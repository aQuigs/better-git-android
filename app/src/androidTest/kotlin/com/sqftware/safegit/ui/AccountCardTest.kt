package com.sqftware.safegit.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.auth.Account
import com.sqftware.safegit.auth.DeviceCode
import com.sqftware.safegit.auth.SignIn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTheCodeToEnterOnGitHub() {
        val code = DeviceCode("WDJB-MJHT", "https://github.com/login/device", "dev123", 5, 900)
        val opened = mutableListOf<DeviceCode>()
        compose.setContent { AccountCard(SignIn.State.Waiting(code), onSignIn = {}, onOpen = { opened += it }, onCancel = {}, onSignOut = {}) }

        compose.onNodeWithText("WDJB-MJHT").assertExists()
        compose.onNodeWithText("Copy code and open GitHub").performClick()

        assertEquals(listOf(code), opened)
    }

    @Test
    fun showsWhoIsSignedIn() {
        var signedOut = false
        compose.setContent {
            AccountCard(SignIn.State.SignedIn(Account("octo", 42, null)), onSignIn = {}, onOpen = {}, onCancel = {}, onSignOut = { signedOut = true })
        }

        compose.onNodeWithText("Signed in to GitHub as @octo").assertExists()
        compose.onNodeWithText("Sign out").performClick()

        assertEquals(true, signedOut)
    }
}
