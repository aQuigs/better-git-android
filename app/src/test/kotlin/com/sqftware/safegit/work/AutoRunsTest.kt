package com.sqftware.safegit.work

import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AutoRunsTest {
    private val now = 10.hours.inWholeMilliseconds

    @Test
    fun putsOffARunWhileTheFolderWasEditedInTheLastMinute() {
        assertEquals(5.minutes, AutoRuns.snooze(now - 30.seconds.inWholeMilliseconds, now))
        assertEquals(5.minutes, AutoRuns.snooze(now, now))
        assertNull(AutoRuns.snooze(now - 1.minutes.inWholeMilliseconds, now))
        assertNull(AutoRuns.snooze(null, now))
    }

    @Test
    fun runsDespiteAnEditTimeAheadOfTheClock() {
        assertNull(AutoRuns.snooze(now + 1.hours.inWholeMilliseconds, now))
    }

    @Test
    fun triesAFailureThatMayPassAgainHourlyForLongPeriodsOnly() {
        val offline = SyncResult.Failed(Problem.FETCH, "Could not resolve host: github.com")

        assertNull(AutoRuns.retry(AutoSync.EVERY_15_MINUTES, offline))
        assertNull(AutoRuns.retry(AutoSync.HOURLY, offline))
        for (auto in listOf(AutoSync.EVERY_6_HOURS, AutoSync.DAILY, AutoSync.WEEKLY)) {
            assertEquals(1.hours, AutoRuns.retry(auto, offline))
        }
    }

    @Test
    fun waitsForTheNextPeriodWhenTryingAgainWouldChangeNothing() {
        for (result in listOf(
            SyncResult.UpToDate,
            SyncResult.Synced(1, 1),
            SyncResult.ConflictAhead(listOf("notes.md")),
            SyncResult.Choose(emptyList()),
            SyncResult.Failed(Problem.SIGN_IN),
            SyncResult.Failed(Problem.FOLDER_BLOCKED, "draft.md"),
        )) {
            assertNull(result.toString(), AutoRuns.retry(AutoSync.DAILY, result))
        }
    }
}
