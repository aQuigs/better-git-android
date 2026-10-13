package com.sqftware.safegit.sync

/**
 * A three-way merge by `git merge-tree`, which writes the result as a tree and touches neither the folder, the index nor
 * any branch. A file both sides changed in the same lines holds both versions between conflict markers in [tree].
 */
internal class MergeTree(git: RepoGit, base: String, ours: String, theirs: String) {
    val tree: String

    /** Files whose conflict markers can show both versions: two text versions of one file. */
    val marked: List<Stages>

    /** Conflicts markers cannot show, such as a binary file, an edit against a delete or a file against a folder. */
    val unmarkable: List<String>

    init {
        val result = git.probe("merge-tree", "--write-tree", "-z", "--merge-base=$base", ours, theirs)
        // The tree, then "<mode> <object> <stage>\t<path>" for each conflicted stage, an empty field, and then messages
        // as "<path count>", that many paths, "<type>" and "<message>"
        val fields = result.stdout.split('\u0000')
        tree = fields.first()
        val stages = fields.drop(1).takeWhile { it.isNotEmpty() }.map { entry ->
            val (mode, blob, stage) = entry.substringBefore("\t").split(" ")
            Stage(entry.substringAfter("\t"), mode, blob, stage.toInt())
        }
        val messages = buildList {
            var at = stages.size + 2
            while (at < fields.size && fields[at].isNotEmpty()) {
                val count = fields[at].toInt()
                add(Message(fields.subList(at + 1, at + 1 + count), fields[at + 1 + count]))
                at += count + 3
            }
        }

        val conflicts = messages.filter { it.type.startsWith("CONFLICT") }
        marked = stages.groupBy { it.path }.mapNotNull { (path, entries) ->
            fun stage(number: Int) = entries.find { it.stage == number }
            val ours = stage(2)
            val theirs = stage(3)
            val text = messages.filter { path in it.paths }.all { it.type in TEXT } && '\n' !in path
            if (ours == null || theirs == null || !text || listOf(ours, theirs).any { it.mode !in FILE_MODES }) null
            else Stages(path, stage(1)?.blob, ours, theirs)
        }
        unmarkable = conflicts.flatMap { it.shownPaths }.filter { path -> marked.none { it.path == path } }.distinct().sorted()
        if (!result.ok && marked.isEmpty() && unmarkable.isEmpty()) throw GitFailure("merge-tree", result)
    }

    class Stages(val path: String, val base: String?, val ours: Stage, val theirs: Stage)

    class Stage(val path: String, val mode: String, val blob: String, val stage: Int)

    private class Message(val paths: List<String>, val type: String) {
        // A file in the way of a folder goes into the tree as "<name>~<side>", a name only the merge made
        val shownPaths get() = paths.filter { path -> paths.none { it != path && path.startsWith("$it~") } }
    }

    private companion object {
        val TEXT = setOf("Auto-merging", "CONFLICT (contents)")
        val FILE_MODES = setOf("100644", "100755")
    }
}
