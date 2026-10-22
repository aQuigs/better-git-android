package com.sqftware.safegit.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.sqftware.safegit.storage.writeAtomically
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.AEADBadTagException
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
            // Android 11's Keystore answers no key when it cannot be reached, so a missing one is not taken as gone
            val key = savedKey() ?: throw KeyStoreException("no key")
            val renewal = if (json.has("refresh")) Renewal(json.open(key, "refresh", "refreshIv"), json.getLong("expiresAt")) else null
            Session(Account(json.getString("login"), json.getLong("id"), json.optString("name").takeIf { !json.isNull("name") }), json.open(key, "token", "iv"), renewal)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: AEADBadTagException) {
            // Encrypted under another key
            null
        } catch (e: Exception) {
            // The Keystore fails for a while at times, as when the app starts in the background; taking that for
            // signed out would send the next sync without the token
            throw IOException("the saved sign-in can't be read (${e.javaClass.simpleName}: ${e.message})", e)
        }
    }

    @Synchronized
    override fun save(session: Session) {
        // A key that fails is replaced, as the token it held is being replaced too
        val key = try {
            (savedKey() ?: newKey()).also(::encrypter)
        } catch (_: Exception) {
            newKey()
        }
        val json = JSONObject()
            .put("login", session.account.login)
            .put("id", session.account.id)
            .put("name", session.account.name ?: JSONObject.NULL)
            .seal(key, "token", "iv", session.token)
        session.renewal?.let { json.seal(key, "refresh", "refreshIv", it.refreshToken).put("expiresAt", it.expiresAt) }
        file.writeAtomically(json.toString())
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private fun encrypter(key: SecretKey) = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    // Each secret gets a cipher of its own, as GCM must never reuse an IV under one key
    private fun JSONObject.seal(key: SecretKey, name: String, iv: String, secret: String): JSONObject {
        val cipher = encrypter(key)
        return put(iv, encode(cipher.iv)).put(name, encode(cipher.doFinal(secret.toByteArray())))
    }

    private fun JSONObject.open(key: SecretKey, name: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(getString(iv))))
        return String(cipher.doFinal(decode(getString(name))))
    }

    private fun savedKey() = KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "github-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
