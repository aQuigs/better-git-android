package com.sqftware.safegit.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.ChoicePreview
import com.sqftware.safegit.sync.DiffLine
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.FileChanges
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.Step
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.sync.Version
import com.sqftware.safegit.work.AllRun
import com.sqftware.safegit.work.SyncState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReposScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val vault = SavedRepo("1", "https://github.com/me/notes.git", File("/storage/emulated/0/Documents/notes"), "main")
    private val journal = SavedRepo("2", "https://github.com/me/journal.git", File("/storage/emulated/0/Documents/journal"), "main")
    private val photos = SavedRepo("3", "https://github.com/me/photos.git", File("/storage/emulated/0/Documents/photos"), "main")
    private val refused = SyncState.Finished(SyncResult.Failed(Problem.SIGN_IN, "fatal: could not read Username for 'https://github.com': terminal prompts disabled"))

    @Test
    fun syncsARepo() {
        val synced = mutableListOf<Pair<SavedRepo, Map<Choice, Keep>>>()
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null)), onSync = { repo, choices -> synced += repo to choices }, onAdd = {}) }

        compose.onNodeWithText("Not synced yet").assertExists()
        compose.onNodeWithText("Sync").performClick()

        assertEquals(listOf(vault to emptyMap<Choice, Keep>()), synced)
    }

    @Test
    fun saysWhichStepTheSyncIsOnAndCancelsIt() {
        val cancelled = mutableListOf<SavedRepo>()
        compose.setContent {
            ReposScreen(listOf(RepoRow(vault, SyncState.Running(Step.COMMIT))), onSync = { _, _ -> }, onAdd = {}, onCancel = { cancelled += it })
        }

        compose.onNodeWithText("Committing this phone's changes…").assertExists()
        compose.onNodeWithText("Sync").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf(vault), cancelled)
    }

    @Test
    fun saysASyncIsCancelling() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault, SyncState.Running(Step.FETCH, cancelling = true))), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("Cancelling…").assertExists()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun saysASyncWasCancelled() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault, SyncState.Finished(SyncResult.Cancelled))), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("Sync stopped before it finished. Nothing was lost, and the next sync picks up where this one left off.").assertExists()
        compose.onNodeWithText("Sync").assertIsEnabled()
    }

    @Test
    fun cannotSyncWhileWaitingToStart() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault, SyncState.Waiting)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("Waiting for Android to start the sync…").assertExists()
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
    fun listsTheFilesAnAutomaticSyncLeftForTheUser() {
        val ahead = SyncState.Finished(SyncResult.ConflictAhead(listOf("ideas.md")))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, ahead)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("An automatic sync leaves them as they are", substring = true).assertExists()
        compose.onNodeWithText("ideas.md").assertExists()
        compose.onNodeWithText("Sync").assertIsEnabled()
    }

    @Test
    fun setsHowOftenARepoSyncsOnItsOwn() {
        val picked = mutableListOf<Pair<SavedRepo, AutoSync>>()
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null)), onSync = { _, _ -> }, onAdd = {}, onAuto = { repo, auto -> picked += repo to auto }) }

        compose.onNodeWithText("Auto sync: Off").performClick()
        compose.onNodeWithText("Every 6 hours").performClick()

        assertEquals(listOf(vault to AutoSync.EVERY_6_HOURS), picked)
    }

    @Test
    fun showsTheRepoAutoSyncSetting() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault.copy(auto = AutoSync.WEEKLY), null)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("Auto sync: Every week").assertExists()
    }

    @Test
    fun showsTheBranchARepoSyncs() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault.copy(branch = "drafts"), null)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithContentDescription("Branch: drafts").assertExists()
        compose.onNodeWithText("drafts", useUnmergedTree = true).assertExists()
    }

    @Test
    fun setsTheCommitMessageTemplate() {
        val saved = mutableListOf<Pair<SavedRepo, String>>()
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null)), onSync = { _, _ -> }, onAdd = {}, onMessage = { repo, message -> saved += repo to message }, device = "Pixel 9") }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Commit message template").performClick()
        compose.onNode(hasSetTextAction() and hasText("Changes from {device}")).assertExists()
        compose.onNodeWithText("Example: Changes from Pixel 9").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("{device}: {files} files ")
        compose.onNodeWithText("Example: Pixel 9: 3 files").assertExists()
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf(vault to "{device}: {files} files"), saved)
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun anEmptyCommitMessageSavesTheDefaultAndCancelSavesNothing() {
        val saved = mutableListOf<String>()
        val custom = vault.copy(message = "{date}")
        compose.setContent { ReposScreen(listOf(RepoRow(custom, null)), onSync = { _, _ -> }, onAdd = {}, onMessage = { _, message -> saved += message }) }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Commit message template").performClick()
        compose.onNode(hasSetTextAction() and hasText("{date}")).assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("edited")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Commit message template").performClick()
        compose.onNode(hasSetTextAction() and hasText("{date}")).assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("  ")
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf("Changes from {device}"), saved)
    }

    @Test
    fun saysWhichSubmodulesWereLeftAsTheyWere() {
        val left = SyncState.Finished(SyncResult.Failed(Problem.SUBMODULE, "lib: It is not on a branch."))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, left)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("The rest synced, but SafeGit left these submodules as they were:").assertExists()
        compose.onNodeWithText("lib: It is not on a branch.").assertExists()
        compose.onNodeWithText("Sync").assertIsEnabled()
    }

    @Test
    fun offersToSignInAgainWhenTheRemoteRefusesTheSignIn() {
        var signIns = 0
        compose.setContent { ReposScreen(listOf(RepoRow(vault, refused, canSignIn = true)), onSync = { _, _ -> }, onAdd = {}, onSignIn = { signIns++ }) }

        compose.onNodeWithText("The remote refused the sign-in, or the repo doesn't exist.").assertExists()
        compose.onNodeWithText("Sign in again").performClick()

        assertEquals(1, signIns)
    }

    @Test
    fun offersNoSignInThatCannotHelp() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault, refused, canSignIn = false)), onSync = { _, _ -> }, onAdd = {}, onSignIn = {}) }

        compose.onNodeWithText("The remote refused the sign-in, or the repo doesn't exist.").assertExists()
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
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
    fun keepsPicksMadeBeforeASyncThatAsksTheSame() {
        val photo = Choice("photo.png", local = "a1", remote = "b2")
        val deleted = Choice("old.md", local = "c3", remote = null)
        val asked = SyncState.Finished(SyncResult.Choose(listOf(photo, deleted)))
        var state by mutableStateOf<SyncState>(asked)
        val synced = mutableListOf<Map<Choice, Keep>>()
        compose.setContent { ReposScreen(listOf(RepoRow(vault, state)), onSync = { _, choices -> synced += choices }, onAdd = {}) }

        compose.onAllNodesWithText("Keep this phone's")[0].performClick()
        state = SyncState.Running(Step.FETCH)
        compose.waitForIdle()
        state = asked
        compose.onAllNodesWithText("Keep the remote's")[1].performClick()
        compose.onNodeWithText("Sync with these choices").performClick()

        assertEquals(listOf(mapOf(photo to Keep.LOCAL, deleted to Keep.REMOTE)), synced)
    }

    @Test
    fun showsWhatEachSideOfAChoiceHolds() {
        val photo = Choice("photo.png", local = "a1", remote = "b2")
        val deleted = Choice("old.md", local = "c3", remote = null)
        val notes = Choice("notes.md", local = "d4", remote = "e5")
        val folder = Choice("drafts", local = "f6", remote = "0123456789")
        val previews = mapOf(
            photo to ChoicePreview(Version.Image(100) { png() }, Version.Binary(2048)),
            deleted to ChoicePreview(Version.Text(listOf("phone's note"), 3), Version.Deleted),
            notes to ChoicePreview(
                Version.Text(listOf("one", "phone"), 2),
                Version.Text(listOf("one", "remote"), 2),
                listOf(DiffLine(null, "one"), DiffLine(Keep.REMOTE, "remote line"), DiffLine(Keep.LOCAL, "phone line")),
            ),
            folder to ChoicePreview(Version.Folder(3), Version.Elsewhere("0123456789")),
        )
        val asked = SyncState.Finished(SyncResult.Choose(previews.keys.toList()))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, asked)), onSync = { _, _ -> }, onAdd = {}, preview = { _, choice -> previews[choice] }) }

        // Previews are read, and images decoded, off the main thread, which the test's idling does not wait for
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("This phone: image").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Changed on this phone and on the remote").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("This phone has it, the remote deleted it").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("A file of 2.0 kB").assertExists()
        compose.onNodeWithText("phone's note").assertExists()
        compose.onNodeWithText("and 2 more lines").assertExists()
        compose.onNodeWithText("Deleted").assertExists()
        compose.onNodeWithText("remote line").assertExists()
        compose.onNodeWithText("phone line").assertExists()
        compose.onAllNodesWithText("2 lines").assertCountEquals(2)
        compose.onNodeWithText("A folder of 3 files").assertExists()
        compose.onNodeWithText("Version 0123456, kept in a submodule").assertExists()
    }

    @Test
    fun saysWhatEachSideHoldsWithoutAPreview() {
        val deleted = Choice("old.md", local = "c3", remote = null)
        val asked = SyncState.Finished(SyncResult.Choose(listOf(deleted)))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, asked)), onSync = { _, _ -> }, onAdd = {}, preview = { _, _ -> null }) }

        compose.onNodeWithText("This phone has it, the remote deleted it").assertExists()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun countsWhatTheNextSyncPushesAndOpensIntoTheFiles() {
        val pending = FileChanges(changed = listOf("ideas.md", "daily/today.md"), added = listOf("new.md"), deleted = emptyList())
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null, pending = pending)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("new.md").assertDoesNotExist()
        compose.onNodeWithText("To sync: 2 changed, 1 new ▾").performClick()

        compose.onNodeWithText("To sync: 2 changed, 1 new ▴").assertExists()
        compose.onNodeWithText("daily/today.md").assertExists()
        compose.onNodeWithText("new.md").assertExists()
        compose.onNodeWithText("deleted").assertDoesNotExist()
    }

    @Test
    fun opensTheSyncHistoryFromTheMenu() {
        val opened = mutableListOf<SavedRepo>()
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null)), onSync = { _, _ -> }, onAdd = {}, onHistory = { opened += it }) }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sync history").performClick()

        assertEquals(listOf(vault), opened)
    }

    @Test
    fun saysWhenThereIsNothingToPush() {
        val nothing = FileChanges(emptyList(), emptyList(), emptyList())
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null, pending = nothing)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("No changes on this phone to sync").assertExists()
    }

    @Test
    fun syncsEveryRepo() {
        val asking = SyncState.Finished(SyncResult.Choose(listOf(Choice("photo.png", "a1", "b2"))))
        var syncs = 0
        val rows = listOf(RepoRow(vault, null), RepoRow(journal, SyncState.Running(Step.FETCH)), RepoRow(photos, asking))
        compose.setContent { ReposScreen(rows, onSync = { _, _ -> }, onAdd = {}, onSyncAll = { syncs++ }) }

        // Whatever each repo's state, as the sync of each starts once the one before it is done
        compose.onNodeWithText("Sync all").assertIsEnabled()
        compose.onNodeWithText("Sync all").performClick()

        assertEquals(1, syncs)
    }

    @Test
    fun offersNoSyncOfAllForOneRepo() {
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null)), onSync = { _, _ -> }, onAdd = {}) }

        compose.onNodeWithText("Sync all").assertDoesNotExist()
    }

    @Test
    fun saysHowFarTheSyncOfAllGot() {
        val all = AllRun(listOf("1", "2", "3"), mapOf("1" to SyncResult.Synced(1, 0)), going = true)
        val rows = listOf(RepoRow(vault, null), RepoRow(journal, SyncState.Running(Step.PUSH)), RepoRow(photos, SyncState.Waiting))
        compose.setContent { ReposScreen(rows, onSync = { _, _ -> }, onAdd = {}, all = all) }

        compose.onNodeWithText("Syncing every repo (1/3)").assertExists()
        compose.onNodeWithText("Syncing…").assertExists()
        compose.onNodeWithText("Waiting").assertExists()
        compose.onNodeWithText("Sync all").assertIsNotEnabled()
        compose.onNodeWithText("Close").assertDoesNotExist()
    }

    @Test
    fun cancelsTheSyncOfAll() {
        val all = AllRun(listOf("1", "2"), mapOf("1" to SyncResult.Cancelled), going = true)
        var cancelled = 0
        compose.setContent {
            ReposScreen(listOf(RepoRow(vault, null), RepoRow(journal, SyncState.Waiting)), onSync = { _, _ -> }, onAdd = {}, all = all, onCancelAll = { cancelled++ })
        }

        compose.onNodeWithText("Stopped").assertExists()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancelled)
    }

    @Test
    fun sumsUpTheSyncOfAllWithItsOwnResults() {
        val all = AllRun(listOf("1", "2"), mapOf("1" to SyncResult.UpToDate, "2" to SyncResult.Failed(Problem.FETCH, "no network")))
        // A sync since, such as an automatic one, changes the repo's card but not the run's result
        val rows = listOf(RepoRow(vault, SyncState.Running(Step.FETCH)), RepoRow(journal, SyncState.Finished(SyncResult.UpToDate)))
        var closed = 0
        compose.setContent { ReposScreen(rows, onSync = { _, _ -> }, onAdd = {}, all = all, onCloseAll = { closed++ }) }

        compose.onNodeWithText("1 of 2 repos needs you").assertExists()
        compose.onNodeWithText("Failed").assertExists()
        compose.onNodeWithText("Sync all").assertIsEnabled()
        compose.onNodeWithText("Close").performClick()

        assertEquals(1, closed)
    }

    @Test
    fun saysWhichReposTheSyncOfAllNeverReached() {
        val all = AllRun(listOf("1", "2"), mapOf("1" to SyncResult.Synced(0, 1)))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null), RepoRow(journal, null)), onSync = { _, _ -> }, onAdd = {}, all = all) }

        compose.onNodeWithText("1 of 2 repos needs you").assertExists()
        compose.onNodeWithText("Didn't sync").assertExists()
    }

    @Test
    fun saysWhenTheSyncOfAllSyncedEveryRepo() {
        val all = AllRun(listOf("1", "2"), mapOf("1" to SyncResult.Synced(0, 1), "2" to SyncResult.UpToDate))
        compose.setContent { ReposScreen(listOf(RepoRow(vault, null), RepoRow(journal, null)), onSync = { _, _ -> }, onAdd = {}, all = all) }

        compose.onNodeWithText("Every repo synced").assertExists()
    }

    @Test
    fun invitesToAddTheFirstRepo() {
        var added = false
        compose.setContent { ReposScreen(emptyList(), onSync = { _, _ -> }, onAdd = { added = true }) }

        compose.onNodeWithText("Add a repo").performClick()

        assertEquals(true, added)
    }
}
