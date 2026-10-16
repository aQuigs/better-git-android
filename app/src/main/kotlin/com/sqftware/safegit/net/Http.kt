package com.sqftware.safegit.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Sends [body] as a form POST, or GETs when it is null, and returns the answer's text. Blocks, and throws [IOException] for a failed request or a non-2xx answer. */
fun fetchJson(url: String, body: String? = null, vararg headers: Pair<String, String>): String {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty("Accept", "application/json")
        for ((key, value) in headers) connection.setRequestProperty(key, value)
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        if (connection.responseCode !in 200..299) throw IOException("$url answered ${connection.responseCode}")
        return connection.inputStream.bufferedReader().readText()
    } finally {
        connection.disconnect()
    }
}

private const val TIMEOUT = 15_000
