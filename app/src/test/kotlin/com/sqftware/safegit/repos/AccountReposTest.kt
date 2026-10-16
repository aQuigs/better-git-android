package com.sqftware.safegit.repos

import com.sqftware.safegit.net.HttpStatusException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AccountReposTest {
    private val notes = HostedRepo("me/notes", "https://github.com/me/notes.git")
    private val signedIn = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var answer: () -> List<HostedRepo> = { listOf(notes) }
    private val listings = AtomicInteger()
    private val repos = AccountRepos(
        object : RepoDirectory {
            override fun repos(): List<HostedRepo> {
                listings.incrementAndGet()
                return answer()
            }
        },
        signedIn,
        scope,
    )

    @After
    fun stop() = scope.cancel()

    @Test
    fun listsTheAccountsReposOnceSignedIn() = runBlocking {
        val watch = scope.launch { repos.state.collect {} }
        assertEquals(AccountRepos.State.None, awaitState { it == AccountRepos.State.None })

        signedIn.value = true
        assertEquals(AccountRepos.State.Loaded(listOf(notes)), awaitState { it is AccountRepos.State.Loaded })

        signedIn.value = false
        assertEquals(AccountRepos.State.None, awaitState { it == AccountRepos.State.None })
        watch.cancel()
    }

    @Test
    fun saysWhenTheyCannotBeListed() = runBlocking {
        answer = { throw IOException("offline") }
        signedIn.value = true

        val watch = scope.launch { repos.state.collect {} }

        assertEquals(AccountRepos.State.Failed, awaitState { it == AccountRepos.State.Failed })
        watch.cancel()
    }

    @Test
    fun saysWhenGitHubRefusesTheSignIn() = runBlocking {
        answer = { throw HttpStatusException("https://api.github.com/user/repos", 401) }
        signedIn.value = true

        val watch = scope.launch { repos.state.collect {} }

        assertEquals(AccountRepos.State.SignInRefused, awaitState { it == AccountRepos.State.SignInRefused })
        watch.cancel()
    }

    @Test
    fun aScreenBackSoonReusesTheList() = runBlocking {
        signedIn.value = true
        val first = scope.launch { repos.state.collect {} }
        awaitState { it is AccountRepos.State.Loaded }
        first.cancel()

        val second = scope.launch { repos.state.collect {} }
        awaitState { it is AccountRepos.State.Loaded }
        second.cancel()

        assertEquals(1, listings.get())
    }

    private suspend fun awaitState(done: (AccountRepos.State) -> Boolean) = withTimeout(5_000) { repos.state.first(done) }
}
