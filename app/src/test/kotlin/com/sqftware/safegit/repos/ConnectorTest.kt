package com.sqftware.safegit.repos

import com.sqftware.safegit.git.CliGit
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.work.AllRun
import com.sqftware.safegit.work.SyncState
import com.sqftware.safegit.work.Syncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConnectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private val repos = MemoryRepoStore()
    private val synced = mutableListOf<SavedRepo>()
    private val syncer = object : Syncer {
        override val states: Flow<Map<String, SyncState>> = emptyFlow()

        override fun sync(repo: SavedRepo, choices: Map<Choice, Keep>) {
            synced += repo
        }

        override fun cancel(repo: SavedRepo) = Unit

        override fun setAuto(repo: SavedRepo, auto: AutoSync) = Unit

        override val all: Flow<AllRun?> = emptyFlow()

        override fun syncAll() = Unit

        override fun cancelAll() = Unit

        override fun closeAll() = Unit
    }
    private lateinit var remote: File
    private lateinit var folder: File
    private val gitDirs get() = temp.root.resolve("git")

    @Before
    fun setUp() {
        remote = temp.newFolder("remote.git")
        assertTrue(git.run(remote, listOf("init", "-q", "--bare")).ok)
        folder = temp.newFolder("vault")
    }

    @Test
    fun savesTheRepoAndSyncsIt() = runBlocking {
        val state = connect(remote.path, folder)

        val repo = (state as Connector.State.Connected).repo
        assertEquals(SavedRepo(repo.id, remote.path, folder), repo)
        assertEquals(listOf(repo), repos.all.first())
        assertEquals(listOf(repo), synced)
        assertTrue(gitDirs.resolve("${repo.id}.git").isDirectory)
    }

    @Test
    fun savesNothingWhenTheRemoteCannotBeReached() = runBlocking {
        val state = connect(temp.root.resolve("missing.git").path, folder)

        assertEquals(Problem.FETCH, (state as Connector.State.Failed).problem)
        assertEquals(emptyList<SavedRepo>(), repos.all.first())
        assertEquals(emptyList<SavedRepo>(), synced)
        assertFalse(gitDirs.list().orEmpty().any { it.endsWith(".git") })
    }

    @Test
    fun refusesAPasswordInTheUrl() = runBlocking {
        assertEquals(Connector.State.Refused(Connector.Refusal.PASSWORD_IN_URL), connect("https://me:ghp_secret@github.com/me/notes.git", folder))
        assertEquals(emptyList<SavedRepo>(), synced)
    }

    @Test
    fun refusesAFolderThatOverlapsAConnectedOne() = runBlocking {
        connect(remote.path, folder)

        val refused = Connector.State.Refused(Connector.Refusal.FOLDER_OVERLAPS)
        assertEquals(refused, connect(remote.path, folder))
        assertEquals(refused, connect(remote.path, folder.resolve("daily").apply { mkdirs() }))
        assertEquals(refused, connect(remote.path, temp.root))
        assertEquals(1, repos.all.first().size)
    }

    private suspend fun CoroutineScope.connect(url: String, folder: File): Connector.State {
        val connector = Connector({ git }, Identity("Phone", "phone@example.com"), { Credentials.NONE }, repos, syncer, { gitDirs.resolve("$it.git") }, this)
        connector.connect(url, folder)
        return connector.state.first { it != Connector.State.Connecting }
    }

    private class MemoryRepoStore : RepoStore {
        override val all = MutableStateFlow(emptyList<SavedRepo>())

        override fun save(repo: SavedRepo) {
            all.value = all.value.filter { it.id != repo.id } + repo
        }
    }
}
