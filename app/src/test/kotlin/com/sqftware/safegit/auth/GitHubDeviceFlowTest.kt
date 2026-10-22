package com.sqftware.safegit.auth

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Against a stand-in for GitHub that answers as its device flow documentation shows. */
class GitHubDeviceFlowTest {
    private val polls = ArrayDeque<String>()
    private val forms = mutableListOf<Map<String, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        context("/login/device/code") {
            """{"device_code":"dev123","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
        }
        context("/login/oauth/access_token") { polls.removeFirst() }
        createContext("/user") { exchange ->
            val body = if (exchange.requestHeaders.getFirst("Authorization") == "Bearer tok") """{"login":"octo","id":42,"name":null}""" else "{}"
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val flow = GitHubDeviceFlow("client1", web = url, api = url, clock = { 1_000 })

    @After
    fun stop() = server.stop(0)

    @Test
    fun signsInOnceTheUserHasEnteredTheCode() {
        polls += """{"error":"authorization_pending"}"""
        polls += """{"error":"slow_down","interval":10}"""
        polls += """{"access_token":"tok","token_type":"bearer","scope":"repo"}"""

        val code = flow.start()
        assertEquals(DeviceCode("WDJB-MJHT", "https://github.com/login/device", "dev123", 5, 900), code)
        assertEquals(Poll.Pending, flow.poll(code))
        assertEquals(Poll.SlowDown(10), flow.poll(code))
        assertEquals("tok", (flow.poll(code) as Poll.Granted).grant.token)
        assertEquals(Account("octo", 42, null), flow.account("tok"))

        assertEquals(mapOf("client_id" to "client1", "scope" to "repo"), forms.first())
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", forms.last()["grant_type"])
    }

    @Test
    fun keepsWhatRenewsATokenThatExpires() {
        polls += """{"access_token":"tok","expires_in":28800,"refresh_token":"r1","refresh_token_expires_in":15897600,"token_type":"bearer","scope":"repo"}"""

        val grant = (flow.poll(flow.start()) as Poll.Granted).grant

        assertEquals("tok", grant.token)
        assertEquals(Renewal("r1", 1_000 + 28_800_000), grant.renewal)
    }

    @Test
    fun renewsWithTheRefreshTokenAlone() {
        polls += """{"access_token":"tok2","expires_in":28800,"refresh_token":"r2","refresh_token_expires_in":15897600,"token_type":"bearer","scope":"repo"}"""

        val grant = flow.renew("r1")

        assertEquals("tok2", grant?.token)
        assertEquals(Renewal("r2", 1_000 + 28_800_000), grant?.renewal)
        assertEquals(mapOf("client_id" to "client1", "grant_type" to "refresh_token", "refresh_token" to "r1"), forms.single())
    }

    @Test
    fun aRefusedRefreshTokenEndsTheSignIn() {
        polls += """{"error":"bad_refresh_token","error_description":"The refresh token passed is incorrect or expired."}"""

        assertNull(flow.renew("r1"))
    }

    @Test(expected = RenewalRefused::class)
    fun anyOtherRenewalErrorKeepsTheRefreshToken() {
        polls += """{"error":"unsupported_grant_type"}"""

        flow.renew("r1")
    }

    @Test(expected = IOException::class)
    fun failsOnAnAnswerItCannotRead() {
        flow.account("someone else's")
    }

    @Test
    fun endsWhenTheUserDeclines() {
        polls += """{"error":"access_denied","error_description":"The authorization request was denied."}"""

        assertEquals(Poll.Ended("The authorization request was denied."), flow.poll(flow.start()))
    }

    private fun HttpServer.context(path: String, answer: () -> String) = createContext(path) { exchange ->
        val form = exchange.requestBody.bufferedReader().readText().split("&").filter { it.isNotEmpty() }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        synchronized(forms) { forms += form }
        val body = answer().toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
