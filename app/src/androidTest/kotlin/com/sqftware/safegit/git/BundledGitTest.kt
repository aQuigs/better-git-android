package com.sqftware.safegit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledGitTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val git = BundledGit(context).install()

    @get:Rule
    val temp = TemporaryFolder(context.cacheDir)
    private val work get() = temp.root

    @Test
    fun runs() {
        val result = git.run(work, "--version")

        assertTrue(result.stderr, result.ok)
        assertTrue(result.stdout, result.stdout.startsWith("git version 2."))
    }

    @Test
    fun rebasesOneBranchOntoAnother() {
        val repo = work.resolve("repo")
        git.ok(work, "init", "-q", "-b", "main", repo.path)
        git.ok(repo, "config", "user.name", "Test")
        git.ok(repo, "config", "user.email", "test@example.com")
        repo.commitFile("notes.md", "first line\n")
        git.ok(repo, "switch", "-q", "-c", "phone")
        repo.commitFile("phone.md", "from the phone")
        git.ok(repo, "switch", "-q", "main")
        repo.commitFile("laptop.md", "from the laptop")

        git.ok(repo, "rebase", "-q", "main", "phone")

        val subjects = git.ok(repo, "log", "--format=%s").lines()
        assertEquals(listOf("add phone.md", "add laptop.md", "add notes.md"), subjects)
    }

    @Test
    fun clonesOverHttps() {
        val clone = work.resolve("clone")

        git.ok(work, "clone", "-q", "--depth", "1", "https://github.com/aQuigs/better-git-android.git", clone.path)

        assertTrue(clone.resolve("CLAUDE.md").isFile)
    }

    private fun File.commitFile(name: String, text: String) {
        resolve(name).writeText(text)
        git.ok(this, "add", name)
        git.ok(this, "commit", "-q", "-m", "add $name")
    }

    private fun Git.ok(dir: File, vararg args: String): String {
        val result = run(dir, *args)
        assertTrue("git ${args.joinToString(" ")}: ${result.stderr}", result.ok)
        return result.stdout.trim()
    }
}
