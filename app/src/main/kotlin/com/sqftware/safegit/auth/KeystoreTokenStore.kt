package com.sqftware.safegit.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.sqftware.safegit.storage.writeAtomically
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the token in a file encrypted with a key that never leaves the Android Keystore, so the file alone, as a backup
 * or a rooted copy would hold, cannot be read. Blocks on file I/O.
 */
class KeystoreTokenStore(private val file: File) : TokenStore {
    @Synchronized
    override fun load(): Session? {
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(json.getString("iv"))))
            val token = String(cipher.doFinal(decode(json.getString("token"))))
            Session(Account(json.getString("login"), json.getLong("id"), json.optString("name").takeIf { !json.isNull("name") }), token)
        } catch (_: GeneralSecurityException) {
            // The key is gone, as after the app's data was partly cleared: the user signs in again
            null
        } catch (_: JSONException) {
            null
        }
    }

    @Synchronized
    override fun save(session: Session) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val json = JSONObject()
            .put("login", session.account.login)
            .put("id", session.account.id)
            .put("name", session.account.name ?: JSONObject.NULL)
            .put("iv", encode(cipher.iv))
            .put("token", encode(cipher.doFinal(session.token.toByteArray())))
        file.writeAtomically(json.toString())
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "github-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
