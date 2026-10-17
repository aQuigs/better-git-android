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
     * Both sides changed the same lines of [paths], and the sync was told to leave conflicts for one the user starts:
     * the local changes are committed, and nothing else changed. A sync that marks conflicts puts both versions in each.
     */
    data class ConflictAhead(val paths: List<String>) : SyncResult

    /** Changes that conflict markers cannot show. Nothing changed; the next sync needs a [Keep] for each of [choices]. */
    data class Choose(val choices: List<Choice>) : SyncResult

    data class Failed(val problem: Problem, val detail: String = "") : SyncResult
}

/**
 * A path the two sides changed in ways conflict markers cannot show: a binary file, an edit against a delete, a file
 * against a folder, or names that differ only by case. [local] and [remote] are what each side holds there, a file's or
 * folder's object, or null for nothing; a [Keep] answers the choice only while both still hold the same.
 */
data class Choice(val path: String, val local: String?, val remote: String?)

/** Which side's version of a path to keep when the two cannot be combined. */
enum class Keep { LOCAL, REMOTE }

/** What a sync is doing, for a user waiting on it: committing on a large shared folder or a slow network can take a while. */
enum class Step { COMMIT, FETCH, MERGE, UPDATE_FOLDER, PUSH }

enum class Problem {
    NOT_ON_BRANCH,
    NO_UPSTREAM,
    FETCH,
    PUSH,

    /** The remote wants a sign-in: it refused the one sent, or none was sent. Hosts such as GitHub say so of a missing repo too. */
    SIGN_IN,
    REMOTE_KEPT_CHANGING,
    FOLDER_KEPT_CHANGING,

    /** Files in the way of the update that git must not overwrite, e.g. ignored ones; the detail lists them. */
    FOLDER_BLOCKED,

    /** Writing the folder failed, e.g. storage full; it was left as it was, or the next sync finishes the job. */
    FOLDER_WRITE,

    /** The folder has a git repo of its own that is no safe place to start from; the detail says why. */
    FOLDER_REPO,

    /** The rest synced, but these submodules were left as they were; the detail gives each one's path and reason. */
    SUBMODULE,
    GIT,
}

/**
 * [otherwise], or [Problem.SIGN_IN] when the remote refused git for want of a sign-in: with prompts off, git stops at
 * asking for a user name or password only once the server has said it needs one.
 */
internal fun GitResult.remoteProblem(otherwise: Problem) =
    if ("could not read Username" in stderr || "could not read Password" in stderr) Problem.SIGN_IN else otherwise

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

    /**
     * Calls [step] as it starts each step, on the thread running the sync. Without [markConflicts], a conflict ends the
     * sync as [SyncResult.ConflictAhead] before the folder moves, so markers never appear in a note the user has open.
     */
    fun sync(
        repo: Repo,
        credentials: Credentials = Credentials.NONE,
        choices: Map<Choice, Keep> = emptyMap(),
        markConflicts: Boolean = true,
        step: (Step) -> Unit = {},
    ): SyncResult = nested(repo, Options(credentials, markConflicts, step), choices, fetched = false).let { (result, problems) ->
        // The user should hear about a submodule left behind even though the rest synced
        if (problems.isEmpty() || (result != SyncResult.UpToDate && result !is SyncResult.Synced)) result
        else SyncResult.Failed(Problem.SUBMODULE, problems.entries.joinToString("\n") { (path, problem) -> "$path: $problem" })
    }

    /** A sync whose submodules left as they were come back as data, for a parent repo to report. */
    private fun nested(repo: Repo, options: Options, choices: Map<Choice, Keep>, fetched: Boolean): NestedSync {
        val session = Session(RepoGit(git, repo, identity), options, choices, fetched)
        val result = try {
            session.sync()
        } catch (e: GitFailure) {
            SyncResult.Failed(Problem.GIT, e.message.orEmpty())
        } catch (e: IOException) {
            SyncResult.Failed(Problem.FOLDER_WRITE, e.message.orEmpty())
        }
        return NestedSync(result, session.submodules.problems)
    }

    /** What a sync shares with the syncs of its submodules. */
    private class Options(val credentials: Credentials, val markConflicts: Boolean, val step: (Step) -> Unit)

    /** With [fetched], the remote was fetched just before, so the first attempt need not fetch again. */
    private inner class Session(
        private val git: RepoGit,
        private val options: Options,
        private val choices: Map<Choice, Keep>,
        private var fetched: Boolean,
    ) {
        private val credentials = options.credentials
        private val step = options.step
        private val folder = FolderUpdate(git)
        private val markers = Markers(device)
        private val held = HeldConflict(git, markers)
        val submodules = SubmoduleSync(git, credentials, choices, step, now) { repo, choices -> nested(repo, options, choices, fetched = true) }

        fun sync(): SyncResult {
            // Nothing else uses this git dir, so a lock left now is from a sync the system killed
            git.repo.gitDir.walk().onEnter { it.name != "objects" }.filter { it.isFile && it.name.endsWith(".lock") }
                .forEach(File::delete)

            val branch = git.branch() ?: return SyncResult.Failed(Problem.NOT_ON_BRANCH)
            // Read from config rather than for-each-ref, which knows no upstream before the branch's first commit
            val config = git.output("config", "--get-regexp", "^branch\\.")?.lines().orEmpty()
                .associate { it.substringBefore(" ") to it.substringAfter(" ") }
            val remote = config["branch.$branch.remote"] ?: return SyncResult.Failed(Problem.NO_UPSTREAM)
            val merge = config["branch.$branch.merge"] ?: return SyncResult.Failed(Problem.NO_UPSTREAM)
            val upstream = "refs/remotes/$remote/${merge.removePrefix("refs/heads/")}"

            folder.resume()?.let { return SyncResult.Failed(Problem.FOLDER_WRITE, it.detail) }
            held.left()?.let { return SyncResult.Conflict(it) }

            var pulled = 0
            var retry: Problem? = null
            repeat(ATTEMPTS) {
                // A blocked update has just committed what blocked it, and needs no new fetch
                if (retry != Problem.FOLDER_KEPT_CHANGING) {
                    stageLocalChanges()?.let { return it }
                    commitStaged()
                    if (!fetched) {
                        step(Step.FETCH)
                        // Submodules are fetched by SubmoduleSync, which can leave one out of reach without failing this
                        val fetch = git.run("fetch", "--quiet", "--no-recurse-submodules", remote, environment = credentials.environment())
                        if (!fetch.ok) return fetch.failed(Problem.FETCH)
                    }
                    fetched = false
                }

                val head = git.head()
                val upstreamHead = git.resolve("$upstream^{commit}")
                val (ahead, behind) = counts(head, upstreamHead, upstream)
                val base = if (ahead > 0 && upstreamHead != null) base(upstream) else null
                var pushing = ahead

                if (behind > 0 || base?.rewound == true) {
                    step(Step.MERGE)
                    val combined = if (base == null) Combined.Commit(upstreamHead!!) else combine(upstreamHead!!, base.commit)
                    val target = when (combined) {
                        is Combined.Choose -> return SyncResult.Choose(combined.choices)
                        is Combined.Commit -> combined.commit
                        is Combined.Marked ->
                            if (options.markConflicts) held.record(combined.tree, upstreamHead, combined.paths)
                            else return SyncResult.ConflictAhead(combined.paths)
                    }
                    if (base != null) {
                        git.check("update-ref", "refs/safegit/backup/${now()}", head!!)
                    }

                    submodules.beforeMove(head, target)?.let { return it }
                    step(Step.UPDATE_FOLDER)
                    when (val moved = folder.move(from = head ?: git.emptyTree, to = target)) {
                        FolderUpdate.Result.Done -> {
                            submodules.bringUp(onlyMoved = true)?.let { return it }
                            if (combined is Combined.Marked) return SyncResult.Conflict(combined.paths)
                            pulled += behind
                        }
                        is FolderUpdate.Result.WriteFailed -> return SyncResult.Failed(Problem.FOLDER_WRITE, moved.detail)
                        // Usually a file changed after the commit, e.g. a notes app saved it: commit that too and retry
                        is FolderUpdate.Result.Blocked -> {
                            stageLocalChanges()?.let { return it }
                            if (!commitStaged()) {
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

                step(Step.PUSH)
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
         * Brings the submodules up to date and stages everything changed in the folder. Stops with the files that would
         * gain this device's conflict markers: those are never committed, whatever their names now, as the user may
         * have renamed a file before resolving it.
         */
        private fun stageLocalChanges(): SyncResult? {
            submodules.bringUp()?.let { return it }
            step(Step.COMMIT)
            git.check("add", "--all", "--", ".", *submodules.excludes().toTypedArray())
            submodules.stage()
            val candidates = git.check("diff", "--cached", "--name-only", "--no-renames", "-z", "-G", markers.pattern).stdout.paths()
            val marked = candidates.filter { markerCount(":$it") > (if (git.head() == null) 0 else markerCount("HEAD:$it")) }
            return if (marked.isEmpty()) null else SyncResult.Conflict(marked)
        }

        private fun markerCount(blob: String): Int {
            if (git.resolve(blob) == null) return 0
            return markers.count(git.check("cat-file", "blob", blob).stdout.lineSequence())
        }

        /** Commits what is staged; false when nothing is. */
        private fun commitStaged(): Boolean {
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

            var merge = git.mergeTree(mergeBase, ours, upstreamHead)
            var asked = ask(unresolved(merge, spelling), ours, upstreamHead)
            // A choice made against other versions could undo an edit made since, so those are asked again. Keeping one
            // side can raise a choice of its own, such as the other end of a rename, so rounds go on while each is answered
            val answered = mutableMapOf<Choice, Keep>()
            while (asked.isNotEmpty() && asked.all { it in choices } && !answered.keys.containsAll(asked)) {
                asked.associateWithTo(answered) { choices.getValue(it) }
                val (keptBase, keptOurs, keptTheirs) = keep(answered, mergeBase, ours, upstreamHead)
                merge = git.mergeTree(keptBase, keptOurs, keptTheirs)
                asked = ask(unresolved(merge, spelling), ours, upstreamHead)
            }
            if (asked.isNotEmpty()) {
                // With those already answered, as the next sync must apply them again to reach these
                return Combined.Choose((answered.keys + asked).distinct())
            }

            if (merge.marked.isNotEmpty()) {
                val tree = git.editTree(
                    merge.tree,
                    // The mode a side changed, as the merge took it
                    merge.marked.joinToString("") { indexEntry(if (it.ours.mode == it.base?.mode) it.theirs.mode else it.ours.mode, markedBlob(it), it.path) },
                )
                return Combined.Marked(tree, merge.marked.map { it.path }.sorted())
            }
            // Nothing local is left once the remote already has it all, e.g. after resolving to its version
            if (merge.tree == git.resolve("$upstreamHead^{tree}")) {
                return Combined.Commit(upstreamHead)
            }
            return Combined.Commit(git.check("commit-tree", merge.tree, "-p", upstreamHead, "-m", message).stdout.trim())
        }

        /** What [merge] cannot show with markers, including names a case-insensitive folder cannot hold side by side. */
        private fun unresolved(merge: MergeTree, spelling: RemoteSpelling?): List<String> {
            val unmarkable = merge.unmarkable + merge.marked.map { it.path }.filterNot(HeldConflict::canRecord)
            if (spelling == null) return unmarkable.sorted()
            // Git sees no conflict in names that differ only by case; the remote's own are not the local changes' doing
            val clashes = caseClashes(git.check("ls-tree", "-r", "-z", "--name-only", merge.tree).stdout.paths())
                .filter { group -> group.any { !spelling.has(it) } }
            return (unmarkable + clashes.flatten()).distinct().sorted()
        }

        // Git also names the old path of a file renamed on one side, which neither side has any more
        private fun ask(paths: List<String>, ours: String, theirs: String) =
            paths.map { Choice(it, git.resolve("$ours:$it"), git.resolve("$theirs:$it")) }.filter { it.local != null || it.remote != null }

        /**
         * [base], [ours] and [theirs] with each chosen path, a file or everything in a folder, made the same on both
         * sides: the kept side's version replaces the other's, so the merge takes it. A path kept deleted goes from the
         * base too, or git could take a file elsewhere for it renamed, which conflicts with the deletion again.
         */
        private fun keep(kept: Map<Choice, Keep>, base: String, ours: String, theirs: String): Triple<String, String, String> {
            val differences = git.diff(ours, theirs)
            fun replacing(tree: String, keep: Keep): String {
                val entries = kept.filterValues { it == keep }.keys
                    .flatMap { choice -> differences.filter { it.path == choice.path || it.path.startsWith("${choice.path}/") } }
                    .map { if (keep == Keep.LOCAL) Triple(it.oldMode, it.oldBlob, it.path) else Triple(it.newMode, it.newBlob, it.path) }
                    // Removals first, so that a file never meets the folder it replaces
                    .sortedBy { (mode) -> mode != Change.ABSENT }
                    .joinToString("") { (mode, blob, path) -> indexEntry(mode, blob, path) }
                return if (entries.isEmpty()) tree else git.editTree(tree, entries)
            }

            val gone = kept.filter { (choice, keep) -> (if (keep == Keep.LOCAL) choice.local else choice.remote) == null }.keys.map { it.path }
            val fromBase = if (gone.isEmpty()) "" else git.check("--literal-pathspecs", "ls-tree", "-r", "-z", base, "--", *gone.toTypedArray())
                .stdout.paths().joinToString("") { indexEntry(Change.ABSENT, it.substringBefore('\t').split(' ')[2], it.substringAfter('\t')) }
            return Triple(
                if (fromBase.isEmpty()) base else git.editTree(base, fromBase),
                replacing(ours, Keep.REMOTE),
                replacing(theirs, Keep.LOCAL),
            )
        }

        /**
         * The file with both versions between markers named for each side. merge-tree has written one already, but
         * names the sides by commit, which says nothing to someone reading the note.
         */
        private fun markedBlob(file: MergeTree.Conflicted): String {
            // The same diff as merge-tree's, so the markers fall where git found the conflict
            val merged = git.run(
                "merge-file", "--diff-algorithm=histogram", "--object-id", "-L", device, "-L", "base", "-L", "remote",
                file.ours.blob, file.base?.blob ?: git.emptyBlob, file.theirs.blob,
            )
            // Exits with the number of conflicts, up to 127
            if (merged.exitCode !in 0..MAX_CONFLICTS) throw GitFailure("merge-file", merged)
            return merged.stdout.trim()
        }
    }

    private class Base(val commit: String, val rewound: Boolean)

    private sealed interface Combined {
        data class Commit(val commit: String) : Combined

        /** A tree whose [paths] hold conflict markers. */
        data class Marked(val tree: String, val paths: List<String>) : Combined

        data class Choose(val choices: List<Choice>) : Combined
    }

    private fun GitResult.failed(problem: Problem) = SyncResult.Failed(remoteProblem(problem), stderr.trim())

    private companion object {
        const val ATTEMPTS = 3
        const val MAX_CONFLICTS = 127
    }
}
