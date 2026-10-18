package com.sqftware.safegit.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
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
    if (tree.authority != STORAGE_DOCUMENTS) return null
    return pathOf(DocumentsContract.getTreeDocumentId(tree)).takeIf { it.isDirectory }
}

/**
 * The path within [folder] of a file the system file picker returned, named as [folderOf] names a folder; null for a
 * file elsewhere, or one picked from another source, such as Recent or Downloads, which comes without its path.
 */
fun pathIn(folder: File, document: Uri): String? {
    if (document.authority != STORAGE_DOCUMENTS) return null
    val file = pathOf(DocumentsContract.getDocumentId(document)).takeIf { it.isFile } ?: return null
    val path = file.relativeToOrNull(folder)?.invariantSeparatorsPath ?: return null
    return path.takeUnless { it == ".." || it.startsWith("../") }
}

/** The system file picker, opening at [folder] where it is on the phone's own storage. */
class OpenDocumentIn(private val folder: File?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        val relative = folder?.relativeToOrNull(Environment.getExternalStorageDirectory()) ?: return intent
        val document = DocumentsContract.buildDocumentUri(STORAGE_DOCUMENTS, "primary:${relative.invariantSeparatorsPath}")
        return intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, document)
    }
}

private fun pathOf(documentId: String): File {
    val (volume, path) = documentId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
    return root.resolve(path)
}

private const val STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
