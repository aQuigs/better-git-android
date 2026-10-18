package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git

/** What one side of a [Choice] holds, enough to tell it from the other before keeping one. */
sealed interface Version {
    data object Deleted : Version

    data class Folder(val files: Int) : Version

    /** A text file's first [lines], of [total]. */
    data class Text(val lines: List<String>, val total: Int) : Version

    /** A file named as an image, for a thumbnail; one that will not decode is shown by its size. */
    class Image(val bytes: ByteArray) : Version

    data class Binary(val size: Long) : Version

    /** An object this repo does not hold, such as a submodule's commit or a file inside one, kept in its own repo. */
    data class Elsewhere(val id: String) : Version
}

/** A line of the two sides' difference: in both, or only on [side]'s. A null line marks a gap between the parts shown. */
data class DiffLine(val side: Keep?, val text: String?)

/** Both sides of a choice, and where both are text, how they differ. */
data class ChoicePreview(val local: Version, val remote: Version, val diff: List<DiffLine> = emptyList())

/** Reads what each side of a choice holds, from objects the sync that asked it left in the repo. */
class ChoicePreviews(private val git: Git) {
    fun of(repo: Repo, choice: Choice): ChoicePreview {
        val git = RepoGit(git, repo, NOBODY)
        val local = version(git, choice.path, choice.local)
        val remote = version(git, choice.path, choice.remote)
        val diff = if (local is Version.Text && remote is Version.Text) diff(git, choice.remote!!, choice.local!!) else emptyList()
        return ChoicePreview(local, remote, diff)
    }

    private fun version(git: RepoGit, path: String, id: String?): Version {
        id ?: return Version.Deleted
        // Missing is no failure here: a submodule's objects live in its own git dir
        val type = git.run("cat-file", "-t", id).takeIf { it.ok }?.stdout?.trim() ?: return Version.Elsewhere(id)
        return when (type) {
            "tree" -> Version.Folder(git.check("ls-tree", "-r", "-z", "--name-only", id).stdout.paths().size)
            "blob" -> blob(git, path, id)
            else -> Version.Elsewhere(id)
        }
    }

    private fun blob(git: RepoGit, path: String, id: String): Version {
        val size = git.check("cat-file", "-s", id).stdout.trim().toLong()
        if (path.substringAfterLast('.').lowercase() in IMAGES && size <= MAX_IMAGE) return Version.Image(bytes(git, id))
        if (size > MAX_TEXT) return Version.Binary(size)

        val text = git.check("cat-file", "blob", id).stdout
        // Git's own test for a binary file
        if ('\u0000' in text.take(BINARY_PROBE)) return Version.Binary(size)
        val lines = text.lines().let { if (text.endsWith("\n")) it.dropLast(1) else it }
        return Version.Text(lines.take(SHOWN_LINES), lines.size)
    }

    /** Git's output reaches us as text, so the file's bytes are written to private storage and read from there. */
    private fun bytes(git: RepoGit, id: String): ByteArray {
        val scratch = git.repo.gitDir.resolve("safegit-preview").apply { mkdirs() }
        val file = scratch.resolve(
            git.cli.run(scratch, listOf("--git-dir=${git.repo.gitDir.path}", "--work-tree=${scratch.path}", "unpack-file", id))
                .also { if (!it.ok) throw GitFailure("unpack-file", it) }
                .stdout.trim(),
        )
        try {
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun diff(git: RepoGit, from: String, to: String): List<DiffLine> {
        val lines = git.check("diff", "--no-color", "--no-ext-diff", "-U$CONTEXT", from, to).stdout.lines()
        val diff = lines.dropWhile { !it.startsWith("@@") }.mapNotNull { line ->
            when (line.firstOrNull()) {
                '@' -> DiffLine(null, null)
                '-' -> DiffLine(Keep.REMOTE, line.drop(1))
                '+' -> DiffLine(Keep.LOCAL, line.drop(1))
                ' ' -> DiffLine(null, line.drop(1))
                // "\ No newline at end of file", and the empty string after the last line
                else -> null
            }
        }.drop(1)
        return if (diff.size > SHOWN_DIFF) diff.take(SHOWN_DIFF) + DiffLine(null, null) else diff
    }

    private companion object {
        val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
        const val MAX_IMAGE = 10L shl 20
        const val MAX_TEXT = 1L shl 20
        const val BINARY_PROBE = 8000
        const val SHOWN_LINES = 8
        const val SHOWN_DIFF = 200
        const val CONTEXT = 2
    }
}
