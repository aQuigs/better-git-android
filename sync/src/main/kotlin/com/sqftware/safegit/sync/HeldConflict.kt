package com.sqftware.safegit.sync

import java.io.File

/**
 * A conflict the branch is held at: a commit whose files hold conflict markers, on top of the remote's commit it was
 * merged with. It is recorded before the folder moves to it, so a sync killed after the branch moved still holds, and it
 * is never pushed. Once no marked file has markers left, it leaves the branch and its files are committed as the
 * resolution.
 */
internal class HeldConflict(private val git: RepoGit) {
    /** Records a commit of [tree] on [parent] whose [paths] hold markers, and returns it. */
    fun record(tree: String, parent: String, paths: List<String>): String {
        require(paths.all(::canRecord))
        val commit = git.check("commit-tree", tree, "-p", parent, "-m", "$SUBJECT\n\n${paths.joinToString("\n")}").stdout.trim()
        git.check("update-ref", REF, commit)
        return commit
    }

    /**
     * The marked files that still have markers while the branch is held; null when it is not, or no longer: then the
     * held commit has left the branch, with the folder and index as they were.
     */
    fun left(): List<String>? {
        val held = git.resolve(REF) ?: return null
        if (held != git.head()) {
            // The update to it was undone
            git.check("update-ref", "-d", REF, held)
            return null
        }

        // Headers, a blank line, then the message: the subject, a blank line and one marked path per line
        val (headers, message) = git.check("cat-file", "commit", held).stdout.split("\n\n", limit = 2)
        val left = message.substringAfter("\n\n").lines().filter { it.isNotEmpty() && hasMarkers(git.repo.workTree.resolve(it)) }
        if (left.isNotEmpty()) return left

        val parent = headers.lines().first { it.startsWith("parent ") }.removePrefix("parent ")
        git.check("update-ref", "--stdin", input = "update HEAD $parent $held\ndelete $REF $held\n")
        return null
    }

    // "=======" alone is no sign, since Markdown underlines a heading with it
    private fun hasMarkers(file: File) =
        file.isFile && file.useLines { lines -> lines.any { it.startsWith("<<<<<<<") || it.startsWith(">>>>>>>") } }

    companion object {
        const val REF = "refs/safegit/conflict"
        private const val SUBJECT = "Conflict markers, never pushed"

        /** The record keeps one path per line. */
        fun canRecord(path: String) = '\n' !in path
    }
}
