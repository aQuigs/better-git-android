package com.sqftware.safegit.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.git.TestDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The contract with the phone's folder on shared storage, which is case-insensitive and behind FUSE, like a real vault. */
@RunWith(AndroidJUnit4::class)
class SharedStorageSafeSyncTest : SafeSyncContract(TestDevice.context.cacheDir, TestDevice.context.getExternalFilesDir(null)) {
    override val git get() = TestDevice.git

    @Test
    fun runsOnCaseInsensitiveStorage() {
        val probe = workTrees.newFile("CaseProbe")
        assertTrue(workTrees.root.resolve("caseprobe").exists())
        probe.delete()
    }
}
