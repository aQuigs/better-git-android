package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import java.io.File

/**
 * When a repo's folder was last edited, by the files that differ from its last commit, untracked ones included. A sync
 * nobody asked for waits while that is recent: a notes app saving a file just as the folder update renames it would
 * lose the save.
 */
class RecentEdits(private val git: Git) {
    /** The newest such file's modification time in milliseconds since the epoch, or null when there is none. */
    fun latest(repo: Repo): Long? {
        // Without optional locks, status never writes the index, which may belong to a sync running meanwhile
        val changed = RepoGit(git, repo, NOBODY).check(
            "--no-optional-locks", "status", "--porcelain", "-z", "--no-renames", "--untracked-files=all", "--ignore-submodules=all",
        ).stdout.paths()
        // Each entry is two status letters and a space before the path; a deleted file has no time to give
        return changed.map { repo.workTree.resolve(it.substring(3)) }.filter(File::isFile).maxOfOrNull(File::lastModified)
    }

    private companion object {
        val NOBODY = Identity("", "")
    }
}
