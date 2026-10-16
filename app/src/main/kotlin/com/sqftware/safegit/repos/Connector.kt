package com.sqftware.safegit.repos

import com.sqftware.safegit.git.Git
import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.RepoSetup
import com.sqftware.safegit.work.Syncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Connects a folder to a repo, saves it and starts its first sync. It runs in [scope] rather than a screen's, so a
 * connect carries on through rotation, and one runs at a time, so a second tap cannot connect the folder twice.
 */
class Connector(
    private val git: () -> Git,
    private val identity: Identity,
    private val credentials: () -> Credentials,
    private val repos: RepoStore,
    private val syncer: Syncer,
    private val gitDir: (id: String) -> File,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State

        data object Connecting : State

        data class Failed(val problem: Problem, val detail: String) : State

        data class Refused(val reason: Refusal) : State

        data class Connected(val repo: SavedRepo) : State
    }

    enum class Refusal {
        /** A token typed into the URL would be kept and shown in plain text. */
        PASSWORD_IN_URL,

        /** Two repos syncing one file would each push the other's changes as their own. */
        FOLDER_OVERLAPS,
    }

    private val current = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = current

    @Synchronized
    fun connect(url: String, folder: File) {
        if (current.value == State.Connecting) return
        current.value = State.Connecting
        scope.launch { current.value = connected(url, folder) }
    }

    /** Back to idle once the outcome has been shown; a running connect carries on. */
    @Synchronized
    fun reset() {
        if (current.value != State.Connecting) current.value = State.Idle
    }

    private suspend fun connected(url: String, folder: File): State {
        if (PASSWORD.containsMatchIn(url)) return State.Refused(Refusal.PASSWORD_IN_URL)
        if (repos.all.first().any { overlap(it.folder, folder) }) return State.Refused(Refusal.FOLDER_OVERLAPS)

        val id = UUID.randomUUID().toString()
        val result = try {
            RepoSetup(git(), identity).connect(url, gitDir(id), folder, credentials())
        } catch (e: Exception) {
            // Such as the folder gone since it was picked
            RepoSetup.Result.Failed(Problem.GIT, e.toString())
        }
        return when (result) {
            is RepoSetup.Result.Failed -> State.Failed(result.problem, result.detail)
            is RepoSetup.Result.Connected -> {
                val saved = SavedRepo(id, url, folder)
                repos.save(saved)
                syncer.sync(saved)
                State.Connected(saved)
            }
        }
    }

    private fun overlap(a: File, b: File): Boolean {
        val (x, y) = a.canonicalFile.toPath() to b.canonicalFile.toPath()
        return x.startsWith(y) || y.startsWith(x)
    }

    private companion object {
        val PASSWORD = Regex("^[a-z][a-z0-9+.-]*://[^/@]*:[^/@]*@", RegexOption.IGNORE_CASE)
    }
}
