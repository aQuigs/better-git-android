package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import java.io.File

/**
 * When a repo's folder was last edited, by the files that differ from its last commit, untracked ones included. A sync
 * nobody asked for waits while that is recent: a notes app saving a file just as the folder update renames it would
 * lose the save. Files inside submodules are not looked at yet.
 */
class RecentEdits(private val git: Git) {
    /** The newest such file's modification time in milliseconds since the epoch, or null when there is none. */
    fun latest(repo: Repo): Long? {
        // Without optional locks, status leaves the index alone: refreshing it would be a write outside a sync
        // Untracked folders come whole, as "dir/", since listing their files would bypass the untracked cache
        val changed = RepoGit(git, repo, NOBODY).check(
            "--no-optional-locks", "status", "--porcelain", "-z", "--no-renames", "--ignore-submodules=all",
        ).stdout.paths()
        // Each entry is two status letters and a space before the path; a deleted file has no time to give
        return changed.flatMap { entry ->
            val file = repo.workTree.resolve(entry.substring(3))
            if (entry.endsWith("/")) file.walk().filter(File::isFile).toList() else listOf(file)
        }.map(File::lastModified).filter { it > 0 }.maxOrNull()
    }
}
