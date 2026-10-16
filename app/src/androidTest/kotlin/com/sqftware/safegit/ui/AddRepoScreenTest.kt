package com.sqftware.safegit.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.repos.AccountRepos
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.repos.HostedRepo
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
            AddRepoScreen(File("/storage/emulated/0/Documents/notes"), folderUnusable = false, Connector.State.Idle, onPickFolder = {}, onConnect = { connected += it }, onCancel = {})
        }

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://github.com/me/notes.git"), connected)
    }

    @Test
    fun picksOneOfTheAccountsRepos() {
        val connected = mutableListOf<String>()
        val repos = listOf(HostedRepo("me/notes", "https://github.com/me/notes.git"), HostedRepo("me/blog", "https://github.com/me/blog.git"))
        compose.setContent {
            AddRepoScreen(
                File("/storage/emulated/0/Documents/notes"), folderUnusable = false, Connector.State.Idle,
                onPickFolder = {}, onConnect = { connected += it }, onCancel = {}, choices = AccountRepos.State.Loaded(repos),
            )
        }

        compose.onNodeWithText("Repo URL").performTextInput("NOT")
        compose.onNodeWithText("me/blog").assertDoesNotExist()
        compose.onNodeWithText("me/notes").performClick()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://github.com/me/notes.git"), connected)
    }

    @Test
    fun takesAPastedUrlWhenTheReposCannotBeListed() {
        val connected = mutableListOf<String>()
        compose.setContent {
            AddRepoScreen(
                File("/storage/emulated/0/Documents/notes"), folderUnusable = false, Connector.State.Idle,
                onPickFolder = {}, onConnect = { connected += it }, onCancel = {}, choices = AccountRepos.State.Failed,
            )
        }

        compose.onNodeWithText("Couldn't list your GitHub repos. Paste the URL instead.").assertExists()
        compose.onNodeWithText("Repo URL").performTextInput("https://example.com/notes.git")
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://example.com/notes.git"), connected)
    }

    @Test
    fun saysToSignInAgainWhenGitHubRefusesIt() {
        compose.setContent {
            AddRepoScreen(File("/storage/emulated/0/Documents/notes"), folderUnusable = false, Connector.State.Idle, {}, {}, {}, AccountRepos.State.SignInRefused)
        }

        compose.onNodeWithText("GitHub no longer accepts this sign-in. Sign out and sign in again.").assertExists()
    }

    @Test
    fun needsAFolder() {
        compose.setContent { AddRepoScreen(null, folderUnusable = false, Connector.State.Idle, onPickFolder = {}, onConnect = {}, onCancel = {}) }

        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("No folder chosen").assertExists()
    }

    @Test
    fun saysWhyItWasRefused() {
        compose.setContent {
            AddRepoScreen(File("/storage/emulated/0/Documents/notes"), folderUnusable = false, Connector.State.Refused(Connector.Refusal.FOLDER_OVERLAPS), {}, {}, {})
        }

        compose.onNodeWithText("This folder is, holds or sits inside a folder that is already connected.").assertExists()
    }
}
