package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git

/** The paths a repo knows of, for keeping them out of what the app shares, such as a bug report. */
class RepoPaths(private val git: Git) {
    /** The paths in [repo]'s index and in its remote's tree as last fetched; whichever git cannot list, such as a remote never fetched, adds none. */
    fun of(repo: Repo): Set<String> {
        val git = RepoGit(git, repo, NOBODY)
        val listings = listOf(arrayOf("ls-files", "-z"), arrayOf("ls-tree", "-r", "-z", "--name-only", "@{upstream}"))
        return listings.flatMap { args -> git.run(*args).takeIf { it.ok }?.stdout?.paths().orEmpty() }.toSet()
    }
}
