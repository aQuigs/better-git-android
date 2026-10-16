package com.sqftware.safegit.auth

import com.sqftware.safegit.sync.Credentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SignInTest {
    private val octo = Account("octo", 42, "Octo Cat")
    private val code = DeviceCode("WDJB-MJHT", "https://github.com/login/device", "dev123", 5, 900)
    private val store = MemoryStore()

    @Test
    fun signsInAndSyncsAsTheAccount() = runBlocking {
        val signIn = signIn(FakeFlow(Poll.Pending, IOException("dropped"), Poll.SlowDown(10), Poll.Granted("tok")))
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
    fun signingInAgainReplacesTheToken() = runBlocking {
        store.session = Session(octo, "revoked")
        val signIn = signIn(FakeFlow(Poll.Granted("tok")))
        settle(signIn)

        assertTrue(signIn.canSignInAgain("https://github.com/octo/notes.git"))
        assertFalse(signIn.canSignInAgain("https://gitlab.com/octo/notes.git"))
        signIn.signInAgain()

        assertEquals(SignIn.State.SignedIn(octo), signIn.state.first { it is SignIn.State.SignedIn })
        assertEquals(Session(octo, "tok"), store.session)
    }

    @Test
    fun signingInAgainLeavesASignInUnderWayToFinish() = runBlocking {
        store.session = Session(octo, "old")
        val polled = CompletableDeferred<Unit>()
        val signIn = SignIn(FakeFlow(Poll.Granted("tok")), store, this, wait = { polled.await() })
        settle(signIn)
        signIn.signIn()
        signIn.state.first { it is SignIn.State.Waiting }

        signIn.signInAgain()

        assertEquals(Session(octo, "old"), store.session)
        assertFalse(signIn.canSignInAgain("https://github.com/octo/notes.git"))
        polled.complete(Unit)
        signIn.state.first { it is SignIn.State.SignedIn }
        assertEquals(Session(octo, "tok"), store.session)
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
    fun aGrantLandingAfterCancelIsDropped() = runBlocking {
        // The poll is a blocking network call that cancelling cannot stop, so it returns after the user cancelled
        val release = CountDownLatch(1)
        val accountRead = CountDownLatch(1)
        val flow = object : DeviceFlow {
            override fun start() = code

            override fun poll(code: DeviceCode): Poll {
                release.await()
                return Poll.Granted("tok")
            }

            override fun account(token: String) = octo.also { accountRead.countDown() }
        }
        val signIn = SignIn(flow, store, CoroutineScope(Dispatchers.IO), wait = {})
        settle(signIn)
        signIn.signIn()
        signIn.state.first { it is SignIn.State.Waiting }

        signIn.cancel()
        release.countDown()
        assertTrue(accountRead.await(5, TimeUnit.SECONDS))
        delay(300)

        assertEquals(SignIn.State.SignedOut, signIn.state.value)
        assertEquals(null, store.session)
    }

    @Test
    fun commitsUnderTheLoginWhenTheNameIsNoName() = runBlocking {
        store.session = Session(octo.copy(name = "\"';"), "tok")

        assertEquals("octo", signIn(FakeFlow()).identity("Pixel").name)
    }

    @Test
    fun aBuildWithoutAClientIdCannotSignIn() = runBlocking {
        assertEquals(SignIn.State.Unavailable, settle(SignIn(null, store, this)))
    }

    private fun CoroutineScope.signIn(flow: DeviceFlow) = SignIn(flow, store, this, wait = {})

    private suspend fun settle(signIn: SignIn) = signIn.state.first { it != SignIn.State.Loading }

    /** Answers each poll with the next of [polls], throwing the exceptions among them. */
    private inner class FakeFlow(vararg polls: Any) : DeviceFlow {
        private val polls = ArrayDeque(polls.toList())

        override fun start() = code

        override fun poll(code: DeviceCode): Poll = when (val next = polls.removeFirstOrNull()) {
            is Poll -> next
            is Exception -> throw next
            else -> throw AssertionError("polled too often")
        }

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
