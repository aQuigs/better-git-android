package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Base64

class TokenCredentialsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private val headers = mutableListOf<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            synchronized(headers) { headers += exchange.requestHeaders.getFirst("Authorization") }
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        start()
    }
    private val port get() = server.address.port

    @After
    fun stop() = server.stop(0)

    @Test
    fun sendsTheTokenToItsSite() {
        lsRemote("http://127.0.0.1:$port/me/notes.git", TokenCredentials("http://127.0.0.1:$port/", "secret"))

        val basic = Base64.getEncoder().encodeToString("x-access-token:secret".toByteArray())
        assertEquals("Basic $basic", headers.first())
    }

    @Test
    fun keepsTheTokenFromOtherSites() {
        lsRemote("http://localhost:$port/me/notes.git", TokenCredentials("http://127.0.0.1:$port/", "secret"))

        assertNull(headers.first())
    }

    private fun lsRemote(url: String, credentials: Credentials) {
        git.run(temp.root, listOf("ls-remote", url), credentials.environment())
    }
}
