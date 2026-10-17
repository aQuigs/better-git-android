package com.sqftware.safegit.repos

import com.sqftware.safegit.sync.RepoSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** The branches of a URL being typed, from [list], to pick the one to connect to. */
class RemoteBranches(private val list: (url: String) -> RepoSetup.Branches?, private val wait: Long = WAIT) {
    /** Each names its [url], so a screen never takes one URL's branches for the next one's. */
    sealed interface State {
        val url: String

        /** Nothing to pick from, so a connect takes the remote's default branch. */
        data class None(override val url: String) : State

        data class Loading(override val url: String) : State

        /** [names] with the [default] first, when the remote has one. */
        data class Listed(override val url: String, val names: List<String>, val default: String?) : State

        data class Failed(override val url: String) : State
    }

    /** Asks once [url] has stayed the same for a moment, so typing it out asks the remote nothing. */
    fun of(url: String): Flow<State> = flow {
        if (url.isEmpty()) return@flow emit(State.None(url))
        emit(State.Loading(url))
        delay(wait)
        emit(
            when (val branches = list(url)) {
                null -> State.None(url)
                is RepoSetup.Branches.Failed -> State.Failed(url)
                is RepoSetup.Branches.Listed -> when {
                    branches.names.isEmpty() -> State.None(url)
                    else -> State.Listed(url, branches.names.sortedBy { it != branches.default }, branches.default)
                }
            },
        )
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val WAIT = 700L
    }
}
