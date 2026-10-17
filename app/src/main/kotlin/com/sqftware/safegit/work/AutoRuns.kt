package com.sqftware.safegit.work

import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** When an automatic sync runs again before its period is up. */
object AutoRuns {
    /** How long the folder must have gone unedited before a sync nobody asked for moves it. */
    val QUIET = 1.minutes

    /** How soon a run put off by an edit tries again, so a long period is not skipped whole. */
    val SNOOZE = 5.minutes

    /** How soon a failed run tries again, for periods of [LONG] and more; a shorter one's next run comes soon enough. */
    val RETRY = 1.hours
    val LONG = 6.hours

    // Worth trying again unchanged; the others need the user, or a remote fixed
    private val transientProblems = setOf(
        Problem.FETCH,
        Problem.PUSH,
        Problem.REMOTE_KEPT_CHANGING,
        Problem.FOLDER_KEPT_CHANGING,
        Problem.FOLDER_WRITE,
        Problem.GIT,
    )

    /**
     * Whether a run puts itself off for [SNOOZE] when the folder was last edited at [lastEdit]. A time ahead of [now] is
     * a clock's doing, not an edit being saved, and would otherwise put off every run until then.
     */
    fun putOff(lastEdit: Long?, now: Long): Boolean {
        val since = (now - (lastEdit ?: return false)).milliseconds
        return since >= Duration.ZERO && since < QUIET
    }

    /** How soon to try again after a run of [auto] that came to [result], or null to wait for the next period. */
    fun retry(auto: AutoSync, result: SyncResult): Duration? {
        val period = auto.period ?: return null
        return if (result is SyncResult.Failed && result.problem in transientProblems && period >= LONG) RETRY else null
    }
}
