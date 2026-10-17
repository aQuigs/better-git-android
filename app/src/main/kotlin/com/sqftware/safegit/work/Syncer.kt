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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration

/** Where a repo's sync stands: waiting to start, running, or the last one's result. */
sealed interface SyncState {
    /** Asked for, but Android has not started it yet. */
    data object Waiting : SyncState

    /** Running; [step] is null until the sync reports its first. */
    data class Running(val step: Step?) : SyncState

    data class Finished(val result: SyncResult) : SyncState
}

/** A sync of every repo at once: the repos it queued, in order, and each one's result once it has one. */
data class AllRun(val ids: List<String>, val results: Map<String, SyncResult> = emptyMap(), val going: Boolean = false)

/** Starts syncs and reports how they stand. */
interface Syncer {
    /** Each repo's sync state by id; a repo never synced has none. */
    val states: Flow<Map<String, SyncState>>

    fun sync(repo: SavedRepo, choices: Map<Choice, Keep> = emptyMap())

    /** The last [syncAll], until [closeAll]; null when there is none. */
    val all: Flow<AllRun?>

    /** Syncs every repo one after another, each as [sync] would with no new choices. */
    fun syncAll()

    fun closeAll()

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
    private val schedule: AutoSchedule,
    private val scope: CoroutineScope,
) : Syncer {
    private val work = WorkManager.getInstance(context)
    private val allClosed = MutableStateFlow(0)

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

    // Each link of the chain records its result before it finishes, so a change of the chain's work brings the results
    override val all: Flow<AllRun?> = combine(allClosed, work.getWorkInfosForUniqueWorkFlow(ALL_WORK)) { _, infos ->
        records.all()?.copy(going = infos.any { !it.state.isFinished })
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun sync(repo: SavedRepo, choices: Map<Choice, Keep>) {
        scope.launch(Dispatchers.IO) {
            records.saveChoices(repo.id, choices)
            work.enqueueUniqueWork("sync-${repo.id}", ExistingWorkPolicy.KEEP, request(repo))
        }
    }

    override fun syncAll() {
        scope.launch(Dispatchers.IO) {
            val saved = repos.all.first().takeIf { it.isNotEmpty() } ?: return@launch
            val run = UUID.randomUUID().toString()
            records.startAll(run, saved.map { it.id })

            // A chain runs one sync at a time; appended, so a run still going finishes first, its results unrecorded
            var chain = work.beginUniqueWork(ALL_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(saved.first(), run))
            for (repo in saved.drop(1)) chain = chain.then(request(repo, run))
            chain.enqueue()
        }
    }

    override fun closeAll() {
        scope.launch(Dispatchers.IO) {
            records.closeAll()
            allClosed.update { it + 1 }
        }
    }

    private fun request(repo: SavedRepo, all: String? = null) = OneTimeWorkRequestBuilder<SyncWorker>()
        .addTag(TAG)
        .addTag("$REPO_TAG${repo.id}")
        .setInputData(workDataOf(SyncWorker.REPO to repo.id, SyncWorker.ALL to all))
        // Before Android 12, expedited work runs as a foreground service, which needs a notification of its own
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
        .build()

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
        const val ALL_WORK = "sync-all"
        const val REPO_TAG = "repo:"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val services = Services.of(applicationContext)
        val id = inputData.getString(REPO) ?: return@withContext Result.failure()
        val run = Run.entries.find { it.name == inputData.getString(RUN) } ?: Run.USER
        val automatic = run != Run.USER
        val again = { delay: Duration -> services.schedule.again(id, delay, from = run) }

        // Unique work is not enough: a rerun of a stopped worker waits for the sync it left running
        synchronized(services.locks.of(id)) {
            // The wait can be long, and the work cancelled or the setting turned off meanwhile
            if (isStopped) return@withContext Result.success()
            // Not a failure, which would fail the rest of a sync-all chain
            val saved = runBlocking { services.repos.all.first() }.find { it.id == id } ?: return@withContext Result.success()
            if (automatic && saved.auto == AutoSync.OFF) return@withContext Result.success()

            val result = sync(services, saved, automatic)
            if (result == null) {
                again(AutoRuns.SNOOZE)
                return@withContext Result.success()
            }
            if (automatic) AutoRuns.retry(saved.auto, result)?.let(again)
            services.notices.after(saved, result, automatic)
        }
        Result.success()
    }

    /** Syncs [saved] and saves the result, or returns null when an automatic sync is put off by an edit. */
    private fun sync(services: Services, saved: SavedRepo, automatic: Boolean): SyncResult? {
        val repo = services.repo(saved)
        services.steps.start(saved.id)
        try {
            val result = try {
                if (automatic && AutoRuns.putOff(RecentEdits(services.git).latest(repo), System.currentTimeMillis())) return null
                // The user's choices wait for the sync they start; an automatic one leaves conflicts to it too
                val choices = if (automatic) emptyMap() else services.records.takeChoices(saved.id)
                SafeSync(services.git, services.signIn.identity(services.device), services.device)
                    .sync(repo, services.signIn.credentials(), choices, attended = !automatic) { services.steps.step(saved.id, it) }
            } catch (e: Exception) {
                // Shown like any other failure rather than leaving the last result up as if nothing had happened
                SyncResult.Failed(Problem.GIT, e.toString())
            }
            // Before the sync stops showing as running, so the state shown next is this result
            services.records.saveResult(saved.id, result)
            services.outgoing.synced(saved)
            inputData.getString(ALL)?.let { services.records.saveAllResult(it, saved.id, result) }
            return result
        } finally {
            services.steps.end(saved.id)
        }
    }

    companion object {
        const val REPO = "repo"

        /** A [Run] by name; a run without one is the user's. */
        const val RUN = "run"

        /** The sync of every repo this one is part of. */
        const val ALL = "all"
    }
}
