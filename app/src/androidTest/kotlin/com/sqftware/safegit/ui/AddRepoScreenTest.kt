package com.sqftware.safegit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.repos.AccountRepos
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.repos.HostedRepo
import com.sqftware.safegit.repos.RemoteBranches
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AddRepoScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val notes = File("/storage/emulated/0/Documents/notes")
    private val connected = mutableListOf<Pair<String, String?>>()

    @Test
    fun connectsOnceItHasAUrlAndAFolder() {
        compose.setContent { Screen() }

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://github.com/me/notes.git" to null), connected)
    }

    @Test
    fun picksOneOfTheAccountsRepos() {
        val repos = listOf(HostedRepo("me/notes", "https://github.com/me/notes.git"), HostedRepo("me/blog", "https://github.com/me/blog.git"))
        compose.setContent { Screen(choices = AccountRepos.State.Loaded(repos)) }

        compose.onNodeWithText("Repo URL").performTextInput("NOT")
        compose.onNodeWithText("me/blog").assertDoesNotExist()
        compose.onNodeWithText("me/notes").performClick()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://github.com/me/notes.git" to null), connected)
    }

    @Test
    fun takesAPastedUrlWhenTheReposCannotBeListed() {
        compose.setContent { Screen(choices = AccountRepos.State.Failed) }

        compose.onNodeWithText("Couldn't list your GitHub repos. Paste the URL instead.").assertExists()
        compose.onNodeWithText("Repo URL").performTextInput("https://example.com/notes.git")
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://example.com/notes.git" to null), connected)
    }

    @Test
    fun connectsToTheDefaultBranchUnlessAnotherIsPicked() {
        compose.setContent { Screen(branchesOf = { RemoteBranches.State.Listed(it, listOf("main", "drafts"), default = "main") }) }

        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("main (default)").performClick()
        compose.onNodeWithText("drafts").performClick()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://github.com/me/notes.git" to "main", "https://github.com/me/notes.git" to "drafts"), connected)
    }

    @Test
    fun waitsForTheBranchesBeforeConnecting() {
        var loaded by mutableStateOf(false)
        compose.setContent {
            Screen(branchesOf = { if (loaded) RemoteBranches.State.Listed(it, listOf("main"), "main") else RemoteBranches.State.Loading(it) })
        }

        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        loaded = true

        compose.onNodeWithText("Connect").assertIsEnabled()
    }

    @Test
    fun neverTakesAnotherUrlsBranches() {
        compose.setContent { Screen(branchesOf = { RemoteBranches.State.Listed("https://github.com/me/blog.git", listOf("drafts"), "drafts") }) }

        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("drafts (default)").assertDoesNotExist()
    }

    @Test
    fun asksForAPickWhenTheRemoteNamesNoDefault() {
        compose.setContent { Screen(branchesOf = { RemoteBranches.State.Listed(it, listOf("archive", "drafts"), default = null) }) }

        compose.onNodeWithText("Repo URL").performTextInput("https://example.com/notes.git")
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Pick a branch").assertExists()
        compose.onNodeWithText("Branch").performClick()
        compose.onNodeWithText("drafts").performClick()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://example.com/notes.git" to "drafts"), connected)
    }

    @Test
    fun forgetsThePickWhenTheUrlChanges() {
        compose.setContent { Screen(branchesOf = { RemoteBranches.State.Listed(it, listOf("main", "drafts"), default = "main") }) }

        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")
        compose.onNodeWithText("main (default)").performClick()
        compose.onNodeWithText("drafts").performClick()
        compose.onNodeWithText("https://github.com/me/notes.git").performTextClearance()
        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/blog.git")
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://github.com/me/blog.git" to "main"), connected)
    }

    @Test
    fun connectsToTheDefaultBranchWhenTheBranchesCannotBeListed() {
        compose.setContent { Screen(branchesOf = { RemoteBranches.State.Failed(it) }) }

        compose.onNodeWithText("Repo URL").performTextInput("https://example.com/notes.git")
        compose.onNodeWithText("Couldn't list this repo's branches. Connecting takes its default branch.").assertExists()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(listOf("https://example.com/notes.git" to null), connected)
    }

    @Test
    fun saysToSignInAgainWhenGitHubRefusesIt() {
        compose.setContent { Screen(choices = AccountRepos.State.SignInRefused) }

        compose.onNodeWithText("GitHub no longer accepts this sign-in. Sign out and sign in again.").assertExists()
    }

    @Test
    fun needsAFolder() {
        compose.setContent { Screen(folder = null) }

        compose.onNodeWithText("Repo URL").performTextInput("https://github.com/me/notes.git")

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("No folder chosen").assertExists()
    }

    @Test
    fun saysWhyItWasRefused() {
        compose.setContent { Screen(state = Connector.State.Refused(Connector.Refusal.FOLDER_OVERLAPS)) }

        compose.onNodeWithText("This folder is, holds or sits inside a folder that is already connected.").assertExists()
    }

    /** The screen with [branchesOf] the URL it last reported, as the app passes them once listed. */
    @Composable
    private fun Screen(
        folder: File? = notes,
        state: Connector.State = Connector.State.Idle,
        choices: AccountRepos.State = AccountRepos.State.None,
        branchesOf: (String) -> RemoteBranches.State = { RemoteBranches.State.None(it) },
    ) {
        var url by remember { mutableStateOf("") }
        AddRepoScreen(
            folder, folderUnusable = false, state, onPickFolder = {}, onConnect = { repo, branch -> connected += repo to branch }, onCancel = {},
            choices = choices, branches = branchesOf(url), onUrlChange = { url = it },
        )
    }
}
