package com.sqftware.safegit.repos

import com.sqftware.safegit.storage.objects
import com.sqftware.safegit.storage.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A folder connected to a remote, by the id that names its git dir. */
data class SavedRepo(val id: String, val url: String, val folder: File, val auto: AutoSync = AutoSync.OFF) {
    val name: String get() = folder.name
}

/** The repos this app syncs. */
interface RepoStore {
    /** Every repo, again each time one is saved. */
    val all: Flow<List<SavedRepo>>

    /** Blocks on file I/O. */
    fun save(repo: SavedRepo)
}

/** Keeps the repos as JSON in one small file, rewritten whole. */
class FileRepoStore(private val file: File) : RepoStore {
    private val repos by lazy { MutableStateFlow(read()) }

    override val all: Flow<List<SavedRepo>> = flow { emitAll(repos) }.flowOn(Dispatchers.IO)

    @Synchronized
    override fun save(repo: SavedRepo) {
        val next = repos.value.filter { it.id != repo.id } + repo
        file.writeAtomically(
            JSONArray(next.map { JSONObject().put("id", it.id).put("url", it.url).put("folder", it.folder.path).put("auto", it.auto.name) }).toString(),
        )
        repos.value = next
    }

    private fun read(): List<SavedRepo> {
        if (!file.exists()) return emptyList()
        return JSONArray(file.readText()).objects().map {
            // Repos saved before automatic syncs, or with a setting this version lacks, sync only when asked
            val auto = AutoSync.entries.find { auto -> auto.name == it.optString("auto") } ?: AutoSync.OFF
            SavedRepo(it.getString("id"), it.getString("url"), File(it.getString("folder")), auto)
        }
    }
}
