package com.sqftware.safegit.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.git.TestDevice
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledSafeSyncTest : SafeSyncContract(TestDevice.context.cacheDir) {
    override val git get() = TestDevice.git
}
