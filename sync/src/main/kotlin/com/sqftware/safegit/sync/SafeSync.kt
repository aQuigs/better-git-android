package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import com.sqftware.safegit.git.GitResult
import java.io.File
import java.io.IOException

/**
 * A repository whose git dir lives apart from its files, so object and index I/O stays off slow shared storage.
 * Git probes core.ignorecase, core.symlinks and core.filemode in the git dir, so whoever creates one sets them for
 * where [workTree] is.
 */
data class Repo(val gitDir: File, val workTree: File)

data class Identity(val name: String, val email: String) {
    val environment = mapOf(
        "GIT_AUTHOR_NAME" to name,
        "GIT_AUTHOR_EMAIL" to email,
        "GIT_COMMITTER_NAME" to name,
        "GIT_COMMITTER_EMAIL" to email,
    )
}

/** What fetch and push need to reach the remote, read each time since a token can be refreshed between calls. */
fun interface Credentials {
    fun environment(): Map<String, String>

    companion object {
        val NONE = Credentials { emptyMap() }
    }
}

sealed interface SyncResult {
    data object UpToDate : SyncResult

    data class Synced(val pulled: Int, val pushed: Int) : SyncResult

    /** Both sides changed [paths] in ways git cannot combine; the folder, the branch and the remote are left as they were. */
    data class Conflict(val paths: List<String>) : SyncResult

    data class Failed(val problem: Problem, val detail: String = "") : SyncResult
}

enum class Problem {
    NOT_ON_BRANCH,
    NO_UPSTREAM,
    FETCH,
    PUSH,
    REMOTE_KEPT_CHANGING,
    FOLDER_KEPT_CHANGING,

    /** Files in the way of the update that git must not overwrite, e.g. ignored ones; the detail lists them. */
    FOLDER_BLOCKED,

    /** Writing the folder failed, e.g. storage full; it was left as it was, or the next sync finishes the job. */
    FOLDER_WRITE,
    GIT,
}

/**
 * Syncs a repo's current branch with its upstream without ever losing work. Local changes are committed first, then
 * combined with the remote's in one three-way merge that touches neither the folder nor the branch. Only then does the
 * folder move, through [FolderUpdate], which writes only over files git has saved; a conflict changes nothing. The
 * commits a sync replaces stay reachable under refs/safegit/backup/.
 *
 * Callers run one sync per repo at a time, and nothing else may run git in its git dir.
 */
class SafeSync(
    private val git: Git,
    private val identity: Identity,
    device: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val message = "Changes from $device"

    fun sync(repo: Repo, credentials: Credentials = Credentials.NONE): SyncResult = try {
        Session(RepoGit(git, repo, identity), credentials).sync()
    } catch (e: GitFailure) {
        SyncResult.Failed(Problem.GIT, e.message.orEmpty())
    } catch (e: IOException) {
        SyncResult.Failed(Problem.FOLDER_WRITE, e.message.orEmpty())
    }

    private inner class Session(private val git: RepoGit, private val credentials: Credentials) {
        private val folder = FolderUpdate(git)

        fun sync(): SyncResult {
            // Nothing else uses this git dir, so a lock left now is from a sync the system killed
            git.repo.gitDir.walk().onEnter { it.name != "objects" }.filter { it.isFile && it.name.endsWith(".lock") }
                .forEach(File::delete)

            val branch = git.output("symbolic-ref", "--quiet", "--short", "HEAD") ?: return SyncResult.Failed(Problem.NOT_ON_BRANCH)
            // Read from config rather than for-each-ref, which knows no upstream before the branch's first commit
            val config = git.output("config", "--get-regexp", "^branch\\.")?.lines().orEmpty()
                .associate { it.substringBefore(" ") to it.substringAfter(" ") }
            val remote = config["branch.$branch.remote"] ?: return SyncResult.Failed(Problem.NO_UPSTREAM)
            val merge = config["branch.$branch.merge"] ?: return SyncResult.Failed(Problem.NO_UPSTREAM)
            val upstream = "refs/remotes/$remote/${merge.removePrefix("refs/heads/")}"

            folder.resume()?.let { return SyncResult.Failed(Problem.FOLDER_WRITE, it.detail) }

            var pulled = 0
            var retry: Problem? = null
            repeat(ATTEMPTS) {
                // A blocked update has just committed what blocked it, and needs no new fetch
                if (retry != Problem.FOLDER_KEPT_CHANGING) {
                    commitLocalChanges()
                    val fetch = git.run("fetch", "--quiet", remote, environment = credentials.environment())
                    if (!fetch.ok) return fetch.failed(Problem.FETCH)
                }

                val head = git.head()
                val upstreamHead = git.resolve("$upstream^{commit}")
                val (ahead, behind) = counts(head, upstreamHead, upstream)
                val base = if (ahead > 0 && upstreamHead != null) base(upstream) else null
                var pushing = ahead

                if (behind > 0 || base?.rewound == true) {
                    val target = if (base == null) {
                        upstreamHead!!
                    } else {
                        when (val combined = combine(upstreamHead!!, base.commit)) {
                            is Combined.Conflict -> return SyncResult.Conflict(combined.paths)
                            is Combined.Commit -> combined.commit.also { git.check("update-ref", "refs/safegit/backup/${now()}", head!!) }
                        }
                    }

                    when (val moved = folder.move(from = head ?: git.emptyTree, to = target)) {
                        FolderUpdate.Result.Done -> pulled += behind
                        is FolderUpdate.Result.WriteFailed -> return SyncResult.Failed(Problem.FOLDER_WRITE, moved.detail)
                        // Usually a file changed after the commit, e.g. a notes app saved it: commit that too and retry
                        is FolderUpdate.Result.Blocked -> {
                            if (!commitLocalChanges()) {
                                return SyncResult.Failed(Problem.FOLDER_BLOCKED, moved.paths.joinToString("\n"))
                            }
                            retry = Problem.FOLDER_KEPT_CHANGING
                            return@repeat
                        }
                    }
                    pushing = if (target == upstreamHead) 0 else 1
                }
                if (pushing == 0) {
                    return if (pulled == 0) SyncResult.UpToDate else SyncResult.Synced(pulled, pushed = 0)
                }

                val push = git.run("push", "--porcelain", remote, "HEAD:$merge", environment = credentials.environment())
                if (push.ok) {
                    return SyncResult.Synced(pulled, pushed = pushing)
                }
                if ("[rejected]" !in push.stdout) {
                    return push.failed(Problem.PUSH)
                }
                retry = Problem.REMOTE_KEPT_CHANGING
            }
            return SyncResult.Failed(retry!!)
        }

        /** Commits everything changed in the folder; false when nothing was. */
        private fun commitLocalChanges(): Boolean {
            git.check("add", "--all")
            if (git.probe("diff", "--cached", "--quiet").ok) {
                return false
            }
            git.check("commit", "--quiet", "--no-verify", "-m", message)
            return true
        }

        private fun counts(head: String?, upstreamHead: String?, upstream: String): List<Int> = when {
            head == null -> listOf(0, if (upstreamHead == null) 0 else count(upstream))
            upstreamHead == null -> listOf(count("HEAD"), 0)
            else -> git.check("rev-list", "--left-right", "--count", "HEAD...$upstream").stdout.trim().split("\t").map(String::toInt)
        }

        private fun count(revision: String) = git.check("rev-list", "--count", revision).stdout.trim().toInt()

        /**
         * Where the local changes start: the fork point, which unlike the merge base leaves out commits the remote has
         * since dropped with a force push. [Base.rewound] says the remote did drop some, so even with nothing new to
         * pull, they must leave the local branch rather than be pushed back.
         */
        private fun base(upstream: String): Base {
            val mergeBase = git.output("merge-base", "HEAD", upstream)
            val forkPoint = git.output("merge-base", "--fork-point", upstream, "HEAD")
            return Base(forkPoint ?: mergeBase ?: git.emptyTree, rewound = forkPoint != null && forkPoint != mergeBase)
        }

        /** Builds the commit that puts the local changes since [base] on top of [upstreamHead]. */
        private fun combine(upstreamHead: String, base: String): Combined {
            val merge = git.probe(
                "merge-tree", "--write-tree", "--name-only", "--no-messages", "-z", "--merge-base=$base", "HEAD", upstreamHead,
            )
            val lines = merge.stdout.paths()
            if (!merge.ok) {
                return Combined.Conflict(lines.drop(1))
            }
            // Nothing local is left once the remote already has it all, e.g. after resolving to its version
            if (lines.first() == git.resolve("$upstreamHead^{tree}")) {
                return Combined.Commit(upstreamHead)
            }
            return Combined.Commit(git.check("commit-tree", lines.first(), "-p", upstreamHead, "-m", message).stdout.trim())
        }
    }

    private class Base(val commit: String, val rewound: Boolean)

    private sealed interface Combined {
        data class Commit(val commit: String) : Combined

        data class Conflict(val paths: List<String>) : Combined
    }

    private fun GitResult.failed(problem: Problem) = SyncResult.Failed(problem, stderr.trim())

    private companion object {
        const val ATTEMPTS = 3
    }
}
