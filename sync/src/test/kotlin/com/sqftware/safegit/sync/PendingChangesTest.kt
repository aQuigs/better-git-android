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
        val laptop = temp.newFolder("laptop")
        git.ok(laptop, "clone", "-q", remote.path, ".")
        laptop.resolve("notes.md").writeText("laptop\n")
        laptop.resolve("laptop.md").writeText("laptop\n")
        git.ok(laptop, "add", "--all")
        git.ok(laptop, "commit", "-q", "-m", "laptop")
        git.ok(laptop, "push", "-q")
        RepoGit(git, phone, PHONE).check("fetch", "-q", "origin")

        write("phone.md", "phone\n")

        assertEquals(Pending(changed = emptyList(), added = listOf("phone.md"), deleted = emptyList()), pending.of(phone))
    }

    @Test
    fun takesEverythingAsNewForARemoteWithoutCommits() {
        write("notes.md", "one\n")
        RepoGit(git, phone, PHONE).run {
            check("add", "--all")
            check("commit", "-q", "-m", "first")
        }

        assertEquals(Pending(changed = emptyList(), added = listOf("notes.md"), deleted = emptyList()), pending.of(phone))
    }

    @Test
    fun leavesOutIgnoredFiles() {
        write(".gitignore", "*.tmp\n")
        sync()
        write("draft.tmp", "scratch\n")

        assertTrue(pending.of(phone)!!.isEmpty())
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

    private fun write(path: String, text: String) {
        phone.workTree.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }
}
