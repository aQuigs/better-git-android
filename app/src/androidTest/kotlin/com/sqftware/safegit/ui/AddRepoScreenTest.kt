package com.sqftware.safegit.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AddRepoScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectsOnceItHasAUrlAndAFolder() {
        val connected = mutableListOf<String>()
        compose.setContent {
            AddRepoScreen(File("/storage/emulated/0/Documents/notes"), connecting = false, error = null, onPickFolder = {}, onConnect = { connected += it }, onCancel = {})
        }

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://github.com/me/notes.git"), connected)
    }

    @Test
    fun needsAFolder() {
        compose.setContent { AddRepoScreen(null, connecting = false, error = null, onPickFolder = {}, onConnect = {}, onCancel = {}) }

        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("No folder chosen").assertExists()
    }
}
