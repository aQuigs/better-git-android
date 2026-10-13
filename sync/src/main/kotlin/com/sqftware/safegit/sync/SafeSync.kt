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

    /**
     * Both sides changed the same lines of [paths]. Each now holds both versions between conflict markers, and the rest
     * of the folder is synced. Nothing is pushed while a marker is left; once none is, the next sync pushes it all.
     */
    data class Conflict(val paths: List<String>) : SyncResult

    /**
     * Changes to [paths] that conflict markers cannot show: a binary file, an edit against a delete, a file against a
     * folder, or names that differ only by case. Nothing changed; the next sync needs a [Keep] for each.
     */
    data class Choose(val paths: List<String>) : SyncResult

    data class Failed(val problem: Problem, val detail: String = "") : SyncResult
}

/** Which side's version of a path to keep when the two cannot be combined. */
enum class Keep { LOCAL, REMOTE }

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
 * folder move, through [FolderUpdate], which writes only over files git has saved. A conflict either puts both versions
 * of a file in it between markers, as a rebase would, or changes nothing and asks which version to keep. The commits a
 * sync replaces stay reachable under refs/safegit/backup/.
 *
 * Callers run one sync per repo at a time, and nothing else may run git in its git dir.
 */
class SafeSync(
    private val git: Git,
    private val identity: Identity,
    private val device: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val message = "Changes from $device"

    fun sync(repo: Repo, credentials: Credentials = Credentials.NONE, choices: Map<String, Keep> = emptyMap()): SyncResult = try {
        Session(RepoGit(git, repo, identity), credentials, choices).sync()
    } catch (e: GitFailure) {
        SyncResult.Failed(Problem.GIT, e.message.orEmpty())
    } catch (e: IOException) {
        SyncResult.Failed(Problem.FOLDER_WRITE, e.message.orEmpty())
    }

    private inner class Session(private val git: RepoGit, private val credentials: Credentials, private val choices: Map<String, Keep>) {
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
            markersLeft()?.let { return it }

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
                    var marked: Combined.Marked? = null
                    val target = if (base == null) {
                        upstreamHead!!
                    } else {
                        when (val combined = combine(upstreamHead!!, base.commit)) {
                            is Combined.Choose -> return SyncResult.Choose(combined.paths)
                            is Combined.Commit -> combined.commit
                            // Recorded before the folder moves, so a sync killed after the branch moved still holds the push
                            is Combined.Marked -> combined.commit.also {
                                marked = combined
                                git.check("update-ref", CONFLICT, it)
                            }
                        }.also { git.check("update-ref", "refs/safegit/backup/${now()}", head!!) }
                    }

                    when (val moved = folder.move(from = head ?: git.emptyTree, to = target)) {
                        FolderUpdate.Result.Done -> {
                            marked?.let { return SyncResult.Conflict(it.paths) }
                            pulled += behind
                        }
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

        /**
         * While the branch is at a commit with conflict markers, holds the sync until no marked file has them left. Then
         * takes that commit off the branch, leaving its files to be committed with the resolution.
         */
        private fun markersLeft(): SyncResult? {
            val marked = git.resolve(CONFLICT) ?: return null
            if (marked != git.head()) {
                // The update to it was undone
                git.check("update-ref", "-d", CONFLICT, marked)
                return null
            }
            // The message is the subject, then one marked path per line
            val paths = git.check("cat-file", "commit", marked).stdout.substringAfter("\n\n").substringAfter("\n\n").lines()
                .filter { it.isNotEmpty() }
            val left = paths.filter { hasMarkers(git.repo.workTree.resolve(it)) }
            if (left.isNotEmpty()) {
                return SyncResult.Conflict(left)
            }
            val parent = git.check("rev-parse", "$marked^").stdout.trim()
            git.check("update-ref", "--stdin", input = "update HEAD $parent $marked\ndelete $CONFLICT $marked\n")
            return null
        }

        // "=======" alone is no sign, since Markdown underlines a heading with it
        private fun hasMarkers(file: File) =
            file.isFile && file.useLines { lines -> lines.any { it.startsWith("<<<<<<<") || it.startsWith(">>>>>>>") } }

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
            val spelling = if (git.ignoreCase) RemoteSpelling(git, upstreamHead) else null
            val mergeBase = spelling?.of(base) ?: base
            val ours = spelling?.of("HEAD") ?: "HEAD"

            var merge = MergeTree(git, mergeBase, ours, upstreamHead)
            var unresolved = unresolved(merge, spelling)
            // Choices count only for conflicts there are now: one made earlier could undo a later edit
            if (unresolved.isNotEmpty() && unresolved.all { it in choices }) {
                val (keptOurs, keptTheirs) = keep(unresolved.associateWith { choices.getValue(it) }, ours, upstreamHead)
                merge = MergeTree(git, mergeBase, keptOurs, keptTheirs)
                unresolved = unresolved(merge, spelling)
            }
            if (unresolved.isNotEmpty()) {
                return Combined.Choose(unresolved)
            }

            if (merge.marked.isNotEmpty()) {
                val paths = merge.marked.map { it.path }.sorted()
                val tree = git.editTree(merge.tree, merge.marked.joinToString("") { indexEntry(it.ours.mode, markers(it), it.path) })
                val conflict = "Conflict in ${paths.size} file(s)\n\n${paths.joinToString("\n")}"
                return Combined.Marked(git.check("commit-tree", tree, "-p", upstreamHead, "-m", conflict).stdout.trim(), paths)
            }
            // Nothing local is left once the remote already has it all, e.g. after resolving to its version
            if (merge.tree == git.resolve("$upstreamHead^{tree}")) {
                return Combined.Commit(upstreamHead)
            }
            return Combined.Commit(git.check("commit-tree", merge.tree, "-p", upstreamHead, "-m", message).stdout.trim())
        }

        /** What [merge] cannot show with markers, including names a case-insensitive folder cannot hold side by side. */
        private fun unresolved(merge: MergeTree, spelling: RemoteSpelling?): List<String> {
            if (spelling == null) return merge.unmarkable
            // Git sees no conflict in names that differ only by case; the remote's own are not the local changes' doing
            val theirs = spelling.paths.toSet()
            val clashes = caseClashes(git.check("ls-tree", "-r", "-z", "--name-only", merge.tree).stdout.paths())
                .filter { group -> group.any { it !in theirs } }
            return (merge.unmarkable + clashes.flatten()).distinct().sorted()
        }

        /**
         * [ours] and [theirs] with each chosen path, a file or everything in a folder, made the same on both sides: the
         * kept side's version replaces the other's, so the merge takes it.
         */
        private fun keep(kept: Map<String, Keep>, ours: String, theirs: String): Pair<String, String> {
            // Entries are "<mode> <type> <object>\t<path>"
            val entries = mapOf(Keep.LOCAL to ours, Keep.REMOTE to theirs)
                .mapValues { (_, tree) -> git.check("ls-tree", "-r", "-z", tree).stdout.paths() }
            fun replacing(keep: Keep): String = kept.filterValues { it == keep }.keys.joinToString("") { path ->
                fun under(entry: String) = entry.substringAfter("\t").let { it == path || it.startsWith("$path/") }
                val other = if (keep == Keep.LOCAL) Keep.REMOTE else Keep.LOCAL
                entries.getValue(other).filter(::under).joinToString("") { entry ->
                    val blob = entry.substringBefore("\t").split(" ")[2]
                    indexEntry(Change.ABSENT, "0".repeat(blob.length), entry.substringAfter("\t"))
                } + entries.getValue(keep).filter(::under).joinToString("") { entry ->
                    val (mode, _, blob) = entry.substringBefore("\t").split(" ")
                    indexEntry(mode, blob, entry.substringAfter("\t"))
                }
            }
            val intoOurs = replacing(Keep.REMOTE)
            val intoTheirs = replacing(Keep.LOCAL)
            return Pair(
                if (intoOurs.isEmpty()) ours else git.editTree(ours, intoOurs),
                if (intoTheirs.isEmpty()) theirs else git.editTree(theirs, intoTheirs),
            )
        }

        /** The file with both versions between markers named for each side, which git would name by commit. */
        private fun markers(file: MergeTree.Stages): String {
            val merged = git.run(
                "merge-file", "--object-id", "-L", device, "-L", "base", "-L", "remote",
                file.ours.blob, file.base ?: git.emptyBlob, file.theirs.blob,
            )
            // Exits with the number of conflicts, up to 127
            if (merged.exitCode !in 0..MAX_CONFLICTS) throw GitFailure("merge-file", merged)
            return merged.stdout.trim()
        }
    }

    private class Base(val commit: String, val rewound: Boolean)

    private sealed interface Combined {
        data class Commit(val commit: String) : Combined

        /** A commit whose [paths] hold conflict markers, never to be pushed. */
        data class Marked(val commit: String, val paths: List<String>) : Combined

        data class Choose(val paths: List<String>) : Combined
    }

    private fun GitResult.failed(problem: Problem) = SyncResult.Failed(problem, stderr.trim())

    private companion object {
        const val ATTEMPTS = 3
        const val MAX_CONFLICTS = 127
        const val CONFLICT = "refs/safegit/conflict"
    }
}
