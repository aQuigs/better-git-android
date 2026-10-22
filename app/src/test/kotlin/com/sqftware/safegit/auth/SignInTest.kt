package com.sqftware.safegit.auth

import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.TokenCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        val signIn = signIn(FakeFlow(Poll.Pending, IOException("dropped"), Poll.SlowDown(10), Poll.Granted(Grant("tok"))))
        settle(signIn)

        signIn.signIn()

        assertEquals(SignIn.State.SignedIn(octo), signIn.state.first { it is SignIn.State.SignedIn })
        assertEquals(Session(octo, "tok"), store.session)
        assertEquals("Octo Cat", signIn.identity("Pixel").name)
        assertEquals("42+octo@users.noreply.github.com", signIn.identity("Pixel").email)
        assertEquals("1", signIn.credentials().environment()["GIT_CONFIG_COUNT"])
    }

    @Test
    fun saysWhenASignInIsGrantedButNotWhenTheSavedOneIsRead() = runBlocking {
        store.session = Session(octo, "old")
        val signIn = signIn(FakeFlow(Poll.Granted(Grant("tok"))))
        val granted = async(start = CoroutineStart.UNDISPATCHED) { signIn.granted.first() }
        settle(signIn)
        assertFalse(granted.isCompleted)

        signIn.signInAgain()

        assertEquals(octo, granted.await())
    }

    @Test
    fun startsSignedInWithTheSavedAccount() = runBlocking {
        store.session = Session(octo, "tok")

        assertEquals(SignIn.State.SignedIn(octo), settle(signIn(FakeFlow())))
    }

    @Test
    fun aTokenThatCannotBeReadYetIsNeverTakenForSignedOut() = runBlocking {
        store.session = Session(octo, "tok")
        store.failures = 1
        val signIn = SignIn(FakeFlow(), store, this, wait = { yield() })

        assertThrows(IOException::class.java) { signIn.credentials() }

        assertEquals(SignIn.State.SignedIn(octo), settle(signIn))
        assertEquals("1", signIn.credentials().environment()["GIT_CONFIG_COUNT"])
    }

    @Test
    fun aTokenThatStaysUnreadableLetsTheUserSignInAnew() = runBlocking {
        store.session = Session(octo, "tok")
        store.failures = Int.MAX_VALUE
        val signIn = SignIn(FakeFlow(Poll.Granted(Grant("new"))), store, this, wait = { yield() })

        signIn.state.first { it is SignIn.State.Failed }
        signIn.signIn()

        assertEquals(SignIn.State.SignedIn(octo), signIn.state.first { it is SignIn.State.SignedIn })
        assertEquals(Session(octo, "new"), store.session)
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
        val signIn = signIn(FakeFlow(Poll.Granted(Grant("tok"))))
        settle(signIn)

        assertTrue(signIn.canSignInAgain("https://github.com/octo/notes.git", signIn.state.value))
        assertFalse(signIn.canSignInAgain("https://gitlab.com/octo/notes.git", signIn.state.value))
        signIn.signInAgain()

        assertEquals(SignIn.State.SignedIn(octo), signIn.state.first { it is SignIn.State.SignedIn })
        assertEquals(Session(octo, "tok"), store.session)
    }

    @Test
    fun signingInAgainLeavesASignInUnderWayToFinish() = runBlocking {
        store.session = Session(octo, "old")
        val polled = CompletableDeferred<Unit>()
        val signIn = SignIn(FakeFlow(Poll.Granted(Grant("tok"))), store, this, wait = { polled.await() })
        settle(signIn)
        signIn.signIn()
        signIn.state.first { it is SignIn.State.Waiting }

        signIn.signInAgain()

        assertEquals(Session(octo, "old"), store.session)
        assertFalse(signIn.canSignInAgain("https://github.com/octo/notes.git", signIn.state.value))
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
                return Poll.Granted(Grant("tok"))
            }

            override fun account(token: String) = octo.also { accountRead.countDown() }

            override fun renew(refreshToken: String) = throw AssertionError("renewed")
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
    fun keepsWhatRenewsTheToken() = runBlocking {
        val signIn = signIn(FakeFlow(Poll.Granted(Grant("tok", Renewal("r1", NOW + HOURS_8)))))
        settle(signIn)

        signIn.signIn()

        signIn.state.first { it is SignIn.State.SignedIn }
        assertEquals(Session(octo, "tok", Renewal("r1", NOW + HOURS_8)), store.session)
    }

    @Test
    fun usesATokenUntilItIsAboutToExpire() = runBlocking {
        store.session = Session(octo, "tok", Renewal("r1", NOW + HOURS_8))
        val flow = FakeFlow()

        assertEquals("tok", signIn(flow).token())
        assertEquals(emptyList<String>(), flow.renewed)
    }

    @Test
    fun renewsATokenAboutToExpireAndSyncsWithTheNewOne() = runBlocking {
        store.session = Session(octo, "old", Renewal("r1", NOW + 60_000))
        val flow = FakeFlow(renewals = listOf(Grant("new", Renewal("r2", NOW + HOURS_8))))
        val signIn = signIn(flow)

        val header = signIn.credentials().environment()["GIT_CONFIG_VALUE_0"]
        assertEquals("new", signIn.token())

        assertEquals(TokenCredentials("https://github.com/", "new").environment()["GIT_CONFIG_VALUE_0"], header)
        assertEquals(listOf("r1"), flow.renewed)
        assertEquals(Session(octo, "new", Renewal("r2", NOW + HOURS_8)), store.session)
    }

    @Test
    fun offlineItSyncsWithTheOldTokenAndRenewsLater() = runBlocking {
        val saved = Session(octo, "old", Renewal("r1", NOW - 1))
        store.session = saved
        val flow = FakeFlow(renewals = listOf(IOException("offline"), Grant("new", Renewal("r2", NOW + HOURS_8))))
        val signIn = signIn(flow)

        assertEquals("old", signIn.token())
        assertEquals(saved, store.session)

        assertEquals("new", signIn.token())
        assertEquals(listOf("r1", "r1"), flow.renewed)
    }

    @Test
    fun anErrorFromGitHubStopsTheSyncAndKeepsTheRefreshToken() = runBlocking {
        val saved = Session(octo, "old", Renewal("r1", NOW - 1))
        store.session = saved
        val signIn = signIn(FakeFlow(renewals = listOf(RenewalRefused("unsupported_grant_type"))))

        assertThrows(RenewalRefused::class.java) { signIn.credentials() }
        assertEquals(saved, store.session)
    }

    @Test
    fun aRenewalThatCannotBeSavedIsSavedOnTheNextUse() = runBlocking {
        store.session = Session(octo, "old", Renewal("r1", NOW - 1))
        store.saveFailures = 1
        val signIn = signIn(FakeFlow(renewals = listOf(Grant("new", Renewal("r2", NOW + HOURS_8)))))

        assertEquals("new", signIn.token())
        assertEquals("old", store.session?.token)

        assertEquals("new", signIn.token())
        assertEquals(Session(octo, "new", Renewal("r2", NOW + HOURS_8)), store.session)
    }

    @Test
    fun aRenewalGitHubRefusesSignsOut() = runBlocking {
        store.session = Session(octo, "old", Renewal("r1", NOW - 1))
        val signIn = signIn(FakeFlow(renewals = listOf(null)))
        settle(signIn)

        assertEquals(Credentials.NONE, signIn.credentials())

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

    private fun CoroutineScope.signIn(flow: DeviceFlow) = SignIn(flow, store, this, wait = {}, clock = { NOW })

    private suspend fun settle(signIn: SignIn) = signIn.state.first { it != SignIn.State.Loading }

    /**
     * Answers each poll with the next of [polls], and each renewal with the next of [renewals] (a [Grant], or null for
     * one refused), throwing the exceptions among them.
     */
    private inner class FakeFlow(vararg polls: Any, renewals: List<Any?> = emptyList()) : DeviceFlow {
        private val polls = ArrayDeque(polls.toList())
        private val renewals = ArrayDeque(renewals)
        val renewed = mutableListOf<String>()

        override fun start() = code

        override fun poll(code: DeviceCode): Poll = when (val next = polls.removeFirstOrNull()) {
            is Poll -> next
            is Exception -> throw next
            else -> throw AssertionError("polled too often")
        }

        override fun account(token: String) = octo

        override fun renew(refreshToken: String): Grant? {
            renewed += refreshToken
            if (renewals.isEmpty()) throw AssertionError("renewed too often")
            return when (val next = renewals.removeFirst()) {
                is Exception -> throw next
                else -> next as Grant?
            }
        }
    }

    private class MemoryStore : TokenStore {
        var session: Session? = null

        /** How many loads still fail, as the Keystore does at times. */
        var failures = 0

        override fun load(): Session? {
            if (failures > 0) {
                failures--
                throw IOException("Keystore busy")
            }
            return session
        }

        /** How many saves still fail, as a full disk does. */
        var saveFailures = 0

        override fun save(session: Session) {
            if (saveFailures > 0) {
                saveFailures--
                throw IOException("No space left on device")
            }
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOURS_8 = 8 * 60 * 60 * 1000L
    }
}
