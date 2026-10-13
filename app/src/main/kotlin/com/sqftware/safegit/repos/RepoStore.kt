package com.sqftware.safegit.repos

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A folder connected to a remote, by the id that names its git dir. */
data class SavedRepo(val id: String, val url: String, val folder: File) {
    val name: String get() = folder.name
}

/** The repos this app syncs. */
interface RepoStore {
    fun all(): List<SavedRepo>

    fun save(repo: SavedRepo)
}

/** Keeps the repos as JSON in one small file, rewritten whole. */
class FileRepoStore(private val file: File) : RepoStore {
    @Synchronized
    override fun all(): List<SavedRepo> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            SavedRepo(it.getString("id"), it.getString("url"), File(it.getString("folder")))
        }
    }

    @Synchronized
    override fun save(repo: SavedRepo) {
        val repos = all().filter { it.id != repo.id } + repo
        val array = JSONArray(repos.map { JSONObject().put("id", it.id).put("url", it.url).put("folder", it.folder.path) })
        // A whole new file renamed into place, so a crash never leaves half a list
        val next = File(file.path + ".new").apply { writeText(array.toString()) }
        check(next.renameTo(file)) { "Could not save $file" }
    }
}
