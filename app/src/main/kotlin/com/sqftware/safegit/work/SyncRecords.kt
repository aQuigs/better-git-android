package com.sqftware.safegit.work

import com.sqftware.safegit.storage.objects
import com.sqftware.safegit.storage.stringOrNull
import com.sqftware.safegit.storage.strings
import com.sqftware.safegit.storage.writeAtomically
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Each repo's last sync result and the choices for its next one, and the last sync of every repo, kept in files:
 * WorkManager's own data is capped at 10 KB, which a long list of conflicted paths can pass. Every call blocks on file
 * I/O.
 */
class SyncRecords(private val dir: File) {
    /** The last result, or null when there is none or this version cannot read it. */
    fun result(id: String): SyncResult? = read(file(id, RESULT)) { decode(it) }

    @Synchronized
    fun saveResult(id: String, result: SyncResult) = file(id, RESULT).writeAtomically(encode(result).toString())

    @Synchronized
    fun saveChoices(id: String, choices: Map<Choice, Keep>) {
        if (choices.isEmpty()) {
            file(id, CHOICES).delete()
            return
        }
        file(id, CHOICES).writeAtomically(JSONArray(choices.map { (choice, keep) -> encode(choice).put("keep", keep.name) }).toString())
    }

    /** The choices saved for the next sync, which uses them once. */
    @Synchronized
    fun takeChoices(id: String): Map<Choice, Keep> {
        val file = file(id, CHOICES).takeIf { it.exists() } ?: return emptyMap()
        val array = JSONArray(file.readText())
        file.delete()
        return array.objects().associate { decodeChoice(it) to Keep.valueOf(it.getString("keep")) }
    }

    /** The last sync of every repo, never [AllRun.going], or null when there is none or it was closed. */
    fun all(): AllRun? = readAll()?.let { (_, run) -> run }

    /** Starts a sync of every repo in [ids], whose results only a sync of the same [run] records. */
    @Synchronized
    fun startAll(run: String, ids: List<String>) = writeAll(run, AllRun(ids))

    @Synchronized
    fun saveAllResult(run: String, id: String, result: SyncResult) {
        val (saved, all) = readAll() ?: return
        if (saved == run) writeAll(run, all.copy(results = all.results + (id to result)))
    }

    @Synchronized
    fun closeAll() {
        dir.resolve(ALL).delete()
    }

    private fun readAll(): Pair<String, AllRun>? = read(dir.resolve(ALL)) { json ->
        val results = json.getJSONObject("results")
        json.getString("run") to AllRun(json.getJSONArray("ids").strings(), results.keys().asSequence().associateWith { decode(results.getJSONObject(it)) })
    }

    /** What [file] holds, or null when there is none or this version cannot read it. */
    private fun <T> read(file: File, parse: (JSONObject) -> T): T? {
        if (!file.exists()) return null
        return try {
            parse(JSONObject(file.readText()))
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeAll(run: String, all: AllRun) {
        val results = JSONObject().apply { for ((id, result) in all.results) put(id, encode(result)) }
        dir.resolve(ALL).writeAtomically(JSONObject().put("run", run).put("ids", JSONArray(all.ids)).put("results", results).toString())
    }

    private fun file(id: String, kind: String) = dir.resolve("$id.$kind.json")

    private fun encode(result: SyncResult): JSONObject = when (result) {
        SyncResult.UpToDate -> JSONObject().put("type", "upToDate")
        is SyncResult.Synced -> JSONObject().put("type", "synced").put("pulled", result.pulled).put("pushed", result.pushed)
        is SyncResult.Conflict -> JSONObject().put("type", "conflict").put("paths", JSONArray(result.paths))
        is SyncResult.ConflictAhead -> JSONObject().put("type", "conflictAhead").put("paths", JSONArray(result.paths))
        is SyncResult.Choose -> JSONObject().put("type", "choose").put("choices", JSONArray(result.choices.map(::encode)))
        is SyncResult.Failed -> JSONObject().put("type", "failed").put("problem", result.problem.name).put("detail", result.detail)
    }

    private fun decode(json: JSONObject): SyncResult = when (val type = json.getString("type")) {
        "upToDate" -> SyncResult.UpToDate
        "synced" -> SyncResult.Synced(json.getInt("pulled"), json.getInt("pushed"))
        "conflict" -> SyncResult.Conflict(json.getJSONArray("paths").strings())
        "conflictAhead" -> SyncResult.ConflictAhead(json.getJSONArray("paths").strings())
        "choose" -> SyncResult.Choose(json.getJSONArray("choices").objects().map(::decodeChoice))
        "failed" -> SyncResult.Failed(Problem.valueOf(json.getString("problem")), json.getString("detail"))
        else -> throw IllegalArgumentException("Unknown sync result $type")
    }

    private fun encode(choice: Choice) = JSONObject().put("path", choice.path)
        .put("local", choice.local ?: JSONObject.NULL).put("remote", choice.remote ?: JSONObject.NULL)

    private fun decodeChoice(json: JSONObject) = Choice(json.getString("path"), json.stringOrNull("local"), json.stringOrNull("remote"))

    private companion object {
        const val RESULT = "result"
        const val CHOICES = "choices"
        const val ALL = "all.json"
    }
}
