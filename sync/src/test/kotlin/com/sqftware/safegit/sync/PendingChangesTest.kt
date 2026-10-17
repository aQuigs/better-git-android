package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingChangesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private val pending = PendingChanges(git)
    private lateinit var remote: File
    private lateinit var phone: Repo

    @Before
    fun setUp() {
        remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        phone = git.splitClone(remote, gitDir = temp.root.resolve("phone.git"), workTree = temp.root.resolve("phone"))
    }

    @Test
    fun hasNoneBeforeTheFirstCommit() {
        write("notes.md", "one\n")

        assertNull(pending.of(phone))
    }

    @Test
    fun isEmptyOnceSynced() {
        write("notes.md", "one\n")
        sync()

        assertTrue(pending.of(phone)!!.isEmpty())
    }

    @Test
    fun sortsEditsByKindUntrackedFilesIncluded() {
        write("notes.md", "one\n")
        write("old.md", "old\n")
        sync()

        write("notes.md", "two\n")
        write("new folder/new.md", "new\n")
        phone.workTree.resolve("old.md").delete()

        assertEquals(Pending(changed = listOf("notes.md"), added = listOf("new folder/new.md"), deleted = listOf("old.md")), pending.of(phone))
    }

    @Test
    fun countsCommitsTheLastSyncCouldNotPush() {
        write("notes.md", "one\n")
        sync()
        remote.renameTo(temp.root.resolve("unreachable.git"))
        write("notes.md", "two\n")
        write("new.md", "new\n")

        assertTrue(sync() is SyncResult.Failed)
        write("later.md", "later\n")

        assertEquals(Pending(changed = listOf("notes.md"), added = listOf("later.md", "new.md"), deleted = emptyList()), pending.of(phone))
    }

    @Test
    fun leavesOutWhatOnlyTheRemoteChanged() {
        write("notes.md", "one\n")
        sync()
        laptop {
            resolve("notes.md").writeText("laptop\n")
            resolve("laptop.md").writeText("laptop\n")
        }
        phoneGit("fetch", "-q", "origin")

        write("phone.md", "phone\n")

        assertEquals(Pending(changed = emptyList(), added = listOf("phone.md"), deleted = emptyList()), pending.of(phone))
    }

    @Test
    fun leavesOutCommitsTheRemoteDroppedWithAForcePush() {
        write("notes.md", "one\n")
        sync()
        laptop { resolve("dropped.md").writeText("soon gone\n") }
        sync()
        val laptop = temp.root.resolve("laptop")
        git.ok(laptop, "reset", "-q", "--hard", "HEAD~1")
        git.ok(laptop, "commit", "-q", "--allow-empty", "-m", "rewritten")
        git.ok(laptop, "push", "-q", "--force", "origin", "HEAD:main")
        phoneGit("fetch", "-q", "origin")

        assertTrue(pending.of(phone)!!.isEmpty())
    }

    @Test
    fun takesEverythingAsNewForARemoteWithoutCommits() {
        write("notes.md", "one\n")
        phoneGit("add", "--all")
        phoneGit("commit", "-q", "-m", "first")

        assertEquals(Pending(changed = emptyList(), added = listOf("notes.md"), deleted = emptyList()), pending.of(phone))
    }

    @Test
    fun hasNoneWhileTheBranchSharesNoHistoryWithTheRemote() {
        laptop { resolve("notes.md").writeText("laptop\n") }
        phoneGit("fetch", "-q", "origin")
        write("notes.md", "phone\n")
        phoneGit("add", "--all")
        phoneGit("commit", "-q", "-m", "first")

        assertNull(pending.of(phone))
    }

    @Test
    fun hasNoneOffABranch() {
        write("notes.md", "one\n")
        sync()
        phoneGit("checkout", "-q", "--detach")

        assertNull(pending.of(phone))
    }

    @Test
    fun leavesOutIgnoredFiles() {
        write(".gitignore", "*.tmp\n")
        sync()
        write("draft.tmp", "scratch\n")

        assertTrue(pending.of(phone)!!.isEmpty())
    }

    @Test
    fun countsASubmoduleWithWorkOfItsOwn() {
        val library = temp.newFolder("lib.git")
        git.ok(library, "init", "-q", "--bare", "-b", "main")
        val libraryCommit = clone("lib-laptop", library).let { clone ->
            clone.resolve("lib.md").writeText("library\n")
            git.ok(clone, "add", "--all")
            git.ok(clone, "commit", "-q", "-m", "library")
            git.ok(clone, "push", "-q", "origin", "HEAD:main")
            git.ok(clone, "rev-parse", "HEAD")
        }
        // By hand, as the sync tests do: `git submodule` needs a shell the device lacks
        laptop {
            resolve(".gitmodules").writeText("[submodule \"lib\"]\n\tpath = lib\n\turl = ../lib.git\n")
            resolve("lib").mkdirs()
            git.ok(this, "update-index", "--add", "--cacheinfo", "160000,$libraryCommit,lib")
        }
        sync()
        assertTrue(pending.of(phone)!!.isEmpty())

        write("lib/lib.md", "library, edited on the phone\n")

        assertEquals(Pending(changed = listOf("lib"), added = emptyList(), deleted = emptyList()), pending.of(phone))
    }

    @Test
    fun seesAnEditSavedInTheSecondTheIndexWasWritten() {
        // Only the index's own time then tells git to look inside the file, which a copy of it must keep
        phoneGit("config", "core.trustctime", "false")
        val second = 1_700_000_000_000L
        write("notes.md", "one\n")
        phone.workTree.resolve("notes.md").setLastModified(second)
        sync()
        phone.gitDir.resolve("index").setLastModified(second)

        write("notes.md", "two\n")
        phone.workTree.resolve("notes.md").setLastModified(second)

        assertEquals(Pending(changed = listOf("notes.md"), added = emptyList(), deleted = emptyList()), pending.of(phone))
    }

    @Test
    fun leavesTheIndexAlone() {
        write("notes.md", "one\n")
        sync()
        val index = phone.gitDir.resolve("index")
        val before = index.readBytes().toList()
        phone.workTree.resolve("notes.md").setLastModified(9_000_000)

        pending.of(phone)

        assertEquals(before, index.readBytes().toList())
    }

    private fun sync() = SafeSync(git, PHONE, "Pixel").sync(phone)

    private fun phoneGit(vararg args: String) = RepoGit(git, phone, PHONE).check(*args)

    private fun clone(name: String, from: File) = temp.newFolder(name).also { git.ok(it, "clone", "-q", from.path, ".") }

    /** Commits and pushes what [edit] does to the laptop's clone. */
    private fun laptop(edit: File.() -> Unit) {
        val laptop = temp.root.resolve("laptop").takeIf { it.exists() }?.also { git.ok(it, "pull", "-q") } ?: clone("laptop", remote)
        laptop.edit()
        git.ok(laptop, "add", "--all")
        git.ok(laptop, "commit", "-q", "-m", "laptop")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")
    }

    private fun write(path: String, text: String) {
        phone.workTree.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }
}
