package com.sqftware.safegit.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.sync.ChoicePreview
import com.sqftware.safegit.sync.DiffLine
import com.sqftware.safegit.sync.FileHistory
import com.sqftware.safegit.sync.FileVersions
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.PastVersion
import com.sqftware.safegit.sync.Version
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VersionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val newest = PastVersion("a".repeat(40), 3_000_000, "Ann", "Changes from Pixel", "b2")
    private val older = PastVersion("b".repeat(40), 2_000_000, "Ann", "Laptop edit", "b1")
    private val deleted = PastVersion("c".repeat(40), 1_000_000, "Ann", "Tidy up", null)
    private val versions = FileVersions(current = "b2", listOf(newest, older, deleted))
    private val preview = ChoicePreview(
        Version.Text(listOf("new"), 1),
        Version.Text(listOf("old"), 1),
        listOf(DiffLine(Keep.REMOTE, "old"), DiffLine(Keep.LOCAL, "new")),
    )

    @Test
    fun listsEachVersionWithWhoMadeItAndMarksTheCurrentOne() {
        compose.setContent { VersionsScreen("notes/day.md", versions, preview = { preview }, onRestore = {}) }

        compose.onNodeWithText("Current").assertExists()
        compose.onNodeWithText("Ann · Changes from Pixel ▾").assertExists()
        compose.onNodeWithText("Ann · Laptop edit ▾").assertExists()
        compose.onNodeWithText("Ann · Tidy up").assertExists()
        compose.onNodeWithText("Deleted").assertExists()
    }

    @Test
    fun opensAVersionIntoItsDifferenceAndRestoresIt() {
        var restored: PastVersion? = null
        compose.setContent { VersionsScreen("notes/day.md", versions, preview = { preview }, onRestore = { restored = it }) }

        compose.onNodeWithText("Ann · Laptop edit ▾").performClick()

        compose.onNodeWithText("old").assertExists()
        compose.onNodeWithText("This version").assertExists()
        compose.onNodeWithText("Restore this version").performClick()
        assertEquals(older, restored)
    }

    @Test
    fun offersNoRestoreOfTheCurrentVersion() {
        compose.setContent { VersionsScreen("notes/day.md", versions, preview = { preview }, onRestore = {}) }

        compose.onNodeWithText("Ann · Changes from Pixel ▾").performClick()

        compose.onNodeWithText("This version").assertExists()
        compose.onNodeWithText("Restore this version").assertDoesNotExist()
    }

    @Test
    fun offersNoRestoreUntilAConflictIsResolved() {
        compose.setContent { VersionsScreen("notes/day.md", versions.copy(held = true), preview = { preview }, onRestore = {}) }

        compose.onNodeWithText(
            "This repo has a conflict to resolve. Edit the conflicted files and sync first, so this phone's changes are kept, then restore.",
        ).assertExists()
        compose.onNodeWithText("Ann · Laptop edit ▾").performClick()
        compose.onNodeWithText("This version").assertExists()
        compose.onNodeWithText("Restore this version").assertDoesNotExist()
    }

    @Test
    fun saysToSyncFirstWhenTheFileHasChanged() {
        val outcome = RestoreOutcome.Ended(FileHistory.Restored.CHANGED)
        compose.setContent { VersionsScreen("notes/day.md", versions, preview = { preview }, onRestore = {}, outcome = outcome) }

        compose.onNodeWithText(
            "This file has changed since you opened its versions, or since the last sync. Sync first, so that change is kept too, then restore.",
        ).assertExists()
    }

    @Test
    fun saysWhenTheVersionsCannotBeRead() {
        compose.setContent { VersionsScreen("notes/day.md", null, preview = { null }, onRestore = {}, unreadable = "git log failed") }

        compose.onNodeWithText("Couldn't read this file's versions: git log failed").assertExists()
    }

    @Test
    fun saysWhenAFileHasNoVersionsYet() {
        compose.setContent { VersionsScreen("new.md", FileVersions(null, emptyList()), preview = { null }, onRestore = {}) }

        compose.onNodeWithText("No versions yet. A file has versions once a sync has committed it.").assertExists()
    }
}
