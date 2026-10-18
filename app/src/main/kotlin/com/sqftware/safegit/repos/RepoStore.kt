package com.sqftware.safegit.repos

import com.sqftware.safegit.storage.objects
import com.sqftware.safegit.sync.CommitTemplate
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

/** A folder connected to [branch] of a remote, by the id that names its git dir; its syncs commit with [message], a [CommitTemplate]. */
data class SavedRepo(
    val id: String,
    val url: String,
    val folder: File,
    val branch: String,
    val auto: AutoSync = AutoSync.OFF,
    val message: String = CommitTemplate.DEFAULT_TEMPLATE,
) {
    val name: String get() = folder.name
}

/** The repos this app syncs. */
interface RepoStore {
    /** Every repo, again each time one is saved. */
    val all: Flow<List<SavedRepo>>

    /** Blocks on file I/O. */
    fun save(repo: SavedRepo)
}

/** Keeps the repos as JSON in one small file, rewritten whole; [branchOf] a repo saved before its branch was is read from its git dir. */
class FileRepoStore(private val file: File, private val branchOf: (id: String) -> String) : RepoStore {
    private val repos by lazy { MutableStateFlow(read()) }

    override val all: Flow<List<SavedRepo>> = flow { emitAll(repos) }.flowOn(Dispatchers.IO)

    @Synchronized
    override fun save(repo: SavedRepo) {
        // A repo saved again keeps its place, so its card stays put when a setting changes
        val next = if (repos.value.any { it.id == repo.id }) repos.value.map { if (it.id == repo.id) repo else it } else repos.value + repo
        file.writeAtomically(
            JSONArray(next.map { JSONObject().put("id", it.id).put("url", it.url).put("folder", it.folder.path).put("branch", it.branch).put("auto", it.auto.name).put("message", it.message) }).toString(),
        )
        repos.value = next
    }

    private fun read(): List<SavedRepo> {
        if (!file.exists()) return emptyList()
        return JSONArray(file.readText()).objects().map {
            // Repos saved before automatic syncs, or with a setting this version lacks, sync only when asked
            val auto = AutoSync.entries.find { auto -> auto.name == it.optString("auto") } ?: AutoSync.OFF
            val id = it.getString("id")
            SavedRepo(id, it.getString("url"), File(it.getString("folder")), it.optString("branch").ifEmpty { branchOf(id) }, auto, it.optString("message", CommitTemplate.DEFAULT_TEMPLATE))
        }
    }
}
