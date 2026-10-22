package com.sqftware.safegit.auth

import com.sqftware.safegit.net.fetchJson
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** A GitHub account the app syncs as. */
data class Account(val login: String, val id: Long, val name: String?)

/** What the user enters at [verificationUri], and what the app polls with every [interval] seconds until they have. */
data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val interval: Int, val expiresIn: Int)

/** A token the host granted, with what renews it; [renewal] is null for a token that never expires. */
class Grant(val token: String, val renewal: Renewal? = null) {
    override fun toString() = "Grant"
}

/** The one-use token that renews an access token expiring at [expiresAt] (milliseconds since the epoch). */
data class Renewal(val refreshToken: String, val expiresAt: Long) {
    override fun toString() = "Renewal(expiresAt=$expiresAt)"
}

/** How a poll for the token went. */
sealed interface Poll {
    /** Not yet. */
    data object Pending : Poll

    /** Not yet, and polling too often: wait [interval] seconds from now on. */
    data class SlowDown(val interval: Int) : Poll

    class Granted(val grant: Grant) : Poll {
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

    /**
     * A new token for [refreshToken], which then stops working; null when the host refuses that refresh token. Throws
     * [RenewalRefused] when the host answers with any other error.
     */
    fun renew(refreshToken: String): Grant?
}

/** The host answered a renewal with an error that leaves the refresh token as it was. */
class RenewalRefused(reason: String) : IOException("GitHub didn't renew the sign-in: $reason")

/**
 * GitHub's OAuth device flow, which needs only the OAuth app's public client id, no secret and no backend. Every call
 * blocks on the network and throws [IOException] when it fails or gets an answer it cannot read.
 *
 * GitHub gives an OAuth app made since August 2026 tokens that expire after 8 hours, each with a refresh token, so a
 * sign-in lasts only as long as it is renewed.
 */
class GitHubDeviceFlow(
    private val clientId: String,
    private val web: String = "https://github.com",
    private val api: String = "https://api.github.com",
    private val clock: () -> Long = System::currentTimeMillis,
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
        if (json.has("access_token")) return@post Poll.Granted(grant(json))
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

    // A token from the device flow renews without the client secret
    override fun renew(refreshToken: String): Grant? = post(
        "$web/login/oauth/access_token",
        "client_id" to clientId,
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
    ) { json ->
        when {
            json.has("access_token") -> grant(json)
            json.optString("error") == "bad_refresh_token" -> null
            // Only a refusal of this refresh token ends the sign-in, since it is the one way back short of signing in
            else -> throw RenewalRefused(json.optString("error_description", json.optString("error")))
        }
    }

    private fun grant(json: JSONObject): Grant {
        val renewal = json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
            Renewal(it, clock() + json.getLong("expires_in") * 1000)
        }
        return Grant(json.getString("access_token"), renewal)
    }

    private fun <T> post(url: String, vararg form: Pair<String, String>, read: (JSONObject) -> T) =
        fetchJson(url, form.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }) { read(JSONObject(it)) }
}
