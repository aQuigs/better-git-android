package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChoicePreviewsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private lateinit var remote: File
    private lateinit var phone: Repo

    @Before
    fun setUp() {
        remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        phone = git.splitClone(remote, gitDir = temp.root.resolve("phone.git"), workTree = temp.root.resolve("phone"))
    }

    @Test
    fun showsAnEditAgainstADeleteAsTheSyncAskedIt() {
        write("notes.md", "one\n")
        sync()
        laptop { resolve("notes.md").writeText("one\nlaptop\n") }
        phone.workTree.resolve("notes.md").delete()

        val choice = (sync() as SyncResult.Choose).choices.single()

        assertEquals(ChoicePreview(Version.Deleted, Version.Text(listOf("one", "laptop"), 2)), previewOf(choice))
    }

    @Test
    fun diffsTwoTextsByWhichSideHasEachLine() {
        val preview = previewOf(Choice("notes.md", blob("one\nphone\nthree\n"), blob("one\nremote\nthree\n")))

        val diff = listOf(DiffLine(null, "one"), DiffLine(Keep.REMOTE, "remote"), DiffLine(Keep.LOCAL, "phone"), DiffLine(null, "three"))
        assertEquals(diff, preview.diff)
    }

    @Test
    fun marksTheGapsBetweenThePartsShown() {
        val lines = (1..20).map { "line $it" }
        val phoneText = lines.toMutableList().apply { this[0] = "phone"; this[19] = "phone" }
        val preview = previewOf(Choice("notes.md", blob(phoneText.joinToString("\n", postfix = "\n")), blob(lines.joinToString("\n", postfix = "\n"))))

        assertEquals(1, preview.diff.count { it.text == null })
        assertEquals(DiffLine(Keep.LOCAL, "phone"), preview.diff.last())
    }

    @Test
    fun readsAnImagesBytes() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2, 0xff.toByte())

        val local = previewOf(Choice("photo.PNG", blob(png), null)).local

        assertArrayEquals(png, (local as Version.Image).read())
    }

    @Test
    fun givesTheSizeOfOtherBinaryFiles() {
        val preview = previewOf(Choice("data.bin", blob(byteArrayOf(1, 0, 2)), blob("text\n")))

        assertEquals(Version.Binary(3), preview.local)
        assertTrue(preview.diff.isEmpty())
    }

    @Test
    fun cutsLongLinesAndSplitsOnlyOnNewlines() {
        val long = "x".repeat(1_000)

        val local = previewOf(Choice("app.js", blob("$long\na\rb"), blob(""))).local

        assertEquals(Version.Text(listOf("x".repeat(300) + "…", "a\rb"), 2), local)
    }

    @Test
    fun countsNoLinesInAnEmptyFile() {
        assertEquals(Version.Text(emptyList(), 0), previewOf(Choice("empty.md", blob(""), null)).local)
    }

    @Test
    fun countsTheFilesInAFolder() {
        val inner = phoneGit("mktree", input = "100644 blob ${blob("b\n")}\tb.md\n").stdout.trim()
        val folder = phoneGit("mktree", input = "100644 blob ${blob("a\n")}\ta.md\n040000 tree $inner\tinner\n").stdout.trim()

        assertEquals(Version.Folder(2), previewOf(Choice("notes", folder, null)).local)
    }

    @Test
    fun leavesAnObjectFromAnotherRepoUnread() {
        val commit = "0123456789abcdef0123456789abcdef01234567"

        assertEquals(Version.Elsewhere(commit), previewOf(Choice("lib", commit, null)).local)
    }

    private fun blob(text: String) = phoneGit("hash-object", "-w", "--stdin", input = text).stdout.trim()

    private fun blob(bytes: ByteArray): String {
        val file = temp.newFile().apply { writeBytes(bytes) }
        return phoneGit("hash-object", "-w", file.path).stdout.trim()
    }

    private fun sync() = SafeSync(git, PHONE, "Pixel").sync(phone)

    private fun phoneGit(vararg args: String, input: String = "") = RepoGit(git, phone, PHONE).check(*args, input = input)

    private fun previewOf(choice: Choice) = ChoicePreviews(git, phone).of(choice)

    private fun laptop(edit: File.() -> Unit) {
        val laptop = temp.newFolder("laptop").also { git.ok(it, "clone", "-q", remote.path, ".") }
        laptop.edit()
        git.ok(laptop, "add", "--all")
        git.ok(laptop, "commit", "-q", "-m", "laptop")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")
    }

    private fun write(path: String, text: String) {
        phone.workTree.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }
}
