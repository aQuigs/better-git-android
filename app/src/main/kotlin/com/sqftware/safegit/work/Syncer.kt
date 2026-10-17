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
import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.RecentEdits
import com.sqftware.safegit.sync.SafeSync
import com.sqftware.safegit.sync.Step
import com.sqftware.safegit.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Where a repo's sync stands: waiting to start, running, or the last one's result. */
sealed interface SyncState {
    /** Asked for, but Android has not started it yet. */
    data object Waiting : SyncState

    /** Running; [step] is null until the sync reports its first. */
    data class Running(val step: Step?) : SyncState

    data class Finished(val result: SyncResult) : SyncState
}

/** Starts syncs and reports how they stand. */
interface Syncer {
    /** Each repo's sync state by id; a repo never synced has none. */
    val states: Flow<Map<String, SyncState>>

    fun sync(repo: SavedRepo, choices: Map<Choice, Keep> = emptyMap())

    /** Saves how often [repo] syncs on its own, and schedules it so. */
    fun setAuto(repo: SavedRepo, auto: AutoSync)
}

/**
 * Runs each sync the user starts as WorkManager work, unique per repo, and expedited, since it takes seconds and the
 * user is waiting. Automatic syncs run as the same worker, on [AutoSchedule]'s schedule.
 */
class WorkSyncer(
    context: Context,
    private val repos: RepoStore,
    private val records: SyncRecords,
    steps: SyncSteps,
    private val scope: CoroutineScope,
) : Syncer {
    private val work = WorkManager.getInstance(context)
    private val schedule = AutoSchedule(work)

    // WorkManager only says what is running: it prunes finished work after a day, and the records keep every result
    override val states: Flow<Map<String, SyncState>> = combine(repos.all, work.getWorkInfosByTagFlow(TAG), steps.running) { saved, infos, running ->
        val waiting = infos.filter { !it.state.isFinished }.mapNotNull { it.repoId }.toSet()
        saved.mapNotNull { repo ->
            val state = when (repo.id) {
                in running -> SyncState.Running(running[repo.id])
                in waiting -> SyncState.Waiting
                else -> records.result(repo.id)?.let(SyncState::Finished)
            }
            state?.let { repo.id to it }
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun sync(repo: SavedRepo, choices: Map<Choice, Keep>) {
        scope.launch(Dispatchers.IO) {
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
    }

    override fun setAuto(repo: SavedRepo, auto: AutoSync) {
        scope.launch(Dispatchers.IO) {
            val saved = repo.copy(auto = auto)
            repos.save(saved)
            schedule.set(saved)
        }
    }

    private val WorkInfo.repoId get() = tags.firstOrNull { it.startsWith(REPO_TAG) }?.removePrefix(REPO_TAG)

    private companion object {
        const val TAG = "sync"
        const val REPO_TAG = "repo:"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val services = Services.of(applicationContext)
        val id = inputData.getString(REPO) ?: return@withContext Result.failure()
        val saved = services.repos.all.first().find { it.id == id } ?: return@withContext Result.failure()
        val automatic = inputData.getBoolean(AUTOMATIC, false)
        val schedule = AutoSchedule(WorkManager.getInstance(applicationContext))
        val again = { delay: Duration -> schedule.again(id, delay, fromAgain = inputData.getBoolean(AGAIN, false)) }

        // Unique work is not enough: a stopped worker's sync runs on, since git cannot be cancelled, while WorkManager
        // starts the same work again. The lock is held by the thread running git, so the rerun waits for it.
        synchronized(locks.computeIfAbsent(id) { Any() }) {
            // Work scheduled before the setting went off, and not yet cancelled
            if (automatic && saved.auto == AutoSync.OFF) return@withContext Result.success()
            val repo = services.repo(saved)

            services.steps.start(id)
            try {
                val result = try {
                    if (automatic) {
                        AutoRuns.snooze(RecentEdits(services.git).latest(repo), System.currentTimeMillis())?.let {
                            again(it)
                            return@withContext Result.success()
                        }
                    }
                    // The user's choices wait for the sync they start; an automatic one leaves conflicts to it too
                    val choices = if (automatic) emptyMap() else services.records.takeChoices(id)
                    SafeSync(services.git, services.signIn.identity(services.device), services.device)
                        .sync(repo, services.signIn.credentials(), choices, markConflicts = !automatic) { services.steps.step(id, it) }
                } catch (e: Exception) {
                    // Shown like any other failure rather than leaving the last result up as if nothing had happened
                    SyncResult.Failed(Problem.GIT, e.toString())
                }
                services.records.saveResult(id, result)
                services.notices.after(saved, result, automatic)
                if (automatic) AutoRuns.retry(saved.auto, result)?.let(again)
            } finally {
                services.steps.end(id)
            }
        }
        Result.success()
    }

    companion object {
        const val REPO = "repo"

        /** Started by a schedule, not the user. */
        const val AUTOMATIC = "automatic"

        /** An automatic sync run before its period is up. */
        const val AGAIN = "again"
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
