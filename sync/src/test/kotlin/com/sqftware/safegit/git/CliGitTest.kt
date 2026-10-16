package com.sqftware.safegit.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CliGitTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")

    @Test
    fun runsInTheGivenDirectory() {
        val repo = temp.newFolder()

        assertTrue(git.run(repo, "init", "-q").ok)

        assertEquals(repo.canonicalPath, git.run(repo, "rev-parse", "--show-toplevel").stdout.trim())
    }

    @Test
    fun reportsFailureWithStderr() {
        val result = git.run(temp.newFolder(), "status")

        assertFalse(result.ok)
        assertTrue(result.stderr, result.stderr.contains("not a git repository"))
    }

    @Test
    fun addsPerCallEnvironmentToItsOwn() {
        val git = CliGit("git", mapOf("GIT_AUTHOR_NAME" to "Fixed", "GIT_AUTHOR_EMAIL" to "fixed@example.com"))
        val repo = temp.newFolder()
        git.run(repo, "init", "-q")

        val ident = git.run(repo, listOf("var", "GIT_AUTHOR_IDENT"), mapOf("GIT_AUTHOR_NAME" to "Per Call"))

        assertTrue(ident.stdout, ident.stdout.startsWith("Per Call <fixed@example.com>"))
    }

    @Test
    fun readsLargeOutputOnBothStreams() {
        val repo = temp.newFolder()
        git.run(repo, "init", "-q")
        repeat(2000) { repo.resolve("file$it.txt").writeText("$it") }

        val added = git.run(repo, "add", "--verbose", ".")
        git.run(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "files")
        val checked = git.run(repo, "fsck", "--verbose")

        assertEquals(2000, added.stdout.lines().count { it.startsWith("add ") })
        assertTrue(checked.ok)
        assertTrue(checked.stderr.lines().count { it.startsWith("Checking blob") } >= 2000)
    }
}
