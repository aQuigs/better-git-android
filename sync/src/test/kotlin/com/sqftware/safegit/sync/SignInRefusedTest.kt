package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

/** A remote that refuses every sign-in, as GitHub does a revoked token. JVM only: Android has no HttpServer. */
class SignInRefusedTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"GitHub\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        start()
    }
    private val site = "http://127.0.0.1:${server.address.port}/"
    private val refusing = "${site}me/notes.git"
    private val credentials = TokenCredentials(site, "revoked")

    private lateinit var phone: Repo

    @Before
    fun setUp() {
        val remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        phone = git.splitClone(remote, gitDir = temp.root.resolve("phone.git"), workTree = temp.root.resolve("phone"))
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun aRefusedFetchAsksForASignIn() {
        RepoGit(git, phone, PHONE).check("remote", "set-url", "origin", refusing)

        assertEquals(Problem.SIGN_IN, failure())
    }

    @Test
    fun aRefusedPushAsksForASignIn() {
        RepoGit(git, phone, PHONE).check("remote", "set-url", "--push", "origin", refusing)
        phone.workTree.resolve("phone.md").writeText("from the phone\n")

        assertEquals(Problem.SIGN_IN, failure())
    }

    @Test
    fun aRefusedConnectAsksForASignIn() {
        val result = RepoSetup(git, PHONE).connect(refusing, temp.root.resolve("new.git"), temp.newFolder("new"), credentials)

        assertEquals(Problem.SIGN_IN, (result as RepoSetup.Result.Failed).problem)
    }

    private fun failure() = (SafeSync(git, PHONE, device = "Pixel").sync(phone, credentials) as SyncResult.Failed).problem
}
