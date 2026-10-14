package com.sqftware.safegit.auth

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
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
    private val flow = GitHubDeviceFlow("client1", web = url, api = url)

    @After
    fun stop() = server.stop(0)

    @Test
    fun signsInOnceTheUserHasEnteredTheCode() {
        polls += """{"error":"authorization_pending"}"""
        polls += """{"error":"slow_down","interval":10}"""
        polls += """{"access_token":"tok","token_type":"bearer","scope":"repo"}"""

        val code = flow.start()
        assertEquals(DeviceCode("WDJB-MJHT", "https://github.com/login/device", "dev123", 5), code)
        assertEquals(Poll.Wait(5), flow.poll(code))
        assertEquals(Poll.Wait(10), flow.poll(code))
        assertEquals(Poll.Granted("tok"), flow.poll(code))
        assertEquals(Account("octo", 42, null), flow.account("tok"))

        assertEquals(mapOf("client_id" to "client1", "scope" to "repo"), forms.first())
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", forms.last()["grant_type"])
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
