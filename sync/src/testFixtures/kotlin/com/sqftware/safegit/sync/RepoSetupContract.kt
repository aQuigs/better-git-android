package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * How [RepoSetup] connects a phone's folder to a remote, run like [SafeSyncContract] against desktop git and the
 * bundled git, with the folder where [workTreeParent] puts it.
 */
abstract class RepoSetupContract(tempParent: File? = null, workTreeParent: File? = tempParent) {
    protected abstract val git: Git

    @get:Rule
    val temp = TemporaryFolder(tempParent)

    @get:Rule
    val workTrees = TemporaryFolder(workTreeParent)

    private lateinit var remote: File
    private lateinit var laptop: File
    private lateinit var folder: File
    private val gitDir get() = temp.root.resolve("repos/phone.git")

    @Before
    fun setUp() {
        remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "trunk")
        laptop = temp.root.resolve("laptop")
        git.ok(temp.root, "clone", "-q", remote.path, laptop.path)
        laptop.resolve("notes.md").writeText("one\ntwo\nthree\n")
        laptop.commitAndPush("first notes")
        folder = workTrees.newFolder("vault")
    }

    @Test
    fun fillsAnEmptyFolderOnTheFirstSync() {
        val repo = connect()

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync(repo))

        assertEquals("one\ntwo\nthree\n", folder.resolve("notes.md").readText())
        assertEquals("", RepoGit(git, repo, LAPTOP).check("status", "--porcelain").stdout)
    }

    @Test
    fun followsTheRemotesDefaultBranch() {
        val repo = connect()

        assertEquals("refs/heads/trunk", RepoGit(git, repo, LAPTOP).check("symbolic-ref", "HEAD").stdout.trim())
    }

    @Test
    fun setsTheCaseRulesOfTheFoldersStorage() {
        val repo = connect()

        assertEquals(ignoresCase(folder).toString(), RepoGit(git, repo, LAPTOP).check("config", "core.ignorecase").stdout.trim())
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test
    fun mergesFilesAlreadyInTheFolder() {
        folder.resolve("notes.md").writeText("one\ntwo\nthree\n")
        folder.resolve("phone.md").writeText("only on the phone\n")

        assertTrue(sync(connect()) is SyncResult.Synced)

        git.ok(laptop, "pull", "-q")
        assertEquals("only on the phone\n", laptop.resolve("phone.md").readText())
        assertEquals("one\ntwo\nthree\n", folder.resolve("notes.md").readText())
    }

    @Test
    fun startsFromTheFoldersOwnGit() {
        // A folder another git app kept: its clone is behind, with an edit it never committed
        git.ok(temp.root, "clone", "-q", remote.path, folder.path)
        laptop.resolve("laptop.md").writeText("pushed since\n")
        laptop.commitAndPush("laptop file")
        folder.resolve("notes.md").writeText("one\ntwo\nthree\nfour\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync(connect()))

        git.ok(laptop, "pull", "-q")
        assertEquals("one\ntwo\nthree\nfour\n", laptop.resolve("notes.md").readText())
        assertEquals("pushed since\n", folder.resolve("laptop.md").readText())
    }

    @Test
    fun refusesAFolderRepoStoppedMidRebase() {
        // How Git Sync leaves a conflicting pull: git's own markers in the file, and a commit not yet replayed
        git.ok(temp.root, "clone", "-q", remote.path, folder.path)
        folder.resolve("notes.md").writeText("one\nPHONE\nthree\n")
        git.ok(folder, "commit", "-q", "-am", "phone edit")
        laptop.resolve("notes.md").writeText("one\nLAPTOP\nthree\n")
        laptop.commitAndPush("laptop edit")
        assertFalse(git.run(folder, listOf("pull", "-q", "--rebase"), LAPTOP.environment).ok)

        assertRefused()
    }

    @Test
    fun refusesAFolderRepoStoppedMidMerge() {
        git.ok(temp.root, "clone", "-q", remote.path, folder.path)
        folder.resolve("notes.md").writeText("one\nPHONE\nthree\n")
        git.ok(folder, "commit", "-q", "-am", "phone edit")
        laptop.resolve("notes.md").writeText("one\nLAPTOP\nthree\n")
        laptop.commitAndPush("laptop edit")
        assertFalse(git.run(folder, listOf("pull", "-q", "--no-rebase"), LAPTOP.environment).ok)

        assertRefused()
    }

    @Test
    fun refusesAFolderRepoOfAnotherRemote() {
        val other = temp.newFolder("other.git")
        git.ok(other, "init", "-q", "--bare", "-b", "trunk")
        git.ok(temp.root, "clone", "-q", other.path, folder.path)
        folder.resolve("private.md").writeText("not for this remote\n")
        git.ok(folder, "add", "--all")
        git.ok(folder, "commit", "-q", "-m", "private")

        assertRefused()
    }

    @Test
    fun refusesAFolderRepoOnAnotherBranch() {
        git.ok(temp.root, "clone", "-q", remote.path, folder.path)
        git.ok(folder, "checkout", "-q", "-b", "drafts")

        assertRefused()
    }

    @Test
    fun refusesASparseFolderRepo() {
        git.ok(temp.root, "clone", "-q", remote.path, folder.path)
        git.ok(folder, "update-index", "--skip-worktree", "notes.md")
        folder.resolve("notes.md").delete()

        assertRefused()
    }

    @Test
    fun keepsWhatTheFolderRepoExcludes() {
        git.ok(temp.root, "clone", "-q", remote.path, folder.path)
        folder.resolve(".git/info/exclude").apply { parentFile.mkdirs() }.writeText("private.md\n")
        folder.resolve("private.md").writeText("only on the phone\n")

        assertEquals(SyncResult.UpToDate, sync(connect()))
        assertTrue(folder.resolve("private.md").exists())
    }

    @Test
    fun neverSyncsAProbeFileLeftBehind() {
        folder.resolve(".safegit-probe123.tmp").writeText("")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync(connect()))
    }

    @Test
    fun leavesNothingBehindWhenTheRemoteCannotBeReached() {
        val result = RepoSetup(git, LAPTOP).connect(temp.root.resolve("missing.git").path, gitDir, folder)

        assertEquals(Problem.FETCH, (result as RepoSetup.Result.Failed).problem)
        assertFalse(gitDir.exists())
    }

    private fun assertRefused() {
        val before = git.ok(folder, "status", "--porcelain")

        val result = RepoSetup(git, LAPTOP).connect(remote.path, gitDir, folder)

        assertEquals(Problem.FOLDER_REPO, (result as RepoSetup.Result.Failed).problem)
        assertFalse(gitDir.exists())
        assertEquals(before, git.ok(folder, "status", "--porcelain"))
    }

    private fun connect() = (RepoSetup(git, LAPTOP).connect(remote.path, gitDir, folder) as RepoSetup.Result.Connected).repo

    private fun sync(repo: Repo) = SafeSync(git, PHONE, device = "Pixel").sync(repo)

    private fun File.commitAndPush(message: String) {
        git.ok(this, "add", "--all")
        git.ok(this, "commit", "-q", "-m", message)
        git.ok(this, "push", "-q", "origin", "HEAD")
    }
}
