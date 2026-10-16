package com.sqftware.safegit.repos

import com.sqftware.safegit.net.fetchJson
import org.json.JSONArray

/** A repo an account can connect: the name its host shows, and the URL git clones. */
data class HostedRepo(val name: String, val url: String)

/** Lists the repos an account can reach on its host. */
interface RepoDirectory {
    /** Most recently pushed first. Blocks on the network and throws [java.io.IOException] when it fails. */
    fun repos(token: String): List<HostedRepo>
}

/** Every repo the GitHub account owns, collaborates on or reaches through an organization. */
class GitHubRepoDirectory(private val api: String = "https://api.github.com") : RepoDirectory {
    override fun repos(token: String): List<HostedRepo> {
        val repos = mutableListOf<HostedRepo>()
        var page = 1
        do {
            val json = JSONArray(fetchJson("$api/user/repos?sort=pushed&per_page=$PAGE&page=${page++}", body = null, "Authorization" to "Bearer $token"))
            for (i in 0 until json.length()) {
                val repo = json.getJSONObject(i)
                repos += HostedRepo(repo.getString("full_name"), repo.getString("clone_url"))
            }
        } while (json.length() == PAGE)
        return repos
    }

    private companion object {
        const val PAGE = 100
    }
}
