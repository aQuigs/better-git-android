package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Connects a folder to a remote repo the way [SafeSync] needs it: the git dir in private storage, set for the rules of
 * the folder's storage, with a branch of the remote as upstream. Nothing in the folder is touched. The first sync
 * brings the remote's files in, merging with any already there; where the folder has a `.git` of its own, as one Git
 * Sync made, its last commit is where the folder's own changes start. That repo must sync with the same remote branch
 * and be at rest, or the first sync could push what it holds back: markers of a rebase it stopped, files a sparse
 * checkout left out as deletions, or another repo's files. A submodule's `.git` is usually a file naming its repo, and
 * that repo is usually on no branch; the sync checks a submodule's work before pushing any.
 */
class RepoSetup(private val git: Git, private val identity: Identity) {
    sealed interface Result {
        data class Connected(val repo: Repo, val branch: String) : Result

        data class Failed(val problem: Problem, val detail: String = "") : Result
    }

    /** A remote's branches, to pick one to connect to. */
    sealed interface Branches {
        /** By name, with [default] the one the remote's HEAD names, if it names one. */
        data class Listed(val names: List<String>, val default: String?) : Branches

        data class Failed(val problem: Problem, val detail: String = "") : Branches
    }

    /** Lists [url]'s branches, running git in [dir], which can be any folder. */
    fun branches(url: String, dir: File, credentials: Credentials = Credentials.NONE): Branches {
        if (url.startsWith("-")) return Branches.Failed(Problem.FETCH, "Not a repo URL: $url")
        val heads = lsRemote(dir, url, credentials, "HEAD", "$HEADS*")
        if (!heads.ok) return Branches.Failed(heads.remoteProblem(Problem.FETCH), heads.stderr.trim())

        // "<id>\trefs/heads/<branch>", and "ref: <target>\t<name>" before each symbolic one
        val names = heads.stdout.lines().filterNot { it.startsWith("ref: ") }.map { it.substringAfter("\t") }.filter { it.startsWith(HEADS) }
            .map { it.removePrefix(HEADS) }
        return Branches.Listed(names, defaultBranch(heads.stdout))
    }

    /** Connects to [branch], or to the remote's default branch when it is null. */
    fun connect(
        url: String,
        gitDir: File,
        folder: File,
        credentials: Credentials = Credentials.NONE,
        branch: String? = null,
        submodule: Boolean = false,
    ): Result {
        require(folder.isDirectory) { "No folder at $folder" }
        require(!gitDir.exists()) { "$gitDir already exists" }
        // Git would read it as an option
        if (url.startsWith("-")) return Result.Failed(Problem.FETCH, "Not a repo URL: $url")
        gitDir.parentFile?.mkdirs()

        // Asked before anything is made, so a wrong URL or a missing sign-in leaves nothing to clean up
        val head = lsRemote(gitDir.parentFile, url, credentials, "HEAD")
        if (!head.ok) return Result.Failed(head.remoteProblem(Problem.FETCH), head.stderr.trim())
        val upstream = branch ?: defaultBranch(head.stdout) ?: DEFAULT_BRANCH

        return try {
            create(RepoGit(git, Repo(gitDir, folder), identity), url, upstream, submodule)
        } catch (e: GitFailure) {
            Result.Failed(Problem.GIT, e.message.orEmpty())
        } catch (e: IOException) {
            Result.Failed(Problem.FOLDER_WRITE, e.message.orEmpty())
        }.also { if (it !is Result.Connected) gitDir.deleteRecursively() }
    }

    private fun lsRemote(dir: File, url: String, credentials: Credentials, vararg patterns: String) =
        git.run(dir, listOf("ls-remote", "--symref", url, *patterns), identity.environment + credentials.environment())

    // "ref: refs/heads/<branch>\tHEAD", or nothing from a repo without commits; the pattern HEAD matches any ref ending so
    private fun defaultBranch(lsRemote: String) = lsRemote.lines().firstOrNull { it.startsWith("ref: $HEADS") && it.endsWith("\tHEAD") }
        ?.removePrefix("ref: $HEADS")?.substringBefore("\t")

    private fun create(repoGit: RepoGit, url: String, branch: String, submodule: Boolean): Result {
        val folder = repoGit.repo.workTree
        val storage = probe(folder)
        repoGit.check("init", "--quiet", "--initial-branch", branch)
        // Every command names the folder itself, so a recorded one could only go stale
        repoGit.check("config", "--unset", "core.worktree")
        // Git probes these where the git dir is, which is not the folder's storage
        repoGit.check("config", "core.ignorecase", storage.ignoresCase.toString())
        repoGit.check("config", "core.symlinks", storage.holdsSymlinks.toString())
        repoGit.check("config", "core.filemode", "false")
        repoGit.check("config", "core.untrackedCache", "true")
        repoGit.check("remote", "add", "origin", url)
        repoGit.check("config", "branch.$branch.remote", "origin")
        repoGit.check("config", "branch.$branch.merge", "refs/heads/$branch")

        val exclude = repoGit.repo.gitDir.resolve("info/exclude").apply { parentFile.mkdirs() }
        // A probe file the app was killed before deleting is never synced
        exclude.writeText("/$PROBE*\n")

        val dotGit = folder.resolve(".git")
        val own = (if (dotGit.isDirectory) dotGit else dotGit.takeIf { submodule && it.isFile }?.let(::gitDirOf))?.let { Own(it, folder) }
        if (own != null && own.dir.isDirectory && own.head() != null) {
            own.unusable(url, branch, submodule)?.let { return Result.Failed(Problem.FOLDER_REPO, it) }
            own.dir.resolve("info/exclude").takeIf { it.isFile }?.let { exclude.appendText(it.readText()) }
            repoGit.check(*TRUSTED, "fetch", "--quiet", "--no-recurse-submodules", own.dir.path, "HEAD")
            repoGit.check("update-ref", "refs/heads/$branch", "FETCH_HEAD")
            // The index from that commit, with the folder's stats, so the first sync sees only what changed since
            repoGit.check("read-tree", "HEAD")
            repoGit.probe("update-index", "-q", "--refresh")
        }
        return Result.Connected(repoGit.repo, branch)
    }

    /** The folder's own repo, read but never written. */
    private inner class Own(val dir: File, folder: File) {
        private val git = RepoGit(this@RepoSetup.git, Repo(dir, folder), identity)

        fun head() = output("rev-parse", "--verify", "--quiet", "HEAD^{commit}")

        /** Why its commit is no place to start [branch] of [url] from, or null when it is one. */
        fun unusable(url: String, branch: String, submodule: Boolean): String? {
            if (BUSY.any { dir.resolve(it).exists() } || check("ls-files", "--unmerged").isNotEmpty()) {
                return "It is in the middle of a rebase, merge or cherry-pick. Finish or abort that in the app that started it."
            }
            if (output("config", "--bool", "core.sparseCheckout") == "true" || check("ls-files", "-t").lines().any { it.startsWith("S ") }) {
                return "It has files checked out sparsely, which the first sync would take as deleted."
            }
            val local = output("symbolic-ref", "--quiet", "--short", "HEAD")
            if (local == null) {
                if (!submodule) return "It is not on a branch."
                val ownUrl = output("config", "remote.origin.url")
                return if (ownUrl?.let(::sameRepo) == sameRepo(url)) null else "It syncs with ${ownUrl ?: "no remote"}, not $url."
            }
            val ownUrl = output("config", "branch.$local.remote")?.let { output("config", "remote.$it.url") }
            val ownBranch = output("config", "branch.$local.merge")?.removePrefix("refs/heads/")
            if (ownUrl?.let(::sameRepo) != sameRepo(url) || ownBranch != branch) {
                return "Its branch $local syncs with ${ownBranch ?: "no branch"} of ${ownUrl ?: "no remote"}, not $branch of $url."
            }
            return null
        }

        private fun output(vararg args: String) = git.output(*TRUSTED, *args)

        private fun check(vararg args: String) = git.check(*TRUSTED, *args).stdout.trim()

        // A repo another app cloned over SSH is the one its HTTPS URL names
        private fun sameRepo(url: String) = overHttps(url).trimEnd('/').removeSuffix(".git")
    }

    private class Storage(val ignoresCase: Boolean, val holdsSymlinks: Boolean)

    private fun probe(folder: File): Storage {
        val file = File.createTempFile(PROBE, null, folder)
        val link = file.resolveSibling("${file.name}-link")
        return try {
            Storage(ignoresCase = file.resolveSibling(file.name.uppercase()).exists(), holdsSymlinks = makesLink(link, file))
        } finally {
            link.delete()
            file.delete()
        }
    }

    private fun makesLink(link: File, target: File) = try {
        Files.createSymbolicLink(link.toPath(), target.toPath().fileName)
        true
    } catch (_: IOException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    private companion object {
        const val DEFAULT_BRANCH = "main"
        const val HEADS = "refs/heads/"
        const val PROBE = ".safegit-probe"

        /** Another app's files in shared storage belong to another user, and git refuses to read such a repo. */
        val TRUSTED = arrayOf("-c", "safe.directory=*")
        val BUSY = listOf("rebase-merge", "rebase-apply", "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "sequencer")
    }
}
