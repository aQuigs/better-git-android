package com.sqftware.safegit.work

import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.FileChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * What each repo's next sync would push, read by [read] while the app is in front rather than watched, since reading a
 * large shared folder takes a while. Reads run one at a time, and a repo asked for again before its read starts is read
 * once.
 */
class Outgoing(private val repos: RepoStore, private val read: (SavedRepo) -> FileChanges?, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<Map<String, FileChanges>>(emptyMap())

    /** By repo id; a repo not read yet, whose read failed, or about which nothing can be said yet has none. */
    val pending: StateFlow<Map<String, FileChanges>> = current
    private val due = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var shown = false

    init {
        scope.launch {
            for (unit in wake) {
                val ids = due.toSet()
                due.removeAll(ids)
                repos.all.first().filter { it.id in ids }.forEach(::update)
            }
        }
    }

    /** The app came to the front: reads every repo's again, and each one's after it syncs, until [hide]. */
    fun show() {
        if (shown) return
        shown = true
        scope.launch { ask(repos.all.first()) }
    }

    fun hide() {
        shown = false
    }

    /** [repo] synced, so what it would push has changed; out of sight, it is only forgotten until the app is in front. */
    fun synced(repo: SavedRepo) {
        if (shown) ask(listOf(repo)) else current.update { it - repo.id }
    }

    private fun ask(repos: List<SavedRepo>) {
        due.addAll(repos.map { it.id })
        wake.trySend(Unit)
    }

    private fun update(repo: SavedRepo) {
        // A count left from before a failed read would be wrong by an unknown amount
        val pending = try {
            read(repo)
        } catch (_: Exception) {
            null
        }
        current.update { if (pending == null) it - repo.id else it + (repo.id to pending) }
    }
}
