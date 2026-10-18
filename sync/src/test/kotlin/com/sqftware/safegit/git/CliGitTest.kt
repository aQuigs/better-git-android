package com.sqftware.safegit.git

import com.sqftware.safegit.sync.LAPTOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun anInterruptStopsARunningGitCleanly() {
        val identity = LAPTOP.environment
        val repo = temp.newFolder()
        git.run(repo, "init", "-q")
        repo.resolve("notes.md").writeText("notes")
        git.run(repo, "add", "notes.md")
        git.run(repo, listOf("commit", "-q", "-m", "notes"), identity)
        repo.resolve("notes.md").writeText("edited")
        val index = repo.resolve(".git/index.lock")
        val caller = Thread.currentThread()
        thread {
            val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
            while (!index.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            caller.interrupt()
        }
        val started = System.nanoTime()

        // A commit of every change holds the index's lock while its editor runs
        assertThrows(InterruptedException::class.java) { git.run(repo, listOf("commit", "-a", "-q"), identity + ("GIT_EDITOR" to "sleep 30;:")) }

        assertTrue(System.nanoTime() - started < 10.seconds.inWholeNanoseconds)
        assertFalse(index.exists())
    }

    @Test
    fun anInterruptedThreadStartsNoGit() {
        val repo = temp.newFolder()
        Thread.currentThread().interrupt()

        assertThrows(InterruptedException::class.java) { git.run(repo, "init", "-q") }

        assertFalse(repo.resolve(".git").exists())
    }
}
