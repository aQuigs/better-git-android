package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import com.sqftware.safegit.git.GitResult
import org.junit.Assert.assertTrue
import java.io.File

/** Who the tests' own git commands commit as, playing the other device. */
val LAPTOP = Identity("Laptop", "laptop@example.com")

fun Git.ok(dir: File, vararg args: String): String {
    val result = run(dir, args.toList(), LAPTOP.environment)
    assertTrue("git ${args.joinToString(" ")}: ${result.stderr}", result.ok)
    return result.stdout.trim()
}

/** Clones the way the app does: the git dir in private storage, set for the case rules of the folder's storage. */
fun Git.splitClone(from: File, gitDir: File, workTree: File): Repo {
    val repo = Repo(gitDir, workTree)
    ok(gitDir.parentFile, "clone", "-q", "--no-checkout", "--separate-git-dir", gitDir.path, from.path, workTree.path)
    workTree.resolve(".git").delete()
    val git = RepoGit(this, repo, LAPTOP)
    val probe = workTree.resolve("CaseProbe").apply { writeText("") }
    git.check("config", "core.ignorecase", workTree.resolve("caseprobe").exists().toString())
    probe.delete()
    if (git.head() != null) git.check("checkout", "-q")
    return repo
}

/** A git command as it reaches [Git.run]. */
class GitCall(val args: List<String>, val environment: Map<String, String>, val input: String) {
    /** FolderUpdate's write of the repo's own index, its step after the folder moved; scratch indexes don't count. */
    val updatesIndex get() = "--index-info" in args && "GIT_INDEX_FILE" !in environment
    val movesBranch get() = "update-ref" in args && "HEAD" in args
}

/** Stands in for the system killing the app. */
class Killed : RuntimeException("killed")

/**
 * Calls [action] in place of the first git command that [command] matches, running the command too unless the action
 * returns a result of its own.
 */
fun Git.onFirst(command: (GitCall) -> Boolean, action: () -> GitResult?): Git {
    val git = this
    var done = false
    return object : Git {
        override fun run(dir: File, args: List<String>, environment: Map<String, String>, input: String): GitResult {
            if (!done && command(GitCall(args, environment, input))) {
                done = true
                action()?.let { return it }
            }
            return git.run(dir, args, environment, input)
        }
    }
}

/** Runs [action] just before the first git command whose arguments match [command]. */
fun Git.beforeFirst(command: (List<String>) -> Boolean, action: () -> Unit) =
    onFirst({ command(it.args) }) {
        action()
        null
    }

/** Stands in for Android killing the first git that [command] matches, as it does to child processes. */
fun Git.killing(command: (List<String>) -> Boolean) =
    onFirst({ command(it.args) }) { GitResult(exitCode = 137, stdout = "", stderr = "Killed") }

/** Stands in for the system killing the app after the folder moved, before the index and branch follow. */
fun Git.dyingAtTheLastStep() = onFirst({ it.updatesIndex }) { throw Killed() }

/** Stands in for the system killing the app after the index moved, before the branch follows. */
fun Git.dyingBeforeTheBranchMoves() = onFirst({ it.movesBranch }) { throw Killed() }

/** Stands in for the system killing the app after the branch moved, before the update's record is cleared. */
fun Git.dyingAfterTheBranchMoved(): Git {
    var branchMoved = false
    return onFirst({ call ->
        (branchMoved && "update-ref" in call.args && call.input.startsWith("delete")).also { branchMoved = branchMoved || call.movesBranch }
    }) { throw Killed() }
}
