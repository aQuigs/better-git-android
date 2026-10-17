package com.sqftware.safegit.work

import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Pending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * What each repo's next sync would push, read by [read] when asked rather than continuously, since reading a large
 * shared folder takes a while. Reads run one at a time, and a repo asked for again before its read starts is read once.
 */
class Outgoing(private val repos: RepoStore, private val read: (SavedRepo) -> Pending?, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<Map<String, Pending>>(emptyMap())

    /** By repo id; a repo not read yet, whose read failed, or about which nothing can be said yet has none. */
    val pending: StateFlow<Map<String, Pending>> = current
    private val due = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unit in wake) {
                val ids = due.toSet()
                due.removeAll(ids)
                repos.all.first().filter { it.id in ids }.forEach(::update)
            }
        }
    }

    /** Reads every repo's again, such as when the app comes to the front. */
    fun refresh() {
        scope.launch { ask(repos.all.first()) }
    }

    /** Reads [repo]'s again, such as after it synced. */
    fun refresh(repo: SavedRepo) = ask(listOf(repo))

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
