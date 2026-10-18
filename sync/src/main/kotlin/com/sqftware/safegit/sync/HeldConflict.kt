package com.sqftware.safegit.sync

import java.io.File

/**
 * A conflict the branch is held at: a commit whose files hold conflict markers, on top of the remote's commit it was
 * merged with. It is recorded before the folder moves to it, so a sync killed after the branch moved still holds, and it
 * is never pushed. Once no marked file has markers left, it leaves the branch and its files are committed as the
 * resolution.
 */
internal class HeldConflict(private val git: RepoGit, private val markers: Markers) {
    /** Records a commit of [tree] on [parent] whose [paths] hold markers, and returns it. */
    fun record(tree: String, parent: String, paths: List<String>): String {
        require(paths.all(::canRecord))
        val commit = git.check("commit-tree", tree, "-p", parent, "-m", "$SUBJECT\n\n${paths.joinToString("\n")}").stdout.trim()
        git.check("update-ref", REF, commit)
        return commit
    }

    /**
     * The marked files that still have markers while the branch is held; null when it is not, or no longer: then the
     * held commit has left the branch, with the folder and index as they were. Without [finish], a branch with no markers
     * left stays held, and every marked file counts: only a sync the user starts takes an edit as the resolution, since
     * one that came on its own could meet a file the user is halfway through resolving.
     */
    fun left(finish: Boolean = true): List<String>? {
        val held = git.resolve(REF) ?: return null
        if (held != git.head()) {
            // The update to it was undone
            git.check("update-ref", "-d", REF, held)
            return null
        }

        // Headers, a blank line, then the message: the subject, a blank line and one marked path per line
        val (headers, message) = git.check("cat-file", "commit", held).stdout.split("\n\n", limit = 2)
        val marked = message.substringAfter("\n\n").split("\n").filter(String::isNotEmpty)
        val left = marked.filter { hasMarkers(git.repo.workTree.resolve(it)) }
        if (left.isNotEmpty()) return left
        if (!finish) return marked

        val parent = headers.lines().first { it.startsWith("parent ") }.removePrefix("parent ")
        git.check("update-ref", "--stdin", input = "update HEAD $parent $held\ndelete $REF $held\n")
        return null
    }

    /** The remote's version of [path] in the held conflict, the merge's other side, or null when it has none. */
    fun remote(path: String): String? = git.resolve("$REF^:$path")

    private fun hasMarkers(file: File) = file.isFile && file.useLines { markers.count(it) > 0 }

    companion object {
        const val REF = "refs/safegit/conflict"
        private const val SUBJECT = "Conflict markers, never pushed"

        /** The record keeps one path per line. */
        fun canRecord(path: String) = '\n' !in path && '\r' !in path

        /** Whether the branch, at [head], is held at a conflict, whose commit holds edits this device never pushed. */
        fun isHeld(git: RepoGit, head: String?) = head != null && git.resolve(REF) == head
    }
}

/**
 * The conflict markers a device writes, which name it and the remote. Markers named otherwise are a note's own text,
 * such as one about git, and "=======" alone is no sign, since Markdown underlines a heading with it.
 */
internal class Markers(device: String) {
    val local = "<<<<<<< $device"
    val remote = ">>>>>>> remote"

    /** Matches either marker line, as an extended regex for `git diff -G`. */
    val pattern = "^(${escape(local)}|${escape(remote)})[[:space:]]*$"

    fun count(lines: Sequence<String>) = lines.count(::isMarker)

    fun isMarker(line: String) = line.trimEnd().let { it == local || it == remote }

    private fun escape(text: String) = text.replace(Regex("""[\\.\[\]{}()*+?^$|]"""), """\\$0""")
}
