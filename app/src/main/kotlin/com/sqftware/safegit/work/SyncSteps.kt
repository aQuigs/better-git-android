package com.sqftware.safegit.work

import com.sqftware.safegit.sync.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The syncs running in this process, each with the step it is on (null before its first). Kept in memory rather than
 * as WorkManager progress, since a sync's git outlives a stopped worker but never its process.
 */
class SyncSteps {
    private val current = MutableStateFlow<Map<String, Step?>>(emptyMap())
    val running: StateFlow<Map<String, Step?>> = current

    fun start(repo: String) = current.update { it + (repo to null) }

    fun step(repo: String, step: Step) = current.update { it + (repo to step) }

    fun end(repo: String) = current.update { it - repo }
}
