package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit

class RepoSetupTest : RepoSetupContract() {
    override val git = CliGit("git")
}
