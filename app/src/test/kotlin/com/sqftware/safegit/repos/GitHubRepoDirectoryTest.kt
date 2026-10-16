package com.sqftware.safegit.repos

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

/** Against a stand-in for GitHub's `GET /user/repos`, which pages its answer. */
class GitHubRepoDirectoryTest {
    private val queries = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/user/repos") { exchange ->
            synchronized(queries) { queries += exchange.requestURI.query }
            val page = exchange.requestURI.query.substringAfterLast("page=").toInt()
            val count = if (page == 1) 100 else 1
            val body = (1..count).joinToString(",", "[", "]") {
                val name = "me/repo${(page - 1) * 100 + it}"
                """{"full_name":"$name","clone_url":"https://github.com/$name.git","private":true}"""
            }.toByteArray()
            val authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer tok"
            exchange.sendResponseHeaders(if (authorized) 200 else 401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val directory = GitHubRepoDirectory("http://127.0.0.1:${server.address.port}")

    @After
    fun stop() = server.stop(0)

    @Test
    fun listsEveryPage() {
        val repos = directory.repos("tok")

        assertEquals(101, repos.size)
        assertEquals(HostedRepo("me/repo1", "https://github.com/me/repo1.git"), repos.first())
        assertEquals(HostedRepo("me/repo101", "https://github.com/me/repo101.git"), repos.last())
        assertEquals(listOf("sort=pushed&per_page=100&page=1", "sort=pushed&per_page=100&page=2"), queries)
    }

    @Test(expected = IOException::class)
    fun failsForATokenGitHubRefuses() {
        directory.repos("revoked")
    }
}
