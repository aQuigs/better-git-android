package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git

/** The files a sync would push, by path. */
data class Pending(val changed: List<String>, val added: List<String>, val deleted: List<String>) {
    fun isEmpty() = changed.isEmpty() && added.isEmpty() && deleted.isEmpty()
}

/**
 * What the next sync would push: the folder against where the branch last met the remote as fetched, so commits a sync
 * made but could not push count too. Files inside submodules are not looked at yet.
 */
class PendingChanges(private val git: Git) {
    /** Null before the branch's first commit: the first sync takes whatever the remote already has as it is. */
    fun of(repo: Repo): Pending? {
        val git = RepoGit(git, repo, NOBODY)
        if (git.head() == null) return null
        val upstream = git.branch()?.let(git::upstream)?.takeIf { git.resolve("$it^{commit}") != null }
        val base = upstream?.let { git.output("merge-base", "HEAD", it) } ?: git.emptyTree

        val (tracked, untracked) = onScratchIndex(git) { environment ->
            // Entries alternate a status letter and a path
            val tracked = git.check("diff", "--name-status", "-z", "--no-renames", "--ignore-submodules=all", base, "--", environment = environment)
                .stdout.paths().chunked(2).associate { (status, path) -> path to status }
            // Folders holding a repo of their own come as "dir/", and are no file of this one
            val untracked = git.check("ls-files", "-z", "--others", "--exclude-standard", environment = environment).stdout.paths()
                .filterNot { it.endsWith("/") }
            tracked to untracked
        }

        // A file the index no longer has but the folder does reads as deleted and untracked both
        val statuses = tracked + untracked.associateWith { if (tracked[it] == DELETED) MODIFIED else ADDED }
        fun with(vararg status: String) = statuses.filterValues { it in status }.keys.sorted()
        return Pending(changed = with(MODIFIED, TYPE_CHANGED), added = with(ADDED), deleted = with(DELETED))
    }

    /** Runs [read] on a copy of the index: diff refreshes the index it reads, optional locks or not, and that write is a sync's alone. */
    private fun <T> onScratchIndex(git: RepoGit, read: (Map<String, String>) -> T): T {
        val scratch = git.repo.gitDir.resolve("safegit-pending.index")
        try {
            git.repo.gitDir.resolve("index").takeIf { it.exists() }?.copyTo(scratch, overwrite = true)
            return read(mapOf("GIT_INDEX_FILE" to scratch.path))
        } finally {
            scratch.delete()
        }
    }

    private companion object {
        val NOBODY = Identity("", "")
        const val ADDED = "A"
        const val MODIFIED = "M"
        const val DELETED = "D"
        const val TYPE_CHANGED = "T"
    }
}
