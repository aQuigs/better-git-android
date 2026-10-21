package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import com.sqftware.safegit.sync.FileHistory.Restored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileHistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private lateinit var remote: File
    private lateinit var phone: Repo
    private var now = 1_000_000L

    @Before
    fun setUp() {
        remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        phone = git.splitClone(remote, gitDir = temp.root.resolve("phone.git"), workTree = temp.root.resolve("phone"))
    }

    @Test
    fun listsEachVersionNewestFirstWithWhoMadeItAndWhen() {
        write("notes.md", "one\n")
        sync()
        laptop("Laptop edit") { resolve("notes.md").writeText("two\n") }
        sync()
        write("other.md", "elsewhere\n")
        sync()
        delete("notes.md")
        sync()

        val versions = history().of("notes.md")

        assertNull(versions.current)
        assertEquals(listOf("Changes from Pixel", "Laptop edit", "Changes from Pixel"), versions.versions.map { it.subject })
        assertEquals(listOf("Phone", "Laptop", "Phone"), versions.versions.map { it.author })
        assertEquals(listOf(null, "two\n", "one\n"), versions.versions.map { it.blob?.let(::text) })
        assertTrue(versions.versions.zipWithNext().all { (newer, older) -> newer.time >= older.time })
    }

    @Test
    fun restoresAVersionAsAnEditTheNextSyncPushes() {
        write("notes.md", "one\n")
        sync()
        write("notes.md", "two\n")
        sync()
        val head = phoneGit("rev-parse", "HEAD")
        val history = history()
        val versions = history.of("notes.md")

        assertEquals(Restored.DONE, history.restore("notes.md", versions, versions.versions.last()))

        assertEquals("one\n", read("notes.md"))
        assertEquals(head, phoneGit("rev-parse", "HEAD"))
        assertEquals(SyncResult.Synced(0, 1), sync())
        assertEquals(listOf("one\n", "two\n", "one\n"), history.of("notes.md").versions.map { text(it.blob!!) })
        assertEquals("one\n", git.ok(remote, "show", "main:notes.md") + "\n")
    }

    @Test
    fun neverWritesOverAnEditNotSyncedYet() {
        write("notes.md", "one\n")
        sync()
        write("notes.md", "two\n")
        sync()
        write("notes.md", "unsynced\n")

        assertEquals(Restored.CHANGED, restoreOldest("notes.md"))

        assertEquals("unsynced\n", read("notes.md"))
    }

    @Test
    fun bringsBackADeletedFileButNeverOverOneMadeSince() {
        write("notes/day.md", "one\n")
        sync()
        delete("notes/day.md")
        sync()
        write("notes/day.md", "new\n")
        assertEquals(Restored.CHANGED, restoreOldest("notes/day.md"))
        assertEquals("new\n", read("notes/day.md"))

        delete("notes/day.md")
        assertEquals(Restored.DONE, restoreOldest("notes/day.md"))
        assertEquals("one\n", read("notes/day.md"))
    }

    @Test
    fun waitsForAFolderUpdateAKilledSyncLeft() {
        write("notes.md", "one\n")
        sync()
        write("notes.md", "two\n")
        sync()
        phoneGit("update-ref", "refs/safegit/move-from", "HEAD")

        assertEquals(Restored.CHANGED, restoreOldest("notes.md"))

        assertEquals("two\n", read("notes.md"))
    }

    @Test
    fun leavesOutTheMarkersOfAHeldConflict() {
        write("notes.md", "one\n")
        sync()
        laptop("Laptop edit") { resolve("notes.md").writeText("laptop\n") }
        write("notes.md", "phone\n")
        assertTrue(sync() is SyncResult.Conflict)

        val versions = history().of("notes.md")

        assertEquals(listOf("Laptop edit", "Changes from Pixel"), versions.versions.map { it.subject })
        assertTrue(text(versions.current!!).startsWith("<<<<<<< Pixel"))
    }

    @Test
    fun previewsAVersionAgainstTheCurrentOne() {
        write("notes.md", "one\nold\n")
        sync()
        write("notes.md", "one\nnew\n")
        sync()
        val history = history()
        val versions = history.of("notes.md")

        val preview = history.preview("notes.md", versions, versions.versions.last())

        assertEquals(listOf(DiffLine(null, "one"), DiffLine(Keep.REMOTE, "old"), DiffLine(Keep.LOCAL, "new")), preview.diff)
    }

    @Test
    fun knowsNoVersionsBeforeTheFirstCommit() {
        assertEquals(FileVersions(null, emptyList()), history().of("notes.md"))
        assertFalse(phone.workTree.resolve("notes.md").exists())
    }

    @Test
    fun neverRestoresWhileAConflictHoldsThisPhonesEdits() {
        write("notes.md", "one\n")
        write("other.md", "base\n")
        sync()
        laptop("Laptop edit") { resolve("notes.md").writeText("laptop\n") }
        write("notes.md", "phone\n")
        write("other.md", "not pushed yet\n")
        assertTrue(sync() is SyncResult.Conflict)

        val versions = history().of("other.md")
        assertTrue(versions.held && versions.versions.none(versions::canRestore))
        assertEquals(Restored.CONFLICT, history().restore("other.md", versions, versions.versions.last()))

        assertEquals("not pushed yet\n", read("other.md"))
    }

    @Test
    fun restoresOnlyOverTheVersionTheUserSaw() {
        write("notes.md", "one\n")
        sync()
        write("notes.md", "two\n")
        sync()
        val history = history()
        val seen = history.of("notes.md")
        laptop("Laptop edit") { resolve("notes.md").writeText("three\n") }
        sync()

        assertEquals(Restored.CHANGED, history.restore("notes.md", seen, seen.versions.last()))

        assertEquals("three\n", read("notes.md"))
    }

    @Test
    fun listsWhatALaptopsMergeMade() {
        mergeOnLaptop()

        val versions = history().of("notes.md").versions

        assertEquals("A\nb\nC\n", text(versions.first().blob!!))
    }

    @Test
    fun listsTheSideAMergeDropped() {
        mergeOnLaptop("-s", "ours")

        val subjects = history().of("notes.md").versions.map { it.subject }

        assertTrue(subjects.toString(), "Laptop edit" in subjects && "Other edit" in subjects)
    }

    @Test
    fun listsTheFilesWithVersionsAndThoseDeletedByFolder() {
        write("ideas.md", "one\n")
        write("Notes/day.md", "one\n")
        write("Notes/old.md", "one\n")
        write("gone/only.md", "one\n")
        write("back.md", "one\n")
        sync()
        delete("Notes/old.md")
        delete("gone/only.md")
        delete("back.md")
        sync()
        write("back.md", "two\n")
        sync()

        val files = history().files()

        assertEquals(setOf("back.md", "ideas.md", "Notes/day.md"), files.files.toSet())
        assertEquals(setOf("gone/only.md", "Notes/old.md"), files.deleted.toSet())
        assertEquals(
            listOf(
                RepoFiles.Entry("gone/", "gone/", deleted = true),
                RepoFiles.Entry("Notes/", "Notes/"),
                RepoFiles.Entry("back.md", "back.md"),
                RepoFiles.Entry("ideas.md", "ideas.md"),
            ),
            files.inFolder(""),
        )
        assertEquals(listOf(RepoFiles.Entry("Notes/day.md", "day.md"), RepoFiles.Entry("Notes/old.md", "old.md", deleted = true)), files.inFolder("Notes/"))
        assertEquals(listOf(RepoFiles.Entry("Notes/day.md", "Notes/day.md"), RepoFiles.Entry("Notes/old.md", "Notes/old.md", deleted = true)), files.matching("notes"))
        assertEquals("Notes/", RepoFiles.parent("Notes/2026/"))
        assertEquals("", RepoFiles.parent("Notes/"))
    }

    @Test
    fun listsARestoredFileAsTheFolderHoldsIt() {
        write("notes.md", "one\n")
        sync()
        delete("notes.md")
        sync()
        assertEquals(listOf("notes.md"), history().files().deleted)

        assertEquals(Restored.DONE, restoreOldest("notes.md"))

        assertEquals(RepoFiles(listOf("notes.md"), emptyList()), history().files())
    }

    @Test
    fun leavesOutADeletedNameAnotherFileHasButForCase() {
        write("notes.md", "one\n")
        sync()
        laptop("Rename by case") {
            git.ok(this, "mv", "notes.md", "Notes.md")
        }
        sync()

        assertEquals(RepoFiles(listOf("Notes.md"), emptyList()), history().files())
    }

    @Test
    fun listsNoFilesBeforeTheFirstCommit() {
        assertEquals(RepoFiles(emptyList(), emptyList()), history().files())
    }

    /** Two laptops edit different lines, and the second merges the first's push with [strategy]. */
    private fun mergeOnLaptop(vararg strategy: String) {
        write("notes.md", "a\nb\nc\n")
        sync()
        val other = temp.root.resolve("other").also { git.ok(temp.root, "clone", "-q", remote.path, it.path) }
        laptop("Laptop edit") { resolve("notes.md").writeText("A\nb\nc\n") }
        other.resolve("notes.md").writeText("a\nb\nC\n")
        git.ok(other, "commit", "-q", "-am", "Other edit")
        git.ok(other, "pull", "-q", "--no-rebase", "--no-edit", *strategy, "origin", "main")
        git.ok(other, "push", "-q", "origin", "HEAD:main")
        sync()
    }

    private fun history() = FileHistory(git, phone)

    private fun restoreOldest(path: String): Restored {
        val history = history()
        val versions = history.of(path)
        return history.restore(path, versions, versions.versions.last { it.blob != null })
    }

    private fun sync() = SafeSync(git, PHONE, "Pixel", now = { now++ }).sync(phone)

    private fun phoneGit(vararg args: String) = RepoGit(git, phone, PHONE).check(*args).stdout.trim()

    private fun text(blob: String) = RepoGit(git, phone, PHONE).check("cat-file", "blob", blob).stdout

    private fun laptop(message: String, edit: File.() -> Unit) {
        val laptop = temp.root.resolve("laptop")
        if (!laptop.exists()) git.ok(temp.root, "clone", "-q", remote.path, laptop.path) else git.ok(laptop, "pull", "-q", "--rebase")
        laptop.edit()
        git.ok(laptop, "add", "--all")
        git.ok(laptop, "commit", "-q", "-m", message)
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")
    }

    private fun write(path: String, text: String) {
        phone.workTree.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun read(path: String) = phone.workTree.resolve(path).readText()

    private fun delete(path: String) = assertTrue(phone.workTree.resolve(path).delete())
}
