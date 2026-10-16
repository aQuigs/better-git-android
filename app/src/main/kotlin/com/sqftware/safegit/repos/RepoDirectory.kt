package com.sqftware.safegit.repos

import com.sqftware.safegit.net.fetchJson
import org.json.JSONArray

/** A repo an account can connect: the name its host shows, and the URL git clones. */
data class HostedRepo(val name: String, val url: String)

/** Lists the repos the signed-in account can reach on its host. */
interface RepoDirectory {
    /**
     * Most recently pushed first, and none when signed out. Blocks on the network and throws [java.io.IOException] when
     * it fails, a [com.sqftware.safegit.net.HttpStatusException] of 401 when the host no longer takes the sign-in.
     */
    fun repos(): List<HostedRepo>
}

/**
 * The repos the GitHub account of [token] owns, collaborates on or reaches through an organization, leaving out those
 * it cannot push to (read-only or archived), where every sync would fail.
 */
class GitHubRepoDirectory(private val token: () -> String?, private val api: String = "https://api.github.com") : RepoDirectory {
    override fun repos(): List<HostedRepo> {
        val token = token() ?: return emptyList()
        val repos = mutableListOf<HostedRepo>()
        var page = 1
        do {
            val count = fetchJson("$api/user/repos?sort=pushed&per_page=$PAGE&page=${page++}", token = token) {
                val json = JSONArray(it)
                for (i in 0 until json.length()) {
                    val repo = json.getJSONObject(i)
                    val pushable = repo.optJSONObject("permissions")?.optBoolean("push") == true && !repo.optBoolean("archived")
                    if (pushable) repos += HostedRepo(repo.getString("full_name"), repo.getString("clone_url"))
                }
                json.length()
            }
        } while (count == PAGE)
        return repos
    }

    private companion object {
        const val PAGE = 100
    }
}
