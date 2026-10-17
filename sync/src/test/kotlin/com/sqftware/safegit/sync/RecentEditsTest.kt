package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentEditsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private val edits = RecentEdits(git)
    private lateinit var phone: Repo

    @Before
    fun setUp() {
        val remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        phone = git.splitClone(remote, gitDir = temp.root.resolve("phone.git"), workTree = temp.root.resolve("phone"))
        write("notes.md", "one\n", at = 1_000_000)
        write("old.md", "old\n", at = 1_000_000)
        SafeSync(git, PHONE, "Pixel").sync(phone)
    }

    @Test
    fun hasNoneWhileNothingChanged() {
        phone.workTree.resolve("notes.md").setLastModified(9_000_000)

        assertNull(edits.latest(phone))
    }

    @Test
    fun takesTheNewestChangedFileUntrackedOnesIncluded() {
        write("notes.md", "two\n", at = 2_000_000)
        write("new folder/new.md", "new\n", at = 3_000_000)
        phone.workTree.resolve("old.md").delete()

        assertEquals(3_000_000L, edits.latest(phone))
    }

    @Test
    fun leavesTheIndexAlone() {
        val index = phone.gitDir.resolve("index")
        val before = index.readBytes().toList()
        write("notes.md", "two\n", at = 2_000_000)

        edits.latest(phone)

        assertEquals(before, index.readBytes().toList())
    }

    private fun write(path: String, text: String, at: Long) {
        phone.workTree.resolve(path).apply { parentFile.mkdirs() }.apply { writeText(text) }.setLastModified(at)
    }
}
