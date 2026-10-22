package com.sqftware.safegit.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.git.TestDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {
    private val file = TestDevice.context.cacheDir.resolve("token-test.json").apply { delete() }
    private val session = Session(Account("octo", 42, null), "gho_secret")

    @Test
    fun keepsTheTokenEncrypted() {
        KeystoreTokenStore(file).save(session)

        assertEquals(session, KeystoreTokenStore(file).load())
        assertFalse("gho_secret" in file.readText())
    }

    @Test
    fun keepsTheRefreshTokenEncryptedToo() {
        val renewing = session.copy(renewal = Renewal("ghr_secret", 1_800_000_000_000))

        KeystoreTokenStore(file).save(renewing)

        assertEquals(renewing, KeystoreTokenStore(file).load())
        assertFalse("ghr_secret" in file.readText())
    }

    @Test
    fun forgetsItOnSignOut() {
        KeystoreTokenStore(file).apply {
            save(session)
            clear()
        }

        assertNull(KeystoreTokenStore(file).load())
    }
}
