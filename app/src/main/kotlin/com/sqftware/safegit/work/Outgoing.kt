package com.sqftware.safegit.work

import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Pending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What each repo's next sync would push, read by [read] when asked rather than continuously, since reading a large
 * shared folder takes a while. Reads run one at a time, so the last one asked for is the one shown.
 */
class Outgoing(private val repos: RepoStore, private val read: (SavedRepo) -> Pending?, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<Map<String, Pending>>(emptyMap())

    /** By repo id; a repo not read yet, before its first commit, or whose read failed has none. */
    val pending: StateFlow<Map<String, Pending>> = current
    private val reading = Mutex()

    /** Reads every repo's again, such as when the app comes to the front. */
    fun refresh() {
        scope.launch { reading.withLock { repos.all.first().forEach(::update) } }
    }

    /** Reads [repo]'s again, such as after it synced. */
    fun refresh(repo: SavedRepo) {
        scope.launch { reading.withLock { update(repo) } }
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
