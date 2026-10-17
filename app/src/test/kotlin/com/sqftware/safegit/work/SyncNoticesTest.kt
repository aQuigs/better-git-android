package com.sqftware.safegit.work

import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class SyncNoticesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val vault = SavedRepo("1", "https://github.com/me/notes.git", File("/storage/emulated/0/Documents/notes"))
    private val board = object : NoticeBoard {
        val events = mutableListOf<String>()

        override fun show(repo: SavedRepo, notice: Notice) {
            events += "show $notice"
        }

        override fun clear(repo: SavedRepo) {
            events += "clear"
        }
    }
    private var now = 0L

    // A new one each time, as each run may be in a new process
    private fun after(result: SyncResult, automatic: Boolean = true) =
        SyncNotices(temp.root.resolve("syncs"), board) { now }.after(vault, result, automatic)

    private val conflict = SyncResult.ConflictAhead(listOf("notes.md"))
    private val offline = SyncResult.Failed(Problem.FETCH, "Could not resolve host: github.com")

    @Test
    fun showsANeedOnceHoweverManyRunsFindIt() {
        after(conflict)
        after(conflict)
        after(SyncResult.Conflict(listOf("notes.md")))

        assertEquals(listOf("show CONFLICT_AHEAD", "show CONFLICT"), board.events)
    }

    @Test
    fun showsItAgainForOtherFiles() {
        after(conflict)
        after(SyncResult.ConflictAhead(listOf("ideas.md")))

        assertEquals(listOf("show CONFLICT_AHEAD", "show CONFLICT_AHEAD"), board.events)
    }

    @Test
    fun clearsItOnceASyncGetsThrough() {
        after(conflict)
        after(SyncResult.Synced(1, 1))
        after(SyncResult.UpToDate)

        assertEquals(listOf("show CONFLICT_AHEAD", "clear"), board.events)
    }

    @Test
    fun keepsItUpThroughAFailureThatMayPass() {
        after(conflict)
        after(offline)
        after(conflict)

        assertEquals(listOf("show CONFLICT_AHEAD"), board.events)
    }

    @Test
    fun saysOfAFailureOnlyOnceItHasLastedADay() {
        after(offline)
        now += 23.hours.inWholeMilliseconds
        after(offline)
        now += 1.hours.inWholeMilliseconds
        after(offline)
        now += 1.days.inWholeMilliseconds
        after(offline)

        assertEquals(listOf("show FAILING"), board.events)
    }

    @Test
    fun countsADayFromTheFirstFailureSinceASyncGotThrough() {
        after(offline)
        now += 23.hours.inWholeMilliseconds
        after(SyncResult.UpToDate)
        after(offline)
        now += 23.hours.inWholeMilliseconds
        after(offline)

        assertEquals(emptyList<String>(), board.events)
    }

    @Test
    fun saysAtOnceWhenTheRemoteRefusesTheSignIn() {
        after(SyncResult.Failed(Problem.SIGN_IN))

        assertEquals(listOf("show SIGN_IN"), board.events)
    }

    @Test
    fun showsNothingForASyncTheUserStartedAndClearsWhatTheyHaveNowSeen() {
        after(conflict)
        after(SyncResult.Conflict(listOf("notes.md")), automatic = false)
        after(SyncResult.Conflict(listOf("notes.md")))

        assertEquals(listOf("show CONFLICT_AHEAD", "clear"), board.events)
    }
}
