package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * A file holding this device's conflict markers, cut into the text both sides agree on and the [blocks] where they
 * differ. [stray] says markers are left that open no block it could tell apart, which only an edit elsewhere can
 * clear. [opened] is its bytes when read, the only ones a save writes over.
 */
class MarkedFile(val path: String, val parts: List<Part>, val stray: Boolean, internal val opened: ByteArray) {
    sealed interface Part {
        data class Same(val text: String) : Part

        /** Lines both sides changed, as this phone and the remote have them, each ending in "\n" whatever the file's [eol]. */
        class Block(val local: String, val remote: String, internal val marked: String, internal val eol: String) : Part {
            val both = local + remote
        }
    }

    val blocks = parts.filterIsInstance<Part.Block>()

    /**
     * The file with each block in [resolved], by its index in [blocks], replaced by that text, in the file's line
     * endings; the rest keep their markers.
     */
    fun text(resolved: Map<Int, String>): String {
        var index = 0
        return parts.joinToString("") { part ->
            when (part) {
                is Part.Same -> part.text
                is Part.Block -> resolved[index++]?.let { lines(it, part) } ?: part.marked
            }
        }
    }

    /** Whole lines, so the text after the block keeps its own, except at the end of a file that ended without one. */
    private fun lines(text: String, block: Part.Block): String {
        val normal = text.replace("\r\n", "\n")
        if (normal.isEmpty()) return ""
        val ended = if (normal.endsWith('\n') || !block.marked.endsWith('\n')) normal else "$normal\n"
        return ended.replace("\n", block.eol)
    }
}

/**
 * Opens the files a conflict marked in [repo]'s folder, and saves them as the user resolves them, the way
 * [FolderUpdate] writes: the new text goes to a file beside them, then over the file by an atomic rename, and only
 * while it still holds what was opened. Callers keep syncs of the repo out while either runs.
 */
class MarkedFiles(git: Git, repo: Repo, device: String) {
    sealed interface Saved {
        data object Done : Saved

        /** The file no longer holds what was opened, e.g. a notes app saved it, so it was left as it is. */
        data object Changed : Saved

        data class Failed(val detail: String) : Saved
    }

    private val git = RepoGit(git, repo, NOBODY)
    private val markers = Markers(device)
    private val workTree = repo.workTree

    /** The file at [path] as it is now, or null when it is no text file of a size to edit here. */
    fun open(path: String): MarkedFile? {
        val bytes = read(path) ?: return null
        if (bytes.size > MAX_SIZE) return null
        val text = bytes.decodeToString()
        // Bytes that are not UTF-8 would come back changed, even where no block is
        if (!text.encodeToByteArray().contentEquals(bytes)) return null
        val parts = parse(text) { remote(path) }
        val stray = parts.any { it is MarkedFile.Part.Same && markers.count(it.text.lineSequence()) > 0 }
        return MarkedFile(path, parts, stray, bytes)
    }

    fun save(file: MarkedFile, text: String): Saved {
        val target = workTree.resolve(file.path).toPath()
        val temp = FolderUpdate.temp(workTree).toPath()
        try {
            Files.write(temp, text.encodeToByteArray())
            if (!read(file.path).contentEquals(file.opened)) return Saved.Changed
            Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
            return Saved.Done
        } catch (e: IOException) {
            return Saved.Failed(e.message.orEmpty())
        } finally {
            // One left behind is removed by the next sync before it commits
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
            }
        }
    }

    /** The bytes of the regular file named exactly [path], since case-insensitive storage opens a twin under another case. */
    private fun read(path: String): ByteArray? {
        val file = workTree.resolve(path)
        if (file.name !in file.parentFile.list().orEmpty()) return null
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return null
        return try {
            Files.readAllBytes(file.toPath())
        } catch (_: IOException) {
            null
        }
    }

    private fun remote(path: String): String? {
        val blob = HeldConflict(git, markers).remote(path) ?: return null
        return git.check("cat-file", "blob", blob).stdout
    }

    /** Where a block's opening marker, closing marker and each possible separator line are. */
    private class Span(val open: Int, val close: Int, val separators: List<Int>)

    /**
     * Cuts [text] at this device's markers. A block's sides part at a line of "=======", which a note can hold too, under
     * a Markdown heading. Where a block has several, the one is taken that gives the remote's side as [remote] has it,
     * with the text around the block; a block no single one fits stays text, as do markers that open no whole block,
     * such as one left from a resolution begun in another app.
     */
    private fun parse(text: String, remote: () -> String?): List<MarkedFile.Part> {
        val lines = Regex("(?<=\n)").split(text).filter(String::isNotEmpty)
        fun joined(from: Int, to: Int) = lines.subList(from, to).joinToString("")

        val spans = mutableListOf<Span>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trimEnd() == markers.local) {
                // An opener before the closer means this one is left over, and the later one opens the block
                val next = (i + 1 until lines.size).firstOrNull { markers.isMarker(lines[it]) }
                if (next != null && lines[next].trimEnd() == markers.remote) {
                    val separators = (i + 1 until next).filter { lines[it].trimEnd() == SEPARATOR }
                    if (separators.isNotEmpty()) {
                        spans += Span(i, next, separators)
                        i = next + 1
                        continue
                    }
                }
            }
            i++
        }

        // Read only for a block with several separators, which is rare; merge-file ends the remote's side with a newline
        // even where the remote's file has none
        val remotes by lazy { remote()?.let { listOf(it, "$it\n", "$it\r\n") }.orEmpty() }
        val parts = mutableListOf<MarkedFile.Part>()
        val same = StringBuilder()
        var at = 0
        for ((index, span) in spans.withIndex()) {
            val before = joined(at, span.open)
            same.append(before)
            val separator = span.separators.singleOrNull() ?: run {
                val after = joined(span.close + 1, spans.getOrNull(index + 1)?.open ?: lines.size)
                span.separators.singleOrNull { s -> remotes.any { before + joined(s + 1, span.close) + after in it } }
            }
            if (separator == null) {
                same.append(joined(span.open, span.close + 1))
            } else {
                if (same.isNotEmpty()) parts += MarkedFile.Part.Same(same.toString()).also { same.clear() }
                parts += MarkedFile.Part.Block(
                    local = joined(span.open + 1, separator).replace("\r\n", "\n"),
                    remote = joined(separator + 1, span.close).replace("\r\n", "\n"),
                    marked = joined(span.open, span.close + 1),
                    eol = if (lines[span.open].endsWith("\r\n")) "\r\n" else "\n",
                )
            }
            at = span.close + 1
        }
        same.append(joined(at, lines.size))
        if (same.isNotEmpty()) parts += MarkedFile.Part.Same(same.toString())
        return parts
    }

    private companion object {
        const val SEPARATOR = "======="
        const val MAX_SIZE = 1 shl 20
    }
}
