package com.sqftware.safegit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledGitTest {
    @get:Rule
    val temp = TemporaryFolder(TestDevice.context.cacheDir)
    private val work get() = temp.root
    private val git = TestDevice.git

    @Test
    fun runs() {
        val result = git.run(work, "--version")

        assertTrue(result.stderr, result.ok)
        assertTrue(result.stdout, result.stdout.startsWith("git version 2."))
    }

    @Test
    fun clonesOverHttps() {
        val clone = work.resolve("clone")

        git.ok(work, "clone", "-q", "--depth", "1", "https://github.com/aQuigs/better-git-android.git", clone.path)

        assertTrue(clone.resolve("CLAUDE.md").isFile)
    }

    private fun Git.ok(dir: File, vararg args: String): String {
        val result = run(dir, *args)
        assertTrue("git ${args.joinToString(" ")}: ${result.stderr}", result.ok)
        return result.stdout.trim()
    }
}
