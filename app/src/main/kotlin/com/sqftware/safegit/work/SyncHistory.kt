package com.sqftware.safegit.work

import com.sqftware.safegit.storage.objects
import com.sqftware.safegit.storage.readableOrNull
import com.sqftware.safegit.storage.writeAtomically
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.sync.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A sync that has run: when it ended, whether the user started it, how it ended, and the files it moved. */
data class RecordedSync(val at: Long, val automatic: Boolean, val result: SyncResult, val transfer: Transfer)

/** Each repo's last syncs. */
interface SyncHistory {
    /** [id]'s syncs, newest first, again after each one [add]ed. */
    fun of(id: String): Flow<List<RecordedSync>>

    /** Blocks on I/O. */
    fun add(id: String, sync: RecordedSync)
}

/**
 * Keeps each repo's last [MAX_SYNCS] syncs in a file of its own, and fewer once they name more than [MAX_PATHS] files:
 * a first sync of a whole vault names thousands, and the file is rewritten on every sync.
 */
class FileSyncHistory(private val dir: File) : SyncHistory {
    // How many syncs each repo has had added, so a repo's screen reads again only for its own
    private val added = MutableStateFlow(emptyMap<String, Int>())

    override fun of(id: String): Flow<List<RecordedSync>> = added.map { it[id] }.distinctUntilChanged().map { read(id) }.flowOn(Dispatchers.IO)

    @Synchronized
    override fun add(id: String, sync: RecordedSync) {
        var paths = 0
        val kept = (listOf(sync) + read(id)).take(MAX_SYNCS).takeWhile { past ->
            paths += past.transfer.size
            past === sync || paths <= MAX_PATHS
        }
        file(id).writeAtomically(JSONArray(kept.map(::encode)).toString())
        added.update { it + (id to (it[id] ?: 0) + 1) }
    }

    /** What [id]'s file holds, less the syncs this version cannot read, as after a downgrade. */
    private fun read(id: String): List<RecordedSync> {
        val file = file(id).takeIf { it.exists() } ?: return emptyList()
        val entries = readableOrNull { JSONArray(file.readText()).objects() } ?: return emptyList()
        return entries.mapNotNull { readableOrNull { decode(it) } }
    }

    private fun file(id: String) = dir.resolve("$id.history.json")

    private fun encode(sync: RecordedSync) = JSONObject().put("at", sync.at).put("automatic", sync.automatic).put("result", encode(sync.result))
        .put("pulled", encode(sync.transfer.pulled)).put("pushed", encode(sync.transfer.pushed))

    private fun decode(json: JSONObject) = RecordedSync(
        json.getLong("at"),
        json.getBoolean("automatic"),
        decodeResult(json.getJSONObject("result")),
        Transfer(decodeFiles(json.getJSONObject("pulled")), decodeFiles(json.getJSONObject("pushed"))),
    )

    private val Transfer.size get() = pulled.size + pushed.size

    private companion object {
        const val MAX_SYNCS = 50
        const val MAX_PATHS = 20_000
    }
}
