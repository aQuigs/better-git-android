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
            val repos = if (page == 1) {
                (1..100).map { repo("me/repo$it") }
            } else {
                listOf(repo("me/repo101"), repo("org/read-only", push = false), repo("me/old", archived = true))
            }
            val body = repos.joinToString(",", "[", "]").toByteArray()
            val authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer tok"
            exchange.sendResponseHeaders(if (authorized) 200 else 401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private var token: String? = "tok"
    private val directory = GitHubRepoDirectory({ token }, "http://127.0.0.1:${server.address.port}")

    @After
    fun stop() = server.stop(0)

    private fun repo(name: String, push: Boolean = true, archived: Boolean = false) =
        """{"full_name":"$name","clone_url":"https://github.com/$name.git","archived":$archived,"permissions":{"admin":false,"push":$push,"pull":true}}"""

    @Test
    fun listsEveryPageOfReposItCanPushTo() {
        val repos = directory.repos()

        assertEquals(101, repos.size)
        assertEquals(HostedRepo("me/repo1", "https://github.com/me/repo1.git"), repos.first())
        assertEquals(HostedRepo("me/repo101", "https://github.com/me/repo101.git"), repos.last())
        assertEquals(listOf("sort=pushed&per_page=100&page=1", "sort=pushed&per_page=100&page=2"), queries)
    }

    @Test(expected = IOException::class)
    fun failsForATokenGitHubRefuses() {
        token = "revoked"
        directory.repos()
    }

    @Test
    fun listsNoneWhenSignedOut() {
        token = null

        assertEquals(emptyList<HostedRepo>(), directory.repos())
        assertEquals(emptyList<String>(), queries)
    }
}
