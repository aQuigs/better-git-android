package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Connects a folder to a remote repo the way [SafeSync] needs it: the git dir in private storage, set for the rules of
 * the folder's storage, with the remote's default branch as upstream. Nothing in the folder is touched. The first sync
 * brings the remote's files in, merging with any already there; where the folder has a `.git` of its own, as one Git
 * Sync made, its last commit is where the folder's own changes start.
 */
class RepoSetup(private val git: Git, private val identity: Identity) {
    sealed interface Result {
        data class Connected(val repo: Repo) : Result

        data class Failed(val problem: Problem, val detail: String = "") : Result
    }

    fun connect(url: String, gitDir: File, folder: File, credentials: Credentials = Credentials.NONE): Result {
        require(folder.isDirectory) { "No folder at $folder" }
        require(!gitDir.exists()) { "$gitDir already exists" }
        gitDir.parentFile?.mkdirs()
        val repo = Repo(gitDir, folder)
        val git = RepoGit(git, repo, identity)
        return try {
            connect(git, url, credentials)
        } catch (e: GitFailure) {
            gitDir.deleteRecursively()
            Result.Failed(Problem.GIT, e.message.orEmpty())
        }
    }

    private fun connect(git: RepoGit, url: String, credentials: Credentials): Result {
        val folder = git.repo.workTree
        git.check("init", "--quiet")
        // Every command names the folder itself, so a recorded one could only go stale
        git.check("config", "--unset", "core.worktree")
        // Git probes these where the git dir is, which is not the folder's storage
        git.check("config", "core.ignorecase", ignoresCase(folder).toString())
        git.check("config", "core.symlinks", holdsSymlinks(folder).toString())
        git.check("config", "core.filemode", "false")
        git.check("config", "core.untrackedCache", "true")
        git.check("remote", "add", "origin", url)

        val head = git.run("ls-remote", "--symref", "origin", "HEAD", environment = credentials.environment())
        if (!head.ok) {
            git.repo.gitDir.deleteRecursively()
            return Result.Failed(Problem.FETCH, head.stderr.trim())
        }
        // "ref: refs/heads/<branch>\tHEAD", or nothing from a repo without commits
        val branch = head.stdout.lines().firstOrNull { it.startsWith("ref: ") }?.substringAfter("ref: ")?.substringBefore("\t")
            ?.removePrefix("refs/heads/") ?: DEFAULT_BRANCH
        git.check("symbolic-ref", "HEAD", "refs/heads/$branch")
        git.check("config", "branch.$branch.remote", "origin")
        git.check("config", "branch.$branch.merge", "refs/heads/$branch")

        val own = folder.resolve(".git")
        if (own.isDirectory && RepoGit(this.git, Repo(own, folder), identity).head() != null) {
            git.check("fetch", "--quiet", own.path, "HEAD")
            git.check("update-ref", "refs/heads/$branch", "FETCH_HEAD")
            // The index from that commit, with the folder's stats, so the first sync sees only what changed since
            git.check("read-tree", "HEAD")
            git.probe("update-index", "-q", "--refresh")
        }
        return Result.Connected(git.repo)
    }

    private fun ignoresCase(folder: File): Boolean = probe(folder) { file -> file.resolveSibling(file.name.uppercase()).exists() }

    private fun holdsSymlinks(folder: File): Boolean = probe(folder) { file ->
        val link = file.resolveSibling("${file.name}-link")
        try {
            Files.createSymbolicLink(link.toPath(), file.toPath().fileName)
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } finally {
            link.delete()
        }
    }

    private fun probe(folder: File, check: (File) -> Boolean): Boolean {
        val file = File.createTempFile(".safegit-probe", null, folder)
        return try {
            check(file)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val DEFAULT_BRANCH = "main"
    }
}
