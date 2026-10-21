package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import java.io.IOException

/**
 * A version of a file: what [commit], written at [time] in epoch milliseconds by [author] with the message [subject],
 * left at its path. [blob] is null where the commit deleted the file.
 */
data class PastVersion(val commit: String, val time: Long, val author: String, val subject: String, val blob: String?)

/**
 * A file's versions on the branch, newest first, and [current], the file the branch's last commit holds, if any. While
 * the branch is [held] at a conflict, nothing can be restored until it is resolved.
 */
data class FileVersions(val current: String?, val versions: List<PastVersion>, val held: Boolean = false) {
    /** Whether [version] is the newest and the folder's file should still be it. */
    fun isCurrent(version: PastVersion) = version === versions.firstOrNull() && version.blob == current

    /** Whether restoring [version] would change the file: a deletion or the current file would not. */
    fun canRestore(version: PastVersion) = !held && version.blob != null && version.blob != current
}

/** The files that have versions: those the folder holds, and [deleted], those it no longer does. */
data class RepoFiles(val files: List<String>, val deleted: List<String>) {
    /**
     * A folder by its path ending in "/", or a file, and its [name] within its folder; a folder is [deleted] when all its
     * files are.
     */
    data class Entry(val path: String, val name: String, val deleted: Boolean = false) {
        val folder get() = path.endsWith('/')
    }

    /** What [folder] ("" for the top, otherwise ending in "/") holds: its folders, then its files. */
    fun inFolder(folder: String): List<Entry> {
        val inside = all().filter { it.first.startsWith(folder) }
        val folders = inside.filter { '/' in it.first.removePrefix(folder) }
            .groupBy { it.first.removePrefix(folder).substringBefore('/') }
            .map { (name, files) -> Entry("$folder$name/", "$name/", files.all { it.second }) }
        val files = inside.filter { '/' !in it.first.removePrefix(folder) }.map { (path, deleted) -> Entry(path, path.removePrefix(folder), deleted) }
        return folders + files
    }

    /** Every file whose path holds [query], ignoring case, each named by its whole path. */
    fun matching(query: String) = all().filter { it.first.contains(query, ignoreCase = true) }.map { (path, deleted) -> Entry(path, path, deleted) }

    // As file managers sort, deleted files among the others
    private fun all() = (files.map { it to false } + deleted.map { it to true }).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })

    companion object {
        /** The folder holding [folder], "" for the top. */
        fun parent(folder: String) = folder.removeSuffix("/").substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    }
}

/**
 * A file's past versions in [repo], and putting one back in the folder as an edit, which the next sync commits like any
 * other. The branch and its history are never touched. Callers keep syncs of the repo from running meanwhile.
 */
class FileHistory(git: Git, repo: Repo) {
    enum class Restored {
        DONE,

        /** The folder's file is not the version the user saw: an edit not synced yet, or one a sync has pulled since. */
        CHANGED,

        /** The branch holds a conflict, whose commit holds this device's edits until it is resolved and synced. */
        CONFLICT,

        /** The last commit holds no file there that a version can replace, such as a folder. */
        IN_THE_WAY,
    }

    private val git = RepoGit(git, repo, NOBODY)
    private val previews = ChoicePreviews(git, repo)
    private val workTree = repo.workTree

    /**
     * Every file that has versions: those the branch holds, and those it deleted, unless the folder holds them again (a
     * restore not synced yet). A deleted name another file now has but for case is left out: on storage that ignores
     * case it is that file.
     */
    fun files(): RepoFiles {
        val head = git.head() ?: return RepoFiles(emptyList(), emptyList())
        val tip = tip(head)
        // Entries are "<mode> <type> <object>" then a tab and the path
        val tracked = git.check("ls-tree", "-r", "-z", tip).stdout.paths()
            .filter { it.substringBefore(' ') in Change.FILE_MODES }
            .map { it.substringAfter('\t') }
        // Raw entries, each ":<old mode> <new mode> <old blob> <new blob> D" and a path
        val log = git.check(
            "log", "-z", "--diff-merges=first-parent", "--no-renames", "--diff-filter=D", "--raw", "--no-abbrev", "--format=", tip,
        ).stdout.split('\u0000')
        val present = tracked.mapTo(HashSet()) { it.lowercase() }
        val (back, deleted) = log.indices.mapNotNull { i ->
            val token = log[i].trimStart('\n')
            log.getOrNull(i + 1)?.takeIf { token.startsWith(":") && token.removePrefix(":").substringBefore(' ') in Change.FILE_MODES }
        }.distinct().filter { it.lowercase() !in present }.partition { workTree.resolve(it).isFile }
        return RepoFiles(tracked + back, deleted)
    }

    fun of(path: String): FileVersions {
        val head = git.head() ?: return FileVersions(null, emptyList())
        val held = HeldConflict.isHeld(git, head)
        val tip = tip(head)
        // Every version a merge left, even one that took a single side's, and those of the side it dropped
        val log = git.check(
            "--literal-pathspecs", "log", "-z", "--full-history", "--diff-merges=first-parent", "--no-renames", "--raw", "--no-abbrev",
            "--format=%H%x00%at%x00%an%x00%s", tip, "--", path,
        ).stdout.split('\u0000')

        // Each commit's four fields, then its raw entries, each ":<old mode> <new mode> <old blob> <new blob> <status>"
        // and a path
        val versions = mutableListOf<PastVersion>()
        var commit = emptyList<String>()
        var i = 0
        while (i < log.size) {
            val token = log[i].trimStart('\n')
            when {
                token.startsWith(":") -> {
                    val (oldMode, mode, _, blob) = token.removePrefix(":").split(" ")
                    val kept = mode in Change.FILE_MODES || (mode == Change.ABSENT && oldMode in Change.FILE_MODES)
                    if (log[i + 1] == path && kept) {
                        versions += PastVersion(commit[0], commit[1].toLong() * MILLIS, commit[2], commit[3], blob.takeIf { mode != Change.ABSENT })
                    }
                    i += 2
                }
                COMMIT.matches(token) -> {
                    commit = log.subList(i, i + COMMIT_FIELDS)
                    i += COMMIT_FIELDS
                }
                else -> i++
            }
        }
        return FileVersions(last(head, path)?.second, versions, held)
    }

    /** How [version] differs from [versions]' current one, as [ChoicePreview.remote] against [ChoicePreview.local]. */
    fun preview(path: String, versions: FileVersions, version: PastVersion) = previews.between(path, versions.current, version.blob)

    /**
     * Puts [version] of [path] in the folder through [FolderUpdate], so only over the current version of [versions], the
     * one the user saw, and only while the folder still holds it: no edit is ever lost.
     */
    fun restore(path: String, versions: FileVersions, version: PastVersion): Restored {
        val blob = requireNotNull(version.blob) { "A deletion is no version to restore" }
        val head = git.head()
        if (HeldConflict.isHeld(git, head)) return Restored.CONFLICT
        val folder = FolderUpdate(git)
        // A move a killed sync left is the next sync's to finish or put back, by what the folder holds
        if (folder.pending()) return Restored.CHANGED
        val last = head?.let { last(it, path) }
        if (last != null && last.first !in Change.FILE_MODES) return Restored.IN_THE_WAY
        if (last?.second != versions.current) return Restored.CHANGED

        val from = head?.let { "$it^{tree}" } ?: git.emptyTree
        val to = git.editTree(from, indexEntry(Change.FILE, blob, path))
        return when (val written = folder.write(from, to)) {
            is FolderUpdate.Result.Done -> Restored.DONE
            is FolderUpdate.Result.Blocked -> Restored.CHANGED
            is FolderUpdate.Result.WriteFailed -> throw IOException(written.detail)
        }
    }

    // A held conflict's markers are this device's working state, never a version anyone kept
    private fun tip(head: String) = if (HeldConflict.isHeld(git, head)) "$head^" else head

    /** The mode and object of what [commit] holds at [path], or null for nothing. */
    private fun last(commit: String, path: String): Pair<String, String>? =
        // Entries are "<mode> <type> <object>" then a tab and the path
        git.check("--literal-pathspecs", "ls-tree", "-z", commit, "--", path).stdout.paths()
            .firstOrNull { it.substringAfter('\t') == path }
            ?.substringBefore('\t')?.split(' ')?.let { it[0] to it[2] }

    private companion object {
        val COMMIT = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
        const val COMMIT_FIELDS = 4
        const val MILLIS = 1000
    }
}
