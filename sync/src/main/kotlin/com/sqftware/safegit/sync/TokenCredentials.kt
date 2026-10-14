package com.sqftware.safegit.sync

import java.util.Base64

/**
 * A token sent only to URLs under [site], as the password of HTTP basic auth, which is how GitHub and most hosts take
 * an OAuth or personal token over git. It reaches git as config in the environment: argv can be read by other apps'
 * processes, and a config file would leave it on disk.
 */
class TokenCredentials(private val site: String, private val token: String) : Credentials {
    override fun environment(): Map<String, String> {
        val basic = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
        return mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.$site.extraHeader",
            "GIT_CONFIG_VALUE_0" to "Authorization: Basic $basic",
        )
    }
}
