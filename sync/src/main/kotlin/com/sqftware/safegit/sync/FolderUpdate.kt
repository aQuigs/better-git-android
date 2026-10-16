package com.sqftware.safegit.sync

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

/**
 * Moves the folder, its index and its branch from one commit's files to another's, writing only over files that still
 * hold what git saved. Used instead of `git checkout`, which reports a file it could not write only as an error line
 * after leaving it half written, and then cannot tell that debris from an edit.
 *
 * Git writes both versions of every changed file to private staging first. Each file then reaches the folder by an
 * atomic rename, after a last check that it still holds the old version, so it is always wholly old or wholly new. If
 * a write fails the moved files go back. refs/safegit/move-from and move-to record a move until the branch has
 * followed, so after a kill the next sync finishes it or puts it back.
 *
 * Shared storage is case-insensitive, so a file only counts as some version when its own name matches exactly. A name
 * that opens a file under another case is taken, unless the two names are one file being renamed by case.
 */
internal class FolderUpdate(private val git: RepoGit) {
    sealed interface Result {
        data object Done : Result

        /** These files hold neither version, e.g. edited since the last commit, so the folder was left as it was. */
        data class Blocked(val paths: List<String>) : Result

        data class WriteFailed(val detail: String) : Result
    }

    private val workTree = git.repo.workTree
    private val staging = git.repo.gitDir.resolve("safegit-staging")

    // In the folder itself, since a rename is only atomic within one file system, and short, since names have a limit
    private val temp = workTree.resolve(".safegit.tmp")

    /**
     * Deals with a move a killed sync left recorded: finished once the branch has moved, redone or put back while it
     * has not. Returns a failure only when the folder could not be put right.
     */
    fun resume(): Result.WriteFailed? {
        temp.delete()
        val from = git.resolve(FROM) ?: return null
        val to = git.resolve(TO)
        if (to == null || (git.head() ?: git.emptyTree) != from) {
            finish()
            return null
        }
        return Move(from, to).run(resuming = true) as? Result.WriteFailed
    }

    fun move(from: String, to: String): Result {
        git.check("update-ref", "--stdin", input = "update $FROM $from\nupdate $TO $to\n")
        return Move(from, to).run(resuming = false)
    }

    private fun finish() {
        git.check("update-ref", "--stdin", input = "delete $FROM\ndelete $TO\n")
        staging.deleteRecursively()
    }

    private enum class Side { FROM, TO }

    private class Change(val path: String, val oldMode: String, val newMode: String, val newBlob: String) {
        val old get() = isFile(oldMode)
        val new get() = isFile(newMode)

        // A submodule's files belong to its own repo, so on this side the path holds no file
        private fun isFile(mode: String) = mode != ABSENT && mode != GITLINK
    }

    private inner class Move(private val from: String, private val to: String) {
        // Raw entries are ":<old mode> <new mode> <old blob> <new blob> <status>" then the path
        private val entries = git.check("diff-tree", "-r", "-z", "--no-renames", from, to).stdout.paths().chunked(2)
            .map { (entry, path) -> entry.removePrefix(":").split(" ").let { Change(path, it[0], it[1], it[3]) } }
        private val changes = entries.filter { it.old || it.new }.associateBy { it.path }
        private val byCase = changes.keys.groupBy { it.lowercase() }
        private val listings = HashMap<File, MutableSet<String>>()

        fun run(resuming: Boolean): Result {
            if (git.output("config", "--bool", "core.ignorecase") == "true") {
                val twins = changes.values.filter { it.new }.groupBy { it.path.lowercase() }.values.filter { it.size > 1 }
                if (twins.isNotEmpty()) {
                    return undo(emptyList()) ?: Result.Blocked(twins.flatten().filter { !it.old }.ifEmpty { twins.flatten() }.map { it.path })
                }
            }

            stage(from, Side.FROM)
            stage(to, Side.TO)

            // One look at every file before anything is written; place() looks at each again right before its write
            val states = changes.keys.associateWith { state(it) }
            // Files a killed run already moved go back too if this one fails
            val moved = if (resuming) states.filterValues { it == Side.TO }.keys.toMutableList() else mutableListOf()
            val blocked = states.filterValues { it == null }.keys.toList()
            if (blocked.isNotEmpty()) {
                return undo(moved) ?: Result.Blocked(blocked)
            }

            for (path in inOrder(changes.keys, Side.TO)) {
                if (states[path] == Side.TO) continue
                moved += path
                try {
                    if (!place(path, Side.TO)) return undo(moved) ?: Result.Blocked(listOf(path))
                } catch (e: IOException) {
                    return undo(moved) ?: Result.WriteFailed("$path: ${e.message}")
                }
            }

            // Git reads a submodule without its folder as deleted, so each gets the empty one clone would make
            for (change in entries) {
                val folder = workTree.resolve(change.path)
                if (change.newMode == GITLINK) folder.mkdirs() else if (change.oldMode == GITLINK && folder.isDirectory) folder.delete()
            }

            // Only the changed entries move, so the index keeps every other file's cached stats and status stays fast
            val index = entries.joinToString("") {
                if (it.newMode == ABSENT) "0 ${"0".repeat(it.newBlob.length)}\t${it.path}\u0000" else "${it.newMode} ${it.newBlob}\t${it.path}\u0000"
            }
            git.check("update-index", "-z", "--index-info", input = index)
            git.check("update-ref", "HEAD", to)
            finish()
            return Result.Done
        }

        /** Puts [moved] files back to their old versions, leaving any that changed since; null once done. */
        private fun undo(moved: List<String>): Result? {
            for (path in inOrder(moved, Side.FROM)) {
                try {
                    place(path, Side.FROM)
                } catch (e: IOException) {
                    return Result.WriteFailed("Could not undo a partial update of $path: ${e.message}")
                }
            }
            finish()
            return null
        }

        /** Removals first, deepest first, so that a file never meets a folder or a case twin on its way in. */
        private fun inOrder(paths: Collection<String>, toward: Side): List<String> {
            val (removals, writes) = paths.partition { version(toward, it) == null }
            return removals.sortedByDescending { it.count { c -> c == '/' } } + writes.sorted()
        }

        private fun stage(tree: String, side: Side) {
            val dir = staging.resolve(side.name)
            dir.deleteRecursively()
            val paths = changes.values.filter { if (side == Side.FROM) it.old else it.new }.map { it.path }
            if (paths.isEmpty()) return
            val index = mapOf("GIT_INDEX_FILE" to staging.resolve("${side.name}.index").path)
            dir.mkdirs()
            git.check("read-tree", tree, environment = index)
            git.check(
                "checkout-index", "--stdin", "-z", "--prefix=${dir.path}/",
                environment = index,
                input = paths.joinToString("") { "$it\u0000" },
            )
        }

        private fun version(side: Side, path: String): Path? {
            val change = changes.getValue(path)
            val there = if (side == Side.FROM) change.old else change.new
            return if (there) staging.resolve(side.name).resolve(path).toPath() else null
        }

        /** Which version the folder holds at [path], or null for neither. */
        private fun state(path: String, renames: Boolean = true) = Side.entries.firstOrNull { holds(path, it, renames) }

        /**
         * Whether the folder holds [side]'s version at [path]. Where that side has no file, the path must open nothing,
         * a folder holding only files the update moves, or, if [renames] allows, the file a rename by case moves.
         */
        private fun holds(path: String, side: Side, renames: Boolean): Boolean {
            val expected = version(side, path)
            val current = workTree.resolve(path).toPath()
            val attributes = attributes(current)
            if (expected == null) {
                return when {
                    attributes == null -> true
                    // Each file in it is the update's to move, and its own check decides whether it holds that side
                    attributes.isDirectory -> current.toFile().walk().all { it.isDirectory || it.relativeTo(workTree).invariantSeparatorsPath in changes }
                    else -> renames && opensRenamedFile(path)
                }
            }
            if (attributes == null || !hasExactName(current.toFile())) return false
            if (Files.isSymbolicLink(expected)) {
                return attributes.isSymbolicLink && Files.readSymbolicLink(current) == Files.readSymbolicLink(expected)
            }
            return attributes.isRegularFile && attributes.size() == Files.size(expected) && sameBytes(current, expected)
        }

        /** Whether [path] opens the same file as the other half of a rename that changes only case. */
        private fun opensRenamedFile(path: String): Boolean {
            val change = changes.getValue(path)
            val current = workTree.resolve(path).toPath()
            return byCase.getValue(path.lowercase()).any { other ->
                val twin = changes.getValue(other)
                other != path && (change.old != twin.old) && (change.new != twin.new) && (change.old != change.new) &&
                    Files.exists(workTree.resolve(other).toPath(), NOFOLLOW_LINKS) &&
                    Files.isSameFile(current, workTree.resolve(other).toPath())
            }
        }

        private fun hasExactName(file: File) = file.name in listing(file.parentFile)

        private fun listing(folder: File) = listings.getOrPut(folder) { folder.list().orEmpty().toMutableSet() }

        /**
         * Puts [toward]'s version of [path] in the folder, or deletes it when that side has none, checking right before
         * that the folder still holds the other version. Returns false, writing nothing, when it holds neither.
         */
        private fun place(path: String, toward: Side): Boolean {
            val target = workTree.resolve(path).toPath()
            val source = version(toward, path)
            try {
                source?.let { Files.copy(it, temp.toPath(), REPLACE_EXISTING, NOFOLLOW_LINKS) }
                when (state(path, renames = false)) {
                    toward -> return true
                    null -> return false
                    else -> {}
                }

                val folder = target.parent.toFile()
                if (source == null) {
                    Files.delete(target)
                    listing(folder) -= target.fileName.toString()
                    removeEmptyFolders(folder)
                } else {
                    // Only folders should be left there by now, since the files in them went first
                    if (Files.isDirectory(target, NOFOLLOW_LINKS)) {
                        if (target.toFile().walk().any { !it.isDirectory } || !target.toFile().deleteRecursively()) return false
                    }
                    Files.createDirectories(target.parent)
                    Files.move(temp.toPath(), target, REPLACE_EXISTING, ATOMIC_MOVE)
                    listing(folder) += target.fileName.toString()
                }
                return true
            } finally {
                if (source != null) temp.delete()
            }
        }

        private fun removeEmptyFolders(start: File) {
            var folder = start
            while (folder != workTree && folder.list()?.isEmpty() == true && folder.delete()) {
                listings.remove(folder)
                listing(folder.parentFile) -= folder.name
                folder = folder.parentFile
            }
        }
    }

    private fun attributes(path: Path) = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    }

    private fun sameBytes(a: Path, b: Path): Boolean {
        Files.newInputStream(a).use { first ->
            Files.newInputStream(b).use { second ->
                val bufferA = ByteArray(CHUNK)
                val bufferB = ByteArray(CHUNK)
                while (true) {
                    val read = first.readChunk(bufferA)
                    if (read != second.readChunk(bufferB) || !bufferA.contentEquals(bufferB)) return false
                    if (read < CHUNK) return true
                }
            }
        }
    }

    /** Fills [buffer] as far as the stream allows, zeroing the rest, so equal files give equal buffers. */
    private fun InputStream.readChunk(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        buffer.fill(0, total)
        return total
    }

    private companion object {
        const val FROM = "refs/safegit/move-from"
        const val TO = "refs/safegit/move-to"
        const val ABSENT = "000000"
        const val GITLINK = "160000"
        const val CHUNK = 64 * 1024
    }
}
