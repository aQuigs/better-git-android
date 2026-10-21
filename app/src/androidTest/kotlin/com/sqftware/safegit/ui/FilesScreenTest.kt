package com.sqftware.safegit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.sync.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val files = RepoFiles(listOf("ideas.md", "notes/day.md"), listOf("notes/old.md", "gone/only.md"))
    private var opened: String? = null

    @Test
    fun browsesFoldersAndOpensAFile() {
        compose.setContent { Files(files) }

        compose.onNodeWithText("ideas.md").assertExists()
        compose.onNodeWithText("notes/").performClick()
        compose.onNodeWithText("day.md").assertExists()
        compose.onNodeWithText("old.md").performClick()
        assertEquals("notes/old.md", opened)

        compose.onNodeWithText("‹ Notes").performClick()
        compose.onNodeWithText("ideas.md").assertExists()
    }

    @Test
    fun marksDeletedFilesAndFoldersThatHoldOnlyThose() {
        compose.setContent { Files(RepoFiles(emptyList(), listOf("gone/only.md", "old.md"))) }

        compose.onNodeWithText("gone/").assertExists()
        compose.onNodeWithText("old.md").assertExists()
        assertEquals(2, compose.onAllNodesWithText("Deleted").fetchSemanticsNodes().size)
    }

    @Test
    fun searchesEveryFolder() {
        compose.setContent { Files(files) }

        compose.onNodeWithText("Search files").performTextInput("DAY")

        compose.onNodeWithText("ideas.md").assertDoesNotExist()
        compose.onNodeWithText("notes/day.md").performClick()
        assertEquals("notes/day.md", opened)
    }

    @Test
    fun saysWhenNothingMatches() {
        compose.setContent { Files(files) }

        compose.onNodeWithText("Search files").performTextInput("zzz")

        compose.onNodeWithText("No matching files").assertExists()
    }

    @Test
    fun saysWhenTheFilesCannotBeRead() {
        compose.setContent { FilesScreen("Notes", null, "", "", onQuery = {}, onFolder = {}, onFile = {}, unreadable = "git failed") }

        compose.onNodeWithText("Couldn't read the files: git failed").assertExists()
    }

    @Composable
    private fun Files(files: RepoFiles) {
        var folder by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        FilesScreen("Notes", files, folder, query, onQuery = { query = it }, onFolder = { folder = it }, onFile = { opened = it })
    }
}
