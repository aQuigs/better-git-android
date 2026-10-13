package com.sqftware.safegit.work

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sqftware.safegit.Services
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SafeSync
import com.sqftware.safegit.sync.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Where a repo's sync stands: running, or the last one's result. */
sealed interface SyncState {
    data object Running : SyncState

    data class Finished(val result: SyncResult) : SyncState
}

/** Starts syncs and reports how they stand. */
interface Syncer {
    /** Each repo's sync state by id; a repo never synced has none. */
    val states: Flow<Map<String, SyncState>>

    fun sync(repo: SavedRepo, choices: Map<Choice, Keep> = emptyMap())
}

/**
 * Runs each sync as WorkManager work, unique per repo, so two syncs of one repo never overlap, and expedited, since it
 * takes seconds and the user is waiting.
 */
class WorkSyncer(context: Context) : Syncer {
    private val work = WorkManager.getInstance(context)
    private val records = Services.of(context).records

    override val states: Flow<Map<String, SyncState>> = work.getWorkInfosByTagFlow(TAG).map { infos ->
        val running = infos.filter { !it.state.isFinished }.mapNotNull { it.repoId }.toSet()
        val known = infos.mapNotNull { it.repoId }.toSet()
        known.associateWith { id -> if (id in running) SyncState.Running else records.result(id)?.let(SyncState::Finished) }
            .filterValues { it != null }.mapValues { it.value!! }
    }

    override fun sync(repo: SavedRepo, choices: Map<Choice, Keep>) {
        records.saveChoices(repo.id, choices)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(TAG)
            .addTag("$REPO_TAG${repo.id}")
            .setInputData(workDataOf(SyncWorker.REPO to repo.id))
            // Before Android 12, expedited work runs as a foreground service, which needs a notification of its own
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        work.enqueueUniqueWork("sync-${repo.id}", ExistingWorkPolicy.KEEP, request)
    }

    private val WorkInfo.repoId get() = tags.firstOrNull { it.startsWith(REPO_TAG) }?.removePrefix(REPO_TAG)

    private companion object {
        const val TAG = "sync"
        const val REPO_TAG = "repo:"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = Services.of(applicationContext)
        val id = inputData.getString(REPO) ?: return Result.failure()
        val saved = services.repos.all().find { it.id == id } ?: return Result.failure()

        val result = withContext(Dispatchers.IO) {
            try {
                SafeSync(services.git, services.identity, services.device)
                    .sync(services.repo(saved), Credentials.NONE, services.records.takeChoices(id))
            } catch (e: Exception) {
                // Shown like any other failure rather than leaving the last result up as if nothing had happened
                SyncResult.Failed(Problem.GIT, e.toString())
            }
        }
        services.records.saveResult(id, result)
        return Result.success()
    }

    companion object {
        const val REPO = "repo"
    }
}
