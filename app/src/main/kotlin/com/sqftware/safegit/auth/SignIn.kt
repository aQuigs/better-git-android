package com.sqftware.safegit.auth

import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.TokenCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The GitHub account the app syncs as, and signing in to it through [flow] (null in a build without a client id). One
 * sign-in runs at a time, in [scope] rather than a screen's, so it carries on while the user is away approving it in
 * the browser. Syncs read [credentials] and [identity] off the main thread.
 */
class SignIn(
    private val flow: DeviceFlow?,
    private val store: TokenStore,
    private val scope: CoroutineScope,
    private val wait: suspend (seconds: Int) -> Unit = { delay(it * 1000L) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Loading : State

        /** This build cannot sign in. */
        data object Unavailable : State

        data object SignedOut : State

        data object Starting : State

        /** The user enters [code] on GitHub. */
        data class Waiting(val code: DeviceCode) : State

        data class SignedIn(val account: Account) : State

        data class Failed(val reason: String) : State
    }

    private val current = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = current

    private val grants = MutableSharedFlow<Account>(extraBufferCapacity = 1)

    /** Each account a sign-in is granted for, unlike [state] never the saved one read at start. */
    val granted: SharedFlow<Account> = grants

    private var job: Job? = null
    private var attempt = 0

    private var session: Session? = null
    private var read = false
    private val renewing = Any()
    private var unsaved: Session? = null

    init {
        scope.launch { readSaved() }
    }

    /**
     * Blocks on the first call, which reads the store, and on renewing a token about to expire. Throws [IOException]
     * while a saved token cannot be read, or GitHub answers its renewal with an error: a sync sent without it would fail
     * as if the sign-in were refused.
     */
    fun credentials(): Credentials = token()?.let { TokenCredentials(GITHUB, it) } ?: Credentials.NONE

    /** The signed-in account's token, for the host's API; blocks like [credentials]. */
    fun token(): String? = session()?.token

    /** The token as last saved, never renewed, to take out of a bug report. Blocks on the first call, which reads the store. */
    fun savedToken(): String? = saved()?.token

    /** Who commits: the account by its private GitHub email, or the app on [device] before sign-in. */
    fun identity(device: String): Identity = saved()?.account?.let {
        // Git refuses a name with no letters or digits, such as one that is all punctuation
        val name = it.name?.takeIf { name -> name.any(Char::isLetterOrDigit) } ?: it.login
        Identity(name, "${it.id}+${it.login}@users.noreply.github.com")
    } ?: Identity("SafeGit on $device", "safegit@users.noreply.github.com")

    @Synchronized
    fun signIn() {
        val flow = flow ?: return
        if (signingIn()) return
        val mine = ++attempt
        current.value = State.Starting
        job = scope.launch {
            val outcome = try {
                Result.success(session(flow, mine))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            finish(mine, outcome)
        }
    }

    /** Stops a sign-in still waiting for the user; one that already got its token is kept. */
    @Synchronized
    fun cancel() {
        if (!signingIn()) return
        attempt++
        job?.cancel()
        current.value = signedOut()
    }

    @Synchronized
    fun signOut() {
        attempt++
        job?.cancel()
        store.clear()
        remember(null)
        current.value = signedOut()
    }

    /**
     * Swaps a sign-in the host no longer accepts for a new one; until that is granted, syncs go without. A sign-in under
     * way is left to finish, since the user may already have entered its code.
     */
    @Synchronized
    fun signInAgain() {
        if (signingIn()) return
        signOut()
        signIn()
    }

    /** Whether [signInAgain] can help a remote at [url] that refused the sign-in: the token goes there, and in [state] none is under way. */
    fun canSignInAgain(url: String, state: State) =
        reaches(url) && (state is State.SignedIn || state == State.SignedOut || state is State.Failed)

    /** Whether the token goes to a remote at [url]. */
    fun reaches(url: String) = url.startsWith(GITHUB)

    private fun signingIn() = current.value == State.Starting || current.value is State.Waiting

    private suspend fun session(flow: DeviceFlow, mine: Int): Session {
        val code = flow.start()
        show(mine, State.Waiting(code))
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        var interval = code.interval
        while (true) {
            wait(interval)
            // The user may be minutes in the browser, so a dropped connection meanwhile only costs a poll
            val poll = try {
                flow.poll(code)
            } catch (e: IOException) {
                if (System.currentTimeMillis() > deadline) throw e
                continue
            }
            when (poll) {
                Poll.Pending -> Unit
                is Poll.SlowDown -> interval = poll.interval
                is Poll.Granted -> return Session(flow.account(poll.grant.token), poll.grant.token, poll.grant.renewal)
                is Poll.Ended -> throw Ended(poll.reason)
            }
        }
    }

    // A sign-in cancelled or overtaken by another must not touch the state, since git's network calls cannot be stopped
    @Synchronized
    private fun show(mine: Int, state: State) {
        if (mine == attempt) current.value = state
    }

    @Synchronized
    private fun finish(mine: Int, outcome: Result<Session>) {
        if (mine != attempt) return
        current.value = try {
            val session = outcome.getOrThrow()
            store.save(session)
            remember(session)
            grants.tryEmit(session.account)
            State.SignedIn(session.account)
        } catch (e: Exception) {
            State.Failed(e.message ?: e.toString())
        }
    }

    /**
     * The saved session, renewed first when its token is about to expire. A refresh token works once, so renewals take
     * turns; they hold their own lock, as the network is no place to keep the buttons on the screen waiting.
     */
    private fun session(): Session? = synchronized(renewing) {
        val saved = saved() ?: return null
        saveRenewed()
        val renewal = saved.renewal?.takeIf { clock() >= it.expiresAt - RENEW_BEFORE_MILLIS } ?: return saved
        val flow = flow ?: return saved
        val grant = try {
            flow.renew(renewal.refreshToken)
        } catch (e: RenewalRefused) {
            throw e
        } catch (_: IOException) {
            // Most likely offline, so git then fails as a fetch does, once the sync has committed
            return saved
        }
        renewed(saved, grant)
    }

    // A sign-in or sign-out during the renewal has the say
    @Synchronized
    private fun renewed(saved: Session, grant: Grant?): Session? {
        if (session !== saved) return session
        if (grant == null) {
            store.clear()
            remember(null)
            if (!signingIn()) current.value = signedOut()
            return null
        }
        val renewed = saved.copy(token = grant.token, renewal = grant.renewal)
        remember(renewed)
        unsaved = renewed
        saveRenewed()
        return renewed
    }

    // GitHub retired the saved refresh token as it granted the new one, so a save that fails is tried again until it lands
    @Synchronized
    private fun saveRenewed() {
        val renewed = unsaved ?: return
        try {
            if (renewed === session) store.save(renewed)
            unsaved = null
        } catch (_: Exception) {
        }
    }

    // A read that failed is left unremembered, so the next call tries again
    @Synchronized
    private fun saved(): Session? {
        if (!read) remember(store.load())
        return session
    }

    /**
     * Shows the saved account once it can be read. Until then, after a few tries, it shows the failure, so the user can
     * sign in anew if the token stays unreadable. A sign-in or sign-out meanwhile has the say.
     */
    private suspend fun readSaved() {
        var shown: State = State.Loading
        var seconds = 1
        while (true) {
            val saved = try {
                saved()
            } catch (e: IOException) {
                val failed = State.Failed(e.message ?: e.toString())
                if (seconds >= FAIL_AFTER_SECONDS && current.compareAndSet(shown, failed)) shown = failed
                wait(seconds)
                seconds = (seconds * 2).coerceAtMost(MAX_RETRY_SECONDS)
                continue
            }
            current.compareAndSet(shown, saved?.let { State.SignedIn(it.account) } ?: signedOut())
            return
        }
    }

    @Synchronized
    private fun remember(session: Session?) {
        this.session = session
        read = true
    }

    private fun signedOut() = if (flow == null) State.Unavailable else State.SignedOut

    private class Ended(reason: String) : Exception(reason)

    private companion object {
        const val GITHUB = "https://github.com/"
        const val FAIL_AFTER_SECONDS = 8
        const val MAX_RETRY_SECONDS = 60

        // Longer than a sync takes, so a token never expires halfway through one
        const val RENEW_BEFORE_MILLIS = 10 * 60 * 1000L
    }
}
