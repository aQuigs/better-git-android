package com.sqftware.safegit.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.about.BundledPackage
import com.sqftware.safegit.about.Licenses
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val licenses = Licenses(
        listOf(BundledPackage("git", "2.56.0", "GNU GENERAL PUBLIC LICENSE"), BundledPackage("zlib", "1.3.2", "Copyright notice: zlib")),
        apache = "Apache License, Version 2.0",
    )

    @Test
    fun listsEachPackageAndOpensItsLicense() {
        compose.setContent { AboutScreen("0.1.0 (7)", licenses, onOpen = {}) }

        compose.onNodeWithText("2.56.0").assertExists()
        compose.onNodeWithText("zlib").performClick()

        compose.onNodeWithText("Copyright notice: zlib").assertExists()
    }

    @Test
    fun opensTheAppLibrariesLicense() {
        compose.setContent { AboutScreen("0.1.0 (7)", licenses, onOpen = {}) }

        scrollTo("Jetpack Compose, AndroidX and Kotlin")
        compose.onNodeWithText("Jetpack Compose, AndroidX and Kotlin").performClick()

        compose.onNodeWithText("Apache License, Version 2.0").assertExists()
    }

    @Test
    fun asksForTheSourceOfThisVersion() {
        val opened = mutableListOf<String>()
        compose.setContent { AboutScreen("0.1.0 (7)", licenses, onOpen = { opened += it }) }

        scrollTo("Ask for the source")
        compose.onNodeWithText("Ask for the source").performClick()

        assertEquals(listOf("mailto:sqftware@gmail.com?subject=SafeGit%200.1.0%20(7)%3A%20source%20code%20request"), opened)
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }
}
