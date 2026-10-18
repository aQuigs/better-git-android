package com.sqftware.safegit.work

import com.sqftware.safegit.sync.Cancellation
import com.sqftware.safegit.sync.Step
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The syncs running in this process, each with how it stands and what cancels it. Kept in memory rather than as
 * WorkManager progress, since a sync's git outlives a stopped worker but never its process.
 */
class RunningSyncs {
    private data class Entry(val state: SyncState.Running, val cancellation: Cancellation)

    private val current = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val states: Flow<Map<String, SyncState.Running>> = current.map { entries -> entries.mapValues { it.value.state } }

    fun start(repo: String, cancellation: Cancellation) = current.update { it + (repo to Entry(SyncState.Running(step = null), cancellation)) }

    fun step(repo: String, step: Step) {
        change(repo) { it.copy(step = step) }
    }

    /** Cancels [repo]'s sync if it is running here. */
    fun cancel(repo: String) {
        change(repo) { it.copy(cancelling = true) }?.cancel()
    }

    fun end(repo: String) = current.update { it - repo }

    /** Changes the state of [repo]'s sync if it is running, and returns what cancels the sync changed. */
    private fun change(repo: String, change: (SyncState.Running) -> SyncState.Running): Cancellation? {
        var changed: Cancellation? = null
        current.update { entries ->
            val entry = entries[repo] ?: return@update entries
            changed = entry.cancellation
            entries + (repo to entry.copy(state = change(entry.state)))
        }
        return changed
    }
}
