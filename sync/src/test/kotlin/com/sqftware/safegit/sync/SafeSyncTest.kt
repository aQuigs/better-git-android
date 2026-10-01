package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit

class SafeSyncTest : SafeSyncContract() {
    override val git = CliGit("git")
}
