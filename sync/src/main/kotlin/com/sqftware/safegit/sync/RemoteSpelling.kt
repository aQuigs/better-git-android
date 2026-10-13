package com.sqftware.safegit.sync

/**
 * The remote's spelling of each file and folder in [upstreamHead], for a case-insensitive folder. Such a folder holds one
 * file under either spelling of its name, so a local file the remote spells in another case is the remote's file.
 */
internal class RemoteSpelling(private val git: RepoGit, private val upstreamHead: String) {
    val paths = git.check("ls-tree", "-r", "-z", "--name-only", upstreamHead).stdout.paths().toSet()
    private val files = unambiguous(paths)
    private val folders = unambiguous(paths.flatMap(::foldersOf).distinct())

    /**
     * [commit]'s tree with each path the remote spells in another case, by its file name or a folder's, renamed to the
     * remote's spelling; [commit] itself when there is none. Merged as two files, the two spellings would make a tree
     * the folder can never hold. Both sides and the merge base need respelling, or git would have to guess the rename
     * from the contents and see a conflict in any file that changed much.
     */
    fun of(commit: String): String {
        val changes = git.diff(commit, upstreamHead)
        val gone = changes.filter { it.oldMode != Change.ABSENT && it.newMode == Change.ABSENT }.associateBy { it.path }
        val added = changes.filter { it.oldMode == Change.ABSENT }.map { it.path }.toSet()
        fun inCommit(path: String) = if (path in paths) path !in added else path in gone

        val renames = gone.keys.mapNotNull { path -> respell(path)?.takeIf { !inCommit(it) }?.let { path to it } }
            .groupBy { it.second }.values.mapNotNull { it.singleOrNull() }
        if (renames.isEmpty()) return commit

        return git.editTree(
            commit,
            renames.joinToString("") { (path, renamed) ->
                val change = gone.getValue(path)
                indexEntry(change.newMode, change.newBlob, path) + indexEntry(change.oldMode, change.oldBlob, renamed)
            },
        )
    }

    private fun respell(path: String): String? {
        files[path.lowercase()]?.let { return it.takeIf { it != path } }
        val folder = foldersOf(path).lastOrNull { it.lowercase() in folders } ?: return null
        return (folders.getValue(folder.lowercase()) + path.substring(folder.length)).takeIf { it != path }
    }

    private fun unambiguous(names: Collection<String>) =
        names.groupBy { it.lowercase() }.filterValues { it.distinct().size == 1 }.mapValues { it.value.first() }
}

/**
 * Groups of [paths] a case-insensitive folder cannot hold side by side: two files, or a file and a folder, whose names
 * differ only by case. Folders that differ only by case are one folder there, so they never clash.
 */
internal fun caseClashes(paths: Collection<String>): List<List<String>> {
    val files = paths.groupBy { it.lowercase() }
    val folders = paths.flatMap { path -> foldersOf(path).map { it.lowercase() to path } }.groupBy({ it.first }, { it.second })
    return files.values.filter { it.size > 1 } + files.mapNotNull { (name, same) -> folders[name]?.let { same + it } }
}

/** The folders [path] sits in, outermost first: "a/b/c.md" gives "a" and "a/b". */
private fun foldersOf(path: String) = path.split("/").dropLast(1).runningReduce { a, b -> "$a/$b" }
