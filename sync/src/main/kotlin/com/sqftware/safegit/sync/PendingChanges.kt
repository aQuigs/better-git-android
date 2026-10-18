package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git

/**
 * What the next sync would push: the folder against where the branch's own changes start, as SafeSync finds it from the
 * remote as last fetched, so commits a sync made but could not push count too. A submodule with work of its own shows
 * as one changed path.
 */
class PendingChanges(private val git: Git) {
    /**
     * Null when nothing can be said yet: before the branch's first commit, off a branch or its upstream, or while it
     * shares no history with the remote, which the first sync's merge sorts out file by file.
     */
    fun of(repo: Repo): FileChanges? {
        val git = RepoGit(git, repo, NOBODY)
        if (git.head() == null) return null
        val upstream = git.branch()?.let(git::upstream) ?: return null
        // A remote without commits takes everything
        val base = if (git.resolve("$upstream^{commit}") == null) git.emptyTree else git.base(upstream)?.commit ?: return null

        val (tracked, untracked) = onScratchIndex(git) { environment ->
            // Entries alternate a status letter and a path
            val tracked = git.check("diff", "--name-status", "-z", "--no-renames", "--ignore-submodules=none", base, "--", environment = environment)
                .stdout.paths().chunked(2).associate { (status, path) -> path to status }
            // Folders holding a repo of their own come as "dir/", and are no file of this one
            val untracked = git.check("ls-files", "-z", "--others", "--exclude-standard", environment = environment).stdout.paths()
                .filterNot { it.endsWith("/") }
            tracked to untracked
        }

        // A file the index no longer has but the folder does reads as deleted and untracked both
        val statuses = tracked + untracked.associateWith { if (tracked[it] == DELETED) MODIFIED else ADDED }
        fun with(vararg status: String) = statuses.filterValues { it in status }.keys.sorted()
        return FileChanges(changed = with(MODIFIED, TYPE_CHANGED), added = with(ADDED), deleted = with(DELETED))
    }

    /** Runs [read] on a copy of the index: diff refreshes the index it reads, optional locks or not, and that write is a sync's alone. */
    private fun <T> onScratchIndex(git: RepoGit, read: (Map<String, String>) -> T): T {
        val index = git.repo.gitDir.resolve("index")
        val scratch = git.repo.gitDir.resolve("safegit-pending.index")
        try {
            scratch.delete()
            if (index.exists()) {
                index.copyTo(scratch)
                // Git checks the contents of files saved in the same second the index was written, by its time
                scratch.setLastModified(index.lastModified())
            }
            return read(mapOf("GIT_INDEX_FILE" to scratch.path))
        } finally {
            scratch.delete()
        }
    }

    private companion object {
        const val ADDED = "A"
        const val MODIFIED = "M"
        const val DELETED = "D"
        const val TYPE_CHANGED = "T"
    }
}
