package com.sqftware.safegit.auth

import com.sqftware.safegit.net.fetchJson
import org.json.JSONObject
import java.net.URLEncoder

/** A GitHub account the app syncs as. */
data class Account(val login: String, val id: Long, val name: String?)

/** What the user enters at [verificationUri], and what the app polls with every [interval] seconds until they have. */
data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val interval: Int, val expiresIn: Int)

/** How a poll for the token went. */
sealed interface Poll {
    /** Not yet. */
    data object Pending : Poll

    /** Not yet, and polling too often: wait [interval] seconds from now on. */
    data class SlowDown(val interval: Int) : Poll

    class Granted(val token: String) : Poll {
        override fun toString() = "Granted"
    }

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
 * blocks on the network and throws [java.io.IOException] when it fails or gets an answer it cannot read.
 */
class GitHubDeviceFlow(
    private val clientId: String,
    private val web: String = "https://github.com",
    private val api: String = "https://api.github.com",
) : DeviceFlow {
    override fun start(): DeviceCode = post("$web/login/device/code", "client_id" to clientId, "scope" to "repo") { json ->
        DeviceCode(
            json.getString("user_code"),
            json.getString("verification_uri"),
            json.getString("device_code"),
            json.optInt("interval", 5),
            json.optInt("expires_in", 900),
        )
    }

    override fun poll(code: DeviceCode): Poll = post(
        "$web/login/oauth/access_token",
        "client_id" to clientId,
        "device_code" to code.deviceCode,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
    ) { json ->
        if (json.has("access_token")) return@post Poll.Granted(json.getString("access_token"))
        when (val error = json.optString("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown(json.optInt("interval", code.interval + 5))
            else -> Poll.Ended(json.optString("error_description", error))
        }
    }

    override fun account(token: String): Account = fetchJson("$api/user", token = token) {
        val json = JSONObject(it)
        Account(json.getString("login"), json.getLong("id"), json.optString("name").takeIf { !json.isNull("name") && it.isNotEmpty() })
    }

    private fun <T> post(url: String, vararg form: Pair<String, String>, read: (JSONObject) -> T) =
        fetchJson(url, form.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }) { read(JSONObject(it)) }
}
