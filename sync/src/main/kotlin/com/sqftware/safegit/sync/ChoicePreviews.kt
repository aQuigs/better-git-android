package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git

/** What one side of a [Choice] holds, enough to tell it from the other before keeping one. */
sealed interface Version {
    data object Deleted : Version

    data class Folder(val files: Int) : Version

    /** A text file's first [lines], each cut short if long, of [total]. */
    data class Text(val lines: List<String>, val total: Int) : Version

    /**
     * A file named as an image, of [size] bytes. [read] gets its bytes only once a thumbnail is shown, so a choice of
     * many photos never holds them all.
     */
    class Image(val size: Long, val read: () -> ByteArray) : Version

    data class Binary(val size: Long) : Version

    /** An object this repo does not hold: a submodule's commit, or a file inside one, which its own repo keeps. */
    data class Elsewhere(val id: String) : Version
}

/** A line of the two sides' difference: in both, or only on [side]'s. A null line marks lines left out. */
data class DiffLine(val side: Keep?, val text: String?)

/** Both sides of a choice, and where both are text, how they differ. */
data class ChoicePreview(val local: Version, val remote: Version, val diff: List<DiffLine> = emptyList())

/** Reads what each side of a choice in [repo] holds, from objects the sync that asked it left there. */
class ChoicePreviews(git: Git, repo: Repo) {
    private val git = RepoGit(git, repo, NOBODY)

    fun of(choice: Choice) = between(choice.path, choice.local, choice.remote)

    /** What [path] holds as the objects [local] and [remote], each null for no file there. */
    fun between(path: String, local: String?, remote: String?): ChoicePreview {
        val objects = objects(listOfNotNull(local, remote))
        val localVersion = version(path, local, objects)
        val remoteVersion = version(path, remote, objects)
        val diff = if (localVersion is Version.Text && remoteVersion is Version.Text) diff(remote!!, local!!) else emptyList()
        return ChoicePreview(localVersion, remoteVersion, diff)
    }

    /** Each object's type and size by id, leaving out those this repo lacks, which git reports as "<id> missing". */
    private fun objects(ids: List<String>): Map<String, Pair<String, Long>> {
        val found = git.check("cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)", input = ids.joinToString("") { "$it\n" })
        return found.stdout.lines().map { it.split(' ') }.filter { it.size == 3 }.associate { (id, type, size) -> id to (type to size.toLong()) }
    }

    private fun version(path: String, id: String?, objects: Map<String, Pair<String, Long>>): Version {
        id ?: return Version.Deleted
        val (type, size) = objects[id] ?: return Version.Elsewhere(id)
        return when (type) {
            "tree" -> Version.Folder(git.check("ls-tree", "-r", "-z", "--name-only", id).stdout.count { it == '\u0000' })
            "blob" -> blob(path, id, size)
            else -> Version.Elsewhere(id)
        }
    }

    private fun blob(path: String, id: String, size: Long): Version {
        if (path.substringAfterLast('.').lowercase() in IMAGES && size <= MAX_IMAGE) {
            return Version.Image(size) { git.check("cat-file", "blob", id).bytes }
        }
        if (size > MAX_TEXT) return Version.Binary(size)

        val bytes = git.check("cat-file", "blob", id).bytes
        // Git's own test for a binary file
        if ((0 until minOf(bytes.size, BINARY_PROBE)).any { bytes[it] == ZERO }) return Version.Binary(size)
        val text = bytes.decodeToString()
        val total = text.count { it == '\n' } + if (text.isEmpty() || text.endsWith('\n')) 0 else 1
        return Version.Text(text.splitToSequence('\n').take(minOf(total, SHOWN_LINES)).map(::cut).toList(), total)
    }

    private fun diff(from: String, to: String): List<DiffLine> {
        val lines = git.check("diff", "--no-color", "--no-ext-diff", "-U$CONTEXT", from, to, "--").stdout.split('\n')
        // The first hunk's header opens the diff; each later one marks lines left out
        val diff = lines.dropWhile { !it.startsWith("@@") }.drop(1).mapNotNull { line ->
            when (line.firstOrNull()) {
                '@' -> DiffLine(null, null)
                '-' -> DiffLine(Keep.REMOTE, cut(line.drop(1)))
                '+' -> DiffLine(Keep.LOCAL, cut(line.drop(1)))
                ' ' -> DiffLine(null, cut(line.drop(1)))
                // "\ No newline at end of file", and the empty string after the last line
                else -> null
            }
        }
        return if (diff.size > SHOWN_DIFF) diff.take(SHOWN_DIFF) + DiffLine(null, null) else diff
    }

    // A minified file can be one line of a megabyte, too long to lay out
    private fun cut(line: String) = line.removeSuffix("\r").let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }

    private companion object {
        val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
        const val MAX_IMAGE = 20L shl 20
        const val MAX_TEXT = 1L shl 20
        const val BINARY_PROBE = 8000
        const val ZERO: Byte = 0
        const val SHOWN_LINES = 8
        const val SHOWN_DIFF = 200
        const val MAX_LINE = 300
        const val CONTEXT = 2
    }
}
