package com.sqftware.safegit.auth

import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.TokenCredentials
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

    private var session: Session? = null
    private var read = false

    init {
        scope.launch { current.value = session()?.let { State.SignedIn(it.account) } ?: signedOut() }
    }

    /** Blocks on the first call, which reads the store. */
    fun credentials(): Credentials = session()?.let { TokenCredentials(GITHUB, it.token) } ?: Credentials.NONE

    /** Who commits: the account by its private GitHub email, or the app on [device] before sign-in. */
    fun identity(device: String): Identity = session()?.account?.let { Identity(it.name ?: it.login, "${it.id}+${it.login}@users.noreply.github.com") }
        ?: Identity("SafeGit on $device", "safegit@users.noreply.github.com")

    @Synchronized
    fun signIn() {
        val flow = flow ?: return
        if (current.value == State.Starting || current.value is State.Waiting) return
        current.value = State.Starting
        job = scope.launch { current.value = signedIn(flow) }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        current.value = signedOut()
    }

    @Synchronized
    fun signOut() {
        store.clear()
        remember(null)
        current.value = signedOut()
    }

    private suspend fun signedIn(flow: DeviceFlow): State = try {
        val code = flow.start()
        current.value = State.Waiting(code)
        var interval = code.interval
        var granted: String? = null
        while (granted == null) {
            wait(interval)
            when (val poll = flow.poll(code)) {
                is Poll.Wait -> interval = poll.interval
                is Poll.Granted -> granted = poll.token
                is Poll.Ended -> return State.Failed(poll.reason)
            }
        }
        val signedIn = Session(flow.account(granted), granted)
        store.save(signedIn)
        remember(signedIn)
        State.SignedIn(signedIn.account)
    } catch (e: IOException) {
        State.Failed(e.message ?: e.toString())
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

    private companion object {
        const val GITHUB = "https://github.com/"
    }
}
