package com.sqftware.safegit.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.net.toUri
import java.io.File

/** Whether the app may read and write the folders it syncs, which live in shared storage. */
interface StorageAccess {
    fun granted(): Boolean

    /** Opens the system screen where the user grants it. */
    fun request()
}

/** All-files access: git needs real paths, file locks and lstat, which the Storage Access Framework cannot give. */
class AllFilesAccess(private val context: Context) : StorageAccess {
    override fun granted() = Environment.isExternalStorageManager()

    override fun request() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * The path of a folder the system folder picker returned, which names it as "<volume>:<path>"; null for one that is not
 * a folder on local storage. With all-files access the app reads it by path, not through the picker's grant.
 */
fun folderOf(tree: Uri): File? {
    if (tree.authority != "com.android.externalstorage.documents") return null
    val (volume, path) = DocumentsContract.getTreeDocumentId(tree).split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
    return root.resolve(path).takeIf { it.isDirectory }
}
