package com.sqftware.safegit.net

import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Sends [form] as a POST, or GETs when it is null, with [token] as a bearer token, and reads the answer with [read].
 * Blocks, and throws [IOException] for a failed request, a non-2xx answer, or JSON [read] cannot make sense of.
 */
fun <T> fetchJson(url: String, form: String? = null, token: String? = null, read: (String) -> T): T {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (form != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(form.toByteArray()) }
        }
        if (connection.responseCode !in 200..299) throw HttpStatusException(url, connection.responseCode)
        val text = connection.inputStream.bufferedReader().readText()
        return try {
            read(text)
        } catch (e: JSONException) {
            throw IOException("$url answered something unexpected", e)
        }
    } finally {
        connection.disconnect()
    }
}

/** A request the server answered with a non-2xx [status]. */
class HttpStatusException(url: String, val status: Int) : IOException("$url answered $status")

private const val TIMEOUT = 15_000
