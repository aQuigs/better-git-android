package com.sqftware.safegit.sync

import com.sqftware.safegit.git.GitResult

/**
 * A three-way merge by `git merge-tree`, which writes the result as a [tree] and touches neither the folder, the index
 * nor any branch. A file both sides changed in the same lines holds both versions between conflict markers in it.
 */
internal class MergeTree(
    val tree: String,
    /** Files whose conflict markers can show both versions: two text versions of one file. */
    val marked: List<Conflicted>,
    /** Conflicts markers cannot show, such as a binary file, an edit against a delete or a file against a folder. */
    val unmarkable: List<String>,
) {
    class Conflicted(val path: String, val base: Stage?, val ours: Stage, val theirs: Stage)

    class Stage(val mode: String, val blob: String)

    private class Message(val paths: List<String>, val type: String) {
        // A file in the way of a folder goes into the tree as "<name>~<side>", a name only the merge made
        val shownPaths get() = paths.filter { path -> paths.none { it != path && path.startsWith("$it~") } }
    }

    companion object {
        private val TEXT = setOf("Auto-merging", "CONFLICT (contents)")

        /** Reads the output of `merge-tree --write-tree -z`. */
        fun parse(result: GitResult): MergeTree {
            // The tree, then "<mode> <object> <stage>\t<path>" for each conflicted stage, an empty field, and then
            // messages as "<path count>", that many paths, "<type>" and "<message>"
            val fields = result.stdout.split('\u0000')
            val stages = fields.drop(1).takeWhile { it.isNotEmpty() }.map { entry ->
                val (mode, blob, stage) = entry.substringBefore("\t").split(" ")
                Triple(entry.substringAfter("\t"), stage.toInt(), Stage(mode, blob))
            }
            val messages = buildList {
                var at = stages.size + 2
                while (at < fields.size && fields[at].isNotEmpty()) {
                    val count = fields[at].toInt()
                    add(Message(fields.subList(at + 1, at + 1 + count), fields[at + 1 + count]))
                    at += count + 3
                }
            }

            val marked = stages.groupBy({ it.first }, { it.second to it.third }).mapNotNull { (path, entries) ->
                val byStage = entries.toMap()
                val ours = byStage[2]
                val theirs = byStage[3]
                val text = messages.filter { path in it.paths }.all { it.type in TEXT }
                if (ours == null || theirs == null || !text || listOf(ours, theirs).any { it.mode !in Change.FILE_MODES }) null
                else Conflicted(path, byStage[1], ours, theirs)
            }
            val unmarkable = messages.filter { it.type.startsWith("CONFLICT") }.flatMap { it.shownPaths }
                .filter { path -> marked.none { it.path == path } }.distinct().sorted()
            if (!result.ok && marked.isEmpty() && unmarkable.isEmpty()) throw GitFailure("merge-tree", result)
            return MergeTree(fields.first(), marked, unmarkable)
        }
    }
}
