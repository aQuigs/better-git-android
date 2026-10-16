package com.sqftware.safegit.git

import android.content.Context
import android.system.Os
import java.io.File
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Base64

/**
 * Sets up the Termux git build that ships as native libraries (see scripts/fetch-git.sh) and returns a [Git] that runs it.
 * Call it once per process, off the main thread: it rewrites the symlinks and the CA bundle.
 *
 * Android only lets an app execute files from its nativeLibraryDir, where every file must be named lib*.so. So the names
 * git looks for, its helpers in GIT_EXEC_PATH and libraries with versioned sonames, are symlinks into that directory.
 * They are rebuilt on every call because nativeLibraryDir moves when the app updates.
 */
class BundledGit(private val context: Context) {
    fun install(): Git {
        val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
        val root = File(context.noBackupFilesDir, "git")

        val libs = root.resolve("lib").recreated()
        val execPath = root.resolve("exec").recreated()
        context.assets.open("git/links").bufferedReader().useLines { lines ->
            for (line in lines) {
                val (link, packaged) = line.split(" ")
                Os.symlink(nativeLibs.resolve(packaged).path, root.resolve(link).path)
            }
        }

        val home = root.resolve("home").apply { mkdirs() }
        val templates = root.resolve("templates").apply { mkdirs() }
        val caBundle = root.resolve("ca-bundle.pem").apply { writeText(trustedCertificatesPem()) }
        val opensslConfig = root.resolve("openssl.cnf").apply { writeText("") }

        // Termux's build defaults every path to its own prefix, /data/data/com.termux/files/usr, so each one is overridden
        return CliGit(
            execPath.resolve("git").path,
            mapOf(
                "LD_LIBRARY_PATH" to "${libs.path}:${nativeLibs.path}",
                "GIT_EXEC_PATH" to execPath.path,
                "PATH" to "${execPath.path}:/system/bin",
                "HOME" to home.path,
                "TMPDIR" to context.cacheDir.path,
                "GIT_TEMPLATE_DIR" to templates.path,
                "GIT_SSL_CAINFO" to caBundle.path,
                "OPENSSL_CONF" to opensslConfig.path,
            ),
        )
    }

    private fun File.recreated() = apply {
        deleteRecursively()
        mkdirs()
    }

    // curl needs a bundle file; the system's own CA directory uses hashes OpenSSL 3 does not read. AndroidCAStore also
    // holds the CAs the user installed, so a self-hosted server behind a private CA works too.
    private fun trustedCertificatesPem(): String {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        return store.aliases().toList().mapNotNull(store::getCertificate).joinToString("") { it.toPem(encoder) }
    }

    private fun Certificate.toPem(encoder: Base64.Encoder) =
        "-----BEGIN CERTIFICATE-----\n${encoder.encodeToString(encoded)}\n-----END CERTIFICATE-----\n"
}
