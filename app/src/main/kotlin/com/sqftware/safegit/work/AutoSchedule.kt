package com.sqftware.safegit.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sqftware.safegit.repos.SavedRepo
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Who started a run of [SyncWorker]. */
enum class Run { USER, PERIODIC, AGAIN }

/**
 * Schedules a repo's automatic syncs as WorkManager work: one periodic, and a one-off when a run should come sooner
 * than the next period. Neither carries the tag the sync states watch, since periodic work is never finished.
 */
class AutoSchedule(private val work: WorkManager) {
    /** Schedules [repo] by its setting, or stops its automatic syncs when that is off. */
    fun set(repo: SavedRepo) {
        val period = repo.auto.period
        if (period == null) {
            work.cancelUniqueWork(periodicName(repo.id))
            work.cancelUniqueWork(againName(repo.id))
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(period.toJavaDuration())
            .setConstraints(CONSTRAINTS)
            .setInputData(input(repo.id, Run.PERIODIC))
            .build()
        work.enqueueUniquePeriodicWork(periodicName(repo.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Runs an automatic sync of [repo] after [delay], asked for by a run that [from] started. */
    fun again(repo: String, delay: Duration, from: Run) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay.toJavaDuration())
            .setConstraints(CONSTRAINTS)
            .setInputData(input(repo, Run.AGAIN))
            .build()
        // A one-off asking for the next is still running, and would count as the one waiting; for any other run, the
        // one waiting will do
        work.enqueueUniqueWork(againName(repo), if (from == Run.AGAIN) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun input(repo: String, run: Run) = workDataOf(SyncWorker.REPO to repo, SyncWorker.RUN to run.name)

    private fun periodicName(repo: String) = "auto-$repo"

    private fun againName(repo: String) = "auto-again-$repo"

    private companion object {
        val CONSTRAINTS = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
    }
}
