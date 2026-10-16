package com.sqftware.safegit.auth

import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.TokenCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    private var job: Job? = null
    private var attempt = 0

    private var session: Session? = null
    private var read = false

    init {
        scope.launch { current.value = session()?.let { State.SignedIn(it.account) } ?: signedOut() }
    }

    /** Blocks on the first call, which reads the store. */
    fun credentials(): Credentials = session()?.let { TokenCredentials(GITHUB, it.token) } ?: Credentials.NONE

    /** Who commits: the account by its private GitHub email, or the app on [device] before sign-in. */
    fun identity(device: String): Identity = session()?.account?.let {
        // Git refuses a name with no letters or digits, such as one that is all punctuation
        val name = it.name?.takeIf { name -> name.any(Char::isLetterOrDigit) } ?: it.login
        Identity(name, "${it.id}+${it.login}@users.noreply.github.com")
    } ?: Identity("SafeGit on $device", "safegit@users.noreply.github.com")

    @Synchronized
    fun signIn() {
        val flow = flow ?: return
        if (current.value == State.Starting || current.value is State.Waiting) return
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
        if (current.value != State.Starting && current.value !is State.Waiting) return
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
                is Poll.Granted -> return Session(flow.account(poll.token), poll.token)
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
            State.SignedIn(session.account)
        } catch (e: Exception) {
            State.Failed(e.message ?: e.toString())
        }
    }

    @Synchronized
    private fun session(): Session? {
        if (!read) remember(store.load())
        return session
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
    }
}
