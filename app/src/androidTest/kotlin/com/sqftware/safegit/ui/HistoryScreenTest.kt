package com.sqftware.safegit.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.sync.FileChanges
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.sync.Transfer
import com.sqftware.safegit.work.RecordedSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val pulled = FileChanges(changed = listOf("ideas.md"), added = listOf("laptop.md"), deleted = emptyList())
    private val pushed = FileChanges(changed = emptyList(), added = emptyList(), deleted = listOf("old.md"))

    @Test
    fun saysThereAreNoSyncsYet() {
        compose.setContent { HistoryScreen("notes", emptyList()) }

        compose.onNodeWithText("No syncs yet. Each sync shows here, with the files it pulled and pushed.").assertExists()
    }

    @Test
    fun countsWhatEachSyncMovedAndOpensIntoTheFiles() {
        val syncs = listOf(
            RecordedSync(2000, automatic = true, SyncResult.UpToDate, Transfer()),
            RecordedSync(1000, automatic = false, SyncResult.Synced(1, 1), Transfer(pulled, pushed)),
        )
        compose.setContent { HistoryScreen("notes", syncs) }

        compose.onNodeWithText("Up to date").assertExists()
        compose.onNodeWithText("No files pulled or pushed").assertExists()
        compose.onNodeWithText("laptop.md").assertDoesNotExist()

        compose.onNodeWithText("Pulled: 1 changed, 1 new\nPushed: 1 deleted ▾").performClick()

        compose.onNodeWithText("laptop.md").assertExists()
        compose.onNodeWithText("ideas.md").assertExists()
        compose.onNodeWithText("old.md").assertExists()
    }

    @Test
    fun opensAFilesVersionsFromASyncOrThePicker() {
        var opened: String? = null
        var picked = false
        val sync = RecordedSync(1000, automatic = false, SyncResult.Synced(1, 1), Transfer(pulled, pushed))
        compose.setContent { HistoryScreen("notes", listOf(sync), onFile = { opened = it }, onPickFile = { picked = true }) }

        compose.onNodeWithText("Pulled: 1 changed, 1 new\nPushed: 1 deleted ▾").performClick()
        compose.onNodeWithText("old.md").performClick()
        compose.onNodeWithText("Restore a file…").performClick()

        assertEquals("old.md", opened)
        assertTrue(picked)
    }

    @Test
    fun saysHowASyncFailedOnceOpened() {
        compose.setContent { HistoryScreen("notes", listOf(RecordedSync(1000, automatic = false, SyncResult.Failed(Problem.FETCH, "no network"), Transfer()))) }

        compose.onNodeWithText("Failed").assertExists()
        compose.onNodeWithText("no network").assertDoesNotExist()

        compose.onNodeWithText("No files pulled or pushed ▾").performClick()

        compose.onNodeWithText("no network").assertExists()
    }

    @Test
    fun listsAConflictsFiles() {
        val conflict = RecordedSync(1000, automatic = false, SyncResult.Conflict(listOf("ideas.md")), Transfer(pulled = pulled))
        compose.setContent { HistoryScreen("notes", listOf(conflict)) }

        compose.onNodeWithText("Conflicts to edit").assertExists()
        compose.onNodeWithText("Pulled: 1 changed, 1 new ▾").performClick()

        compose.onNodeWithText("Conflicted files").assertExists()
    }
}
