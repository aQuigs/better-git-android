package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MarkedFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private lateinit var remote: File
    private lateinit var phone: Repo
    private val files get() = MarkedFiles(git, phone, DEVICE)

    @Before
    fun setUp() {
        remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        phone = git.splitClone(remote, gitDir = temp.root.resolve("phone.git"), workTree = temp.root.resolve("phone"))
    }

    @Test
    fun cutsAConflictIntoEachSidesLines() {
        conflict("one\ntwo\nthree\n", phone = "one\nphone\nthree\n", laptop = "one\nlaptop\nthree\n")

        val file = files.open("notes.md")!!

        val block = file.blocks.single()
        assertEquals("phone\n", block.local)
        assertEquals("laptop\n", block.remote)
        assertEquals(MarkedFile.Part.Same("one\n"), file.parts.first())
        assertEquals(MarkedFile.Part.Same("three\n"), file.parts.last())
    }

    @Test
    fun savesTheResolutionSoTheNextSyncPushesIt() {
        conflict("one\ntwo\n", phone = "one\nphone\n", laptop = "one\nlaptop\n")
        val file = files.open("notes.md")!!

        assertEquals(MarkedFiles.Saved.Done, files.save(file, file.text(mapOf(0 to file.blocks.single().both))))

        assertEquals("one\nphone\nlaptop\n", read("notes.md"))
        assertFalse(phone.workTree.resolve(".safegit.tmp").exists())
        assertTrue(sync() is SyncResult.Synced)
        assertEquals("one\nphone\nlaptop\n", git.ok(remote, "show", "main:notes.md") + "\n")
    }

    @Test
    fun leavesTheMarkersOfBlocksNotResolved() {
        val middle = (1..10).joinToString("") { "line $it\n" }
        conflict("a\n${middle}e\n", phone = "phone a\n${middle}phone e\n", laptop = "laptop a\n${middle}laptop e\n")
        val file = files.open("notes.md")!!
        assertEquals(2, file.blocks.size)

        val text = file.text(mapOf(1 to "both e"))

        assertEquals(read("notes.md").substringBefore("<<<<<<< $DEVICE\nphone e") + "both e\n", text)
    }

    @Test
    fun neverSavesOverAFileChangedSinceItOpened() {
        conflict("one\n", phone = "phone\n", laptop = "laptop\n")
        val file = files.open("notes.md")!!
        val edited = read("notes.md") + "typed in a notes app\n"
        write("notes.md", edited)

        assertEquals(MarkedFiles.Saved.Changed, files.save(file, file.text(mapOf(0 to "mine\n"))))

        assertEquals(edited, read("notes.md"))
        assertFalse(phone.workTree.resolve(".safegit.tmp").exists())
    }

    @Test
    fun neverSavesOverAFileGoneSinceItOpened() {
        conflict("one\n", phone = "phone\n", laptop = "laptop\n")
        val file = files.open("notes.md")!!
        phone.workTree.resolve("notes.md").delete()

        assertEquals(MarkedFiles.Saved.Changed, files.save(file, "mine\n"))

        assertFalse(phone.workTree.resolve("notes.md").exists())
    }

    @Test
    fun partsTheSidesWhereTheRemotesOwnTextHasNoSeparator() {
        conflict("Title\n", phone = "Phone title\n=======\n", laptop = "Laptop title\n")

        val block = files.open("notes.md")!!.blocks.single()

        assertEquals("Phone title\n=======\n", block.local)
        assertEquals("Laptop title\n", block.remote)
    }

    @Test
    fun keepsTheFilesLineEndings() {
        conflict("one\r\ntwo\r\n", phone = "one\r\nphone\r\n", laptop = "one\r\nlaptop\r\n")
        val file = files.open("notes.md")!!

        assertEquals("one\r\nmine\r\nand more\r\n", file.text(mapOf(0 to "mine\nand more")))
    }

    @Test
    fun takesMarkersThatOpenNoWholeBlockAsText() {
        val text = "<<<<<<< $DEVICE\nhalf deleted\n"
        write("notes.md", text)

        val file = files.open("notes.md")!!

        assertTrue(file.blocks.isEmpty())
        assertEquals(listOf(MarkedFile.Part.Same(text)), file.parts)
        assertTrue(file.stray)
    }

    @Test
    fun anOpenerLeftFromAResolutionBegunElsewhereNeverSwallowsTheTextAfterIt() {
        val middle = (1..10).joinToString("") { "line $it\n" }
        conflict("a\n${middle}e\n", phone = "phone a\n${middle}phone e\n", laptop = "laptop a\n${middle}laptop e\n")
        // Resolved halfway in a notes app: the remote's side and the closer gone, the opener left
        write("notes.md", read("notes.md").replace("=======\nlaptop a\n>>>>>>> remote\n", ""))

        val file = files.open("notes.md")!!

        val block = file.blocks.single()
        assertEquals("phone e\n", block.local)
        assertEquals("laptop e\n", block.remote)
        assertTrue(file.stray)
        assertEquals("<<<<<<< $DEVICE\nphone a\n${middle}laptop e\n", file.text(mapOf(0 to block.remote)))
    }

    @Test
    fun partsTheSidesWhereTheRemoteFileEndsWithoutANewline() {
        conflict("Title", phone = "Phone title\n=======\n", laptop = "Laptop title")

        val block = files.open("notes.md")!!.blocks.single()

        assertEquals("Phone title\n=======\n", block.local)
        assertEquals("Laptop title\n", block.remote)
    }

    @Test
    fun partsTheSidesWhereTheTextBeforeEndsInASeparator() {
        conflict("H\n=======\nB\n", phone = "H\n=======\nP\n=======\n", laptop = "H\n=======\nQ\n")

        val block = files.open("notes.md")!!.blocks.single()

        assertEquals("P\n=======\n", block.local)
        assertEquals("Q\n", block.remote)
    }

    @Test
    fun leavesABlockAsTextWhenNoSingleSeparatorFits() {
        val text = "<<<<<<< $DEVICE\na\n=======\nb\n=======\nc\n>>>>>>> remote\n"
        write("notes.md", text)

        val file = files.open("notes.md")!!

        assertTrue(file.blocks.isEmpty())
        assertTrue(file.stray)
        assertEquals(text, file.text(emptyMap()))
    }

    @Test
    fun keepsAFileEndingWithoutANewlineSo() {
        conflict("one", phone = "phone", laptop = "laptop")
        val file = files.open("notes.md")!!

        val kept = file.text(mapOf(0 to file.blocks.single().remote))

        assertEquals(read("notes.md").endsWith("\n"), kept.endsWith("\n"))
        assertEquals("laptop", kept.trimEnd())
    }

    @Test
    fun leavesMarkersNamingAnotherDeviceAsText() {
        write("notes.md", "<<<<<<< laptop\na\n=======\nb\n>>>>>>> remote\n")

        assertTrue(files.open("notes.md")!!.blocks.isEmpty())
    }

    @Test
    fun opensNoFileThatIsNotUtf8() {
        phone.workTree.resolve("notes.md").writeBytes(byteArrayOf(0xff.toByte(), '\n'.code.toByte()))

        assertNull(files.open("notes.md"))
    }

    @Test
    fun opensNoFileByAnotherCase() {
        write("Notes.md", "<<<<<<< $DEVICE\na\n=======\nb\n>>>>>>> remote\n")

        assertNull(files.open("notes.md"))
    }

    private fun conflict(base: String, phone: String, laptop: String) {
        write("notes.md", base)
        assertTrue(sync() is SyncResult.Synced)
        laptop { resolve("notes.md").writeText(laptop) }
        write("notes.md", phone)
        assertEquals(SyncResult.Conflict(listOf("notes.md")), sync())
    }

    private fun sync() = SafeSync(git, PHONE, DEVICE).sync(phone)

    private fun laptop(edit: File.() -> Unit) {
        val laptop = temp.newFolder().also { git.ok(it, "clone", "-q", remote.path, ".") }
        laptop.edit()
        git.ok(laptop, "add", "--all")
        git.ok(laptop, "commit", "-q", "-m", "laptop")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")
    }

    private fun read(path: String) = phone.workTree.resolve(path).readText()

    private fun write(path: String, text: String) {
        phone.workTree.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private companion object {
        const val DEVICE = "Pixel"
    }
}
