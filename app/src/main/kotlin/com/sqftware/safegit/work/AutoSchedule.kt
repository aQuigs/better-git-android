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

/**
 * Schedules a repo's automatic syncs as WorkManager work: one periodic, and a one-off when a run should come sooner
 * than the next period. Neither carries the tag the sync states watch, since periodic work is never finished.
 */
internal class AutoSchedule(private val work: WorkManager) {
    /** Schedules [repo] by its setting, or stops its automatic syncs when that is off. */
    fun set(repo: SavedRepo) {
        val period = repo.auto.period
        if (period == null) {
            work.cancelUniqueWork(periodic(repo.id))
            work.cancelUniqueWork(again(repo.id))
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(period.toJavaDuration())
            .setConstraints(CONSTRAINTS)
            .setInputData(input(repo.id, again = false))
            .build()
        work.enqueueUniquePeriodicWork(periodic(repo.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Runs an automatic sync of [repo] after [delay]; [fromAgain] when one such run asks for the next. */
    fun again(repo: String, delay: Duration, fromAgain: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay.toJavaDuration())
            .setConstraints(CONSTRAINTS)
            .setInputData(input(repo, again = true))
            .build()
        // A one-off asking for the next is still running, and would count as the one waiting; for any other run, the
        // one waiting will do
        work.enqueueUniqueWork(again(repo), if (fromAgain) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun input(repo: String, again: Boolean) = workDataOf(SyncWorker.REPO to repo, SyncWorker.AUTOMATIC to true, SyncWorker.AGAIN to again)

    private fun periodic(repo: String) = "auto-$repo"

    private fun again(repo: String) = "auto-again-$repo"

    private companion object {
        val CONSTRAINTS = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
    }
}
