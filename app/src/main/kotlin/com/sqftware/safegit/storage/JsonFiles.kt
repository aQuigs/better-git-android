package com.sqftware.safegit.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Writes a whole new file and renames it into place, so neither a crash nor a power cut leaves half of one. */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val next = resolveSibling("$name.new")
    FileOutputStream(next).use {
        it.write(text.toByteArray())
        it.fd.sync()
    }
    check(next.renameTo(this)) { "Could not save $this" }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
