package com.sqftware.safegit.auth

import com.sqftware.safegit.sync.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SignInTest {
    private val octo = Account("octo", 42, "Octo Cat")
    private val code = DeviceCode("WDJB-MJHT", "https://github.com/login/device", "dev123", 5)
    private val store = MemoryStore()

    @Test
    fun signsInAndSyncsAsTheAccount() = runBlocking {
        val signIn = signIn(FakeFlow(Poll.Wait(5), Poll.Granted("tok")))
        settle(signIn)

        signIn.signIn()

        assertEquals(SignIn.State.SignedIn(octo), signIn.state.first { it is SignIn.State.SignedIn })
        assertEquals(Session(octo, "tok"), store.session)
        assertEquals("Octo Cat", signIn.identity("Pixel").name)
        assertEquals("42+octo@users.noreply.github.com", signIn.identity("Pixel").email)
        assertEquals("1", signIn.credentials().environment()["GIT_CONFIG_COUNT"])
    }

    @Test
    fun startsSignedInWithTheSavedAccount() = runBlocking {
        store.session = Session(octo, "tok")

        assertEquals(SignIn.State.SignedIn(octo), settle(signIn(FakeFlow())))
    }

    @Test
    fun signingOutForgetsTheToken() = runBlocking {
        store.session = Session(octo, "tok")
        val signIn = signIn(FakeFlow())
        settle(signIn)

        signIn.signOut()

        assertEquals(SignIn.State.SignedOut, signIn.state.value)
        assertEquals(null, store.session)
        assertEquals(Credentials.NONE, signIn.credentials())
        assertEquals("SafeGit on Pixel", signIn.identity("Pixel").name)
    }

    @Test
    fun saysWhyItEnded() = runBlocking {
        val signIn = signIn(FakeFlow(Poll.Ended("The authorization request was denied.")))
        settle(signIn)

        signIn.signIn()

        assertEquals(SignIn.State.Failed("The authorization request was denied."), signIn.state.first { it is SignIn.State.Failed })
        assertEquals(null, store.session)
    }

    @Test
    fun aBuildWithoutAClientIdCannotSignIn() = runBlocking {
        assertEquals(SignIn.State.Unavailable, settle(SignIn(null, store, this)))
    }

    private fun CoroutineScope.signIn(flow: DeviceFlow) = SignIn(flow, store, this, wait = {})

    private suspend fun settle(signIn: SignIn) = signIn.state.first { it != SignIn.State.Loading }

    private inner class FakeFlow(vararg polls: Poll) : DeviceFlow {
        private val polls = ArrayDeque(polls.toList())

        override fun start() = code

        override fun poll(code: DeviceCode) = polls.removeFirstOrNull() ?: throw IOException("no more polls")

        override fun account(token: String) = octo
    }

    private class MemoryStore : TokenStore {
        var session: Session? = null

        override fun load() = session

        override fun save(session: Session) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }
}
