package com.sqftware.safegit.repos

import com.sqftware.safegit.net.HttpStatusException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.io.IOException

/**
 * The signed-in account's repos, to pick one when adding a repo. They load from [directory] while [signedIn] and a
 * screen watches [state], and are kept a few seconds after it stops, so a rotation reuses them while a later visit
 * sees repos made since.
 */
class AccountRepos(directory: RepoDirectory, signedIn: Flow<Boolean>, scope: CoroutineScope) {
    sealed interface State {
        /** Signed out: there is only a pasted URL. */
        data object None : State

        data object Loading : State

        data class Loaded(val repos: List<HostedRepo>) : State

        data object Failed : State

        /** The host refused the token, so a sync would fail too. */
        data object SignInRefused : State
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> = signedIn.distinctUntilChanged().transformLatest { signedIn ->
        if (!signedIn) {
            emit(State.None)
            return@transformLatest
        }
        emit(State.Loading)
        emit(
            try {
                State.Loaded(directory.repos())
            } catch (e: HttpStatusException) {
                if (e.status == UNAUTHORIZED) State.SignInRefused else State.Failed
            } catch (_: IOException) {
                State.Failed
            },
        )
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(KEEP, replayExpirationMillis = 0), State.None)

    private companion object {
        const val KEEP = 5_000L
        const val UNAUTHORIZED = 401
    }
}
