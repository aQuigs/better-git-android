package com.sqftware.safegit.work

import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Each repo's last sync result and the choices for its next one, kept in files: WorkManager's own data is capped at
 * 10 KB, which a long list of conflicted paths can pass.
 */
class SyncRecords(private val dir: File) {
    fun result(id: String): SyncResult? = read(id, "result")?.let { decode(JSONObject(it)) }

    fun saveResult(id: String, result: SyncResult) = write(id, "result", encode(result).toString())

    fun saveChoices(id: String, choices: Map<Choice, Keep>) = write(
        id,
        "choices",
        JSONArray(choices.map { (choice, keep) -> encode(choice).put("keep", keep.name) }).toString(),
    )

    /** The choices saved for the next sync, which uses them once. */
    fun takeChoices(id: String): Map<Choice, Keep> {
        val text = read(id, "choices") ?: return emptyMap()
        dir.resolve("$id.choices.json").delete()
        val array = JSONArray(text)
        return (0 until array.length()).map { array.getJSONObject(it) }.associate { decodeChoice(it) to Keep.valueOf(it.getString("keep")) }
    }

    private fun read(id: String, kind: String) = dir.resolve("$id.$kind.json").takeIf { it.exists() }?.readText()

    @Synchronized
    private fun write(id: String, kind: String, text: String) {
        dir.mkdirs()
        val next = dir.resolve("$id.$kind.json.new").apply { writeText(text) }
        check(next.renameTo(dir.resolve("$id.$kind.json"))) { "Could not save $kind for $id" }
    }

    private fun encode(result: SyncResult): JSONObject = when (result) {
        SyncResult.UpToDate -> JSONObject().put("type", "upToDate")
        is SyncResult.Synced -> JSONObject().put("type", "synced").put("pulled", result.pulled).put("pushed", result.pushed)
        is SyncResult.Conflict -> JSONObject().put("type", "conflict").put("paths", JSONArray(result.paths))
        is SyncResult.Choose -> JSONObject().put("type", "choose").put("choices", JSONArray(result.choices.map(::encode)))
        is SyncResult.Failed -> JSONObject().put("type", "failed").put("problem", result.problem.name).put("detail", result.detail)
    }

    private fun decode(json: JSONObject): SyncResult = when (json.getString("type")) {
        "upToDate" -> SyncResult.UpToDate
        "synced" -> SyncResult.Synced(json.getInt("pulled"), json.getInt("pushed"))
        "conflict" -> SyncResult.Conflict(json.getJSONArray("paths").strings())
        "choose" -> json.getJSONArray("choices").let { array ->
            SyncResult.Choose((0 until array.length()).map { decodeChoice(array.getJSONObject(it)) })
        }
        else -> SyncResult.Failed(Problem.valueOf(json.getString("problem")), json.getString("detail"))
    }

    private fun encode(choice: Choice) = JSONObject().put("path", choice.path)
        .put("local", choice.local ?: JSONObject.NULL).put("remote", choice.remote ?: JSONObject.NULL)

    private fun decodeChoice(json: JSONObject) =
        Choice(json.getString("path"), json.optString("local").takeIf { !json.isNull("local") }, json.optString("remote").takeIf { !json.isNull("remote") })

    private fun JSONArray.strings() = (0 until length()).map(::getString)
}
