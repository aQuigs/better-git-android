package com.sqftware.safegit.work

import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.storage.stringOrNull
import com.sqftware.safegit.storage.writeAtomically
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import kotlin.time.Duration.Companion.days

/** What a repo's syncs need from a user who is away from the app. */
enum class Notice { CONFLICT, CONFLICT_AHEAD, CHOOSE, SIGN_IN, FAILING }

/** Where notices go, such as Android's notifications: one per repo at a time. */
interface NoticeBoard {
    fun show(repo: SavedRepo, notice: Notice)

    fun clear(repo: SavedRepo)
}

/**
 * Decides which notice a repo's syncs call for, and shows each once, when it changes: a state that lasts is never
 * shown again every run, while one about other files is. Kept in [dir] beside the sync records, as each run may be in
 * a new process. Every call blocks on file I/O.
 */
class SyncNotices(private val dir: File, private val board: NoticeBoard, private val now: () -> Long = System::currentTimeMillis) {
    /** After a sync of [repo]; one the user started shows nothing, since they have the app open. */
    @Synchronized
    fun after(repo: SavedRepo, result: SyncResult, automatic: Boolean) {
        val last = read(repo.id)
        val failingSince = if (result is SyncResult.Failed) last.failingSince ?: now() else null
        val need = need(result, failingSince)
        val shown = when {
            result == SyncResult.UpToDate || result is SyncResult.Synced -> null
            // Seen in the app, so an automatic sync that comes to the same need not say it again
            !automatic -> need?.key
            // A failure that may pass leaves a conflict's notice up, since the conflict is still there
            need == null -> last.shown
            else -> need.key
        }

        if (!automatic || shown == null) {
            if (last.shown != null) board.clear(repo)
        } else if (need != null && shown != last.shown) {
            board.show(repo, need.notice)
        }
        val next = State(failingSince, shown)
        if (next != last) write(repo.id, next)
    }

    /** A notice, and what makes it new: the files and versions it is about. */
    private class Need(val notice: Notice, about: List<String> = emptyList()) {
        val key = (listOf(notice.name) + about.sorted()).joinToString("\n")
    }

    private fun need(result: SyncResult, failingSince: Long?) = when (result) {
        is SyncResult.Conflict -> Need(Notice.CONFLICT, result.paths)
        is SyncResult.ConflictAhead -> Need(Notice.CONFLICT_AHEAD, result.paths)
        is SyncResult.Choose -> Need(Notice.CHOOSE, result.choices.map { "${it.path} ${it.local} ${it.remote}" })
        is SyncResult.Failed -> when {
            result.problem == Problem.SIGN_IN -> Need(Notice.SIGN_IN)
            failingSince != null && now() - failingSince >= FAILING_FOR.inWholeMilliseconds -> Need(Notice.FAILING)
            else -> null
        }
        SyncResult.UpToDate, is SyncResult.Synced -> null
    }

    /** [shown] is the key of the notice up, or of the need the user last saw in the app. */
    private data class State(val failingSince: Long?, val shown: String?)

    private fun read(id: String): State {
        val file = file(id).takeIf { it.exists() } ?: return State(null, null)
        return try {
            val json = JSONObject(file.readText())
            State(json.optLong("failingSince").takeIf { json.has("failingSince") }, json.stringOrNull("shown"))
        } catch (_: JSONException) {
            State(null, null)
        }
    }

    private fun write(id: String, state: State) =
        file(id).writeAtomically(JSONObject().putOpt("failingSince", state.failingSince).putOpt("shown", state.shown).toString())

    private fun file(id: String) = dir.resolve("$id.notice.json")

    private companion object {
        val FAILING_FOR = 1.days
    }
}
