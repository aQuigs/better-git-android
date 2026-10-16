package com.sqftware.safegit.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.git.TestDevice
import org.junit.runner.RunWith

/** Connecting a folder on shared storage, which is case-insensitive, behind FUSE and holds no symlinks, like a vault. */
@RunWith(AndroidJUnit4::class)
class SharedStorageRepoSetupTest : RepoSetupContract(TestDevice.context.cacheDir, TestDevice.context.getExternalFilesDir(null)) {
    override val git get() = TestDevice.git
}
