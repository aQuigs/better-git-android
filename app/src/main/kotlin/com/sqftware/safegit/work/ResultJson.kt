package com.sqftware.safegit.work

import com.sqftware.safegit.storage.objects
import com.sqftware.safegit.storage.stringOrNull
import com.sqftware.safegit.storage.strings
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.FileChanges
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.json.JSONArray
import org.json.JSONObject

internal fun encode(result: SyncResult): JSONObject = when (result) {
    SyncResult.UpToDate -> JSONObject().put("type", "upToDate")
    is SyncResult.Synced -> JSONObject().put("type", "synced").put("pulled", result.pulled).put("pushed", result.pushed)
    is SyncResult.Conflict -> JSONObject().put("type", "conflict").put("paths", JSONArray(result.paths))
    is SyncResult.ConflictAhead -> JSONObject().put("type", "conflictAhead").put("paths", JSONArray(result.paths))
    is SyncResult.Choose -> JSONObject().put("type", "choose").put("choices", JSONArray(result.choices.map(::encode)))
    is SyncResult.Failed -> JSONObject().put("type", "failed").put("problem", result.problem.name).put("detail", result.detail)
    SyncResult.Cancelled -> JSONObject().put("type", "cancelled")
}

/** Throws [IllegalArgumentException] or [org.json.JSONException] for a result this version cannot read. */
internal fun decodeResult(json: JSONObject): SyncResult = when (val type = json.getString("type")) {
    "upToDate" -> SyncResult.UpToDate
    "synced" -> SyncResult.Synced(json.getInt("pulled"), json.getInt("pushed"))
    "conflict" -> SyncResult.Conflict(json.getJSONArray("paths").strings())
    "conflictAhead" -> SyncResult.ConflictAhead(json.getJSONArray("paths").strings())
    "choose" -> SyncResult.Choose(json.getJSONArray("choices").objects().map(::decodeChoice))
    "failed" -> SyncResult.Failed(Problem.valueOf(json.getString("problem")), json.getString("detail"))
    "cancelled" -> SyncResult.Cancelled
    else -> throw IllegalArgumentException("Unknown sync result $type")
}

internal fun encode(choice: Choice): JSONObject = JSONObject().put("path", choice.path)
    .put("local", choice.local ?: JSONObject.NULL).put("remote", choice.remote ?: JSONObject.NULL)

internal fun decodeChoice(json: JSONObject) = Choice(json.getString("path"), json.stringOrNull("local"), json.stringOrNull("remote"))

internal fun encode(files: FileChanges): JSONObject =
    JSONObject().put("changed", JSONArray(files.changed)).put("added", JSONArray(files.added)).put("deleted", JSONArray(files.deleted))

internal fun decodeFiles(json: JSONObject) =
    FileChanges(json.getJSONArray("changed").strings(), json.getJSONArray("added").strings(), json.getJSONArray("deleted").strings())
