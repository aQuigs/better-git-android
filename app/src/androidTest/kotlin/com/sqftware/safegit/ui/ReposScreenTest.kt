package com.sqftware.safegit.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.work.SyncState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReposScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val vault = SavedRepo("1", "https://github.com/me/notes.git", File("/storage/emulated/0/Documents/notes"))

    @Test
    fun syncsARepo() {
        val synced = mutableListOf<Pair<SavedRepo, Map<Choice, Keep>>>()
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null)), onSync = { repo, choices -> synced += repo to choices }, onAdd = {}) }

        compose.onNodeWithText("Not synced yet").assertExists()
        compose.onNodeWithText("Sync").performClick()

        assertEquals(listOf(vault to emptyMap<Choice, Keep>()), synced)
    }

    @Test
    fun cannotSyncWhileSyncing() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault, SyncState.Running)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("Sync").assertIsNotEnabled()
    }

    @Test
    fun listsTheFilesWithMarkers() {
        val conflict = SyncState.Finished(SyncResult.Conflict(listOf("daily/today.md", "ideas.md")))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, conflict)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("daily/today.md").assertExists()
        compose.onNodeWithText("ideas.md").assertExists()
        compose.onNodeWithText("Sync").assertIsEnabled()
    }

    @Test
    fun syncsWithAChoiceForEveryFile() {
        val photo = Choice("photo.png", local = "a1", remote = "b2")
        val deleted = Choice("old.md", local = "c3", remote = null)
        val synced = mutableListOf<Map<Choice, Keep>>()
        val asked = SyncState.Finished(SyncResult.Choose(listOf(photo, deleted)))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, asked)), onSync = { _, choices -> synced += choices }, onAdd = {}) }

        compose.onNodeWithText("This phone has it, the remote deleted it").assertExists()
        compose.onAllNodesWithText("Keep this phone's")[0].performClick()
        compose.onNodeWithText("Sync with these choices").assertIsNotEnabled()
        compose.onAllNodesWithText("Keep the remote's")[1].performClick()
        compose.onNodeWithText("Sync with these choices").performClick()

        assertEquals(listOf(mapOf(photo to Keep.LOCAL, deleted to Keep.REMOTE)), synced)
    }

    @Test
    fun invitesToAddTheFirstRepo() {
        var added = false
        compose.setContent { ReposScreen(emptyList(), onSync = { _, _ -> }, onAdd = { added = true }) }

        compose.onNodeWithText("Add a repo").performClick()

        assertEquals(true, added)
    }
}
