package com.sqftware.safegit.auth

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** A GitHub account the app syncs as. */
data class Account(val login: String, val id: Long, val name: String?)

/** What the user enters at [verificationUri], and what the app polls with until they have. */
data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val interval: Int)

/** How a poll for the token went. */
sealed interface Poll {
    /** Not yet; ask again in [interval] seconds. */
    data class Wait(val interval: Int) : Poll

    data class Granted(val token: String) : Poll

    /** The user declined, or the code expired. */
    data class Ended(val reason: String) : Poll
}

/** Signs in to a host that has no password field in the app: the user approves on its website instead. */
interface DeviceFlow {
    fun start(): DeviceCode

    fun poll(code: DeviceCode): Poll

    fun account(token: String): Account
}

/**
 * GitHub's OAuth device flow, which needs only the OAuth app's public client id, no secret and no backend. Every call
 * blocks on the network and throws [IOException] when it fails.
 */
class GitHubDeviceFlow(
    private val clientId: String,
    private val web: String = "https://github.com",
    private val api: String = "https://api.github.com",
) : DeviceFlow {
    override fun start(): DeviceCode {
        val json = post("$web/login/device/code", "client_id" to clientId, "scope" to "repo")
        return DeviceCode(json.getString("user_code"), json.getString("verification_uri"), json.getString("device_code"), json.optInt("interval", 5))
    }

    override fun poll(code: DeviceCode): Poll {
        val json = post(
            "$web/login/oauth/access_token",
            "client_id" to clientId,
            "device_code" to code.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        if (json.has("access_token")) return Poll.Granted(json.getString("access_token"))
        return when (val error = json.optString("error")) {
            "authorization_pending" -> Poll.Wait(code.interval)
            // GitHub asks for a longer wait each time, and says how long
            "slow_down" -> Poll.Wait(json.optInt("interval", code.interval + 5))
            else -> Poll.Ended(json.optString("error_description", error))
        }
    }

    override fun account(token: String): Account {
        val json = request("$api/user", body = null, "Authorization" to "Bearer $token")
        return Account(json.getString("login"), json.getLong("id"), json.optString("name").takeIf { !json.isNull("name") && it.isNotEmpty() })
    }

    private fun post(url: String, vararg form: Pair<String, String>) =
        request(url, form.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" })

    private fun request(url: String, body: String?, vararg headers: Pair<String, String>): JSONObject {
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
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT = 15_000
    }
}
