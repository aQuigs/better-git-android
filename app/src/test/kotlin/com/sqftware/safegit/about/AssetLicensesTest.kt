package com.sqftware.safegit.about

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetLicensesTest {
    @Test
    fun pairsEachPackageWithItsLicense() {
        val assets = mapOf(
            "git/packages" to "git 2.56.0\nopenssl 1:3.6.5\nlibssh2 1.11.1-2\n",
            "git/licenses/git" to "GPL-2.0",
            "git/licenses/openssl" to "Apache-2.0",
            "git/licenses/libssh2" to "BSD-3-Clause",
            "licenses/Apache-2.0.txt" to "Apache License",
        )

        val licenses = AssetLicenses(assets::getValue).load()

        assertEquals(
            listOf(
                BundledPackage("git", "2.56.0", "GPL-2.0"),
                BundledPackage("openssl", "1:3.6.5", "Apache-2.0"),
                BundledPackage("libssh2", "1.11.1-2", "BSD-3-Clause"),
            ),
            licenses.packages,
        )
        assertEquals("Apache License", licenses.apache)
    }
}
