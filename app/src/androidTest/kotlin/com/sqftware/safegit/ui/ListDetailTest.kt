package com.sqftware.safegit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListDetailTest {
    @get:Rule
    val compose = createComposeRule()

    private val empty = "History, files and conflicts open here."

    // Required, as the test device's own screen may be narrower than the window the test plays
    private fun show(width: Dp, height: Dp = 800.dp, detail: (@Composable () -> Unit)?) = compose.setContent {
        Box(Modifier.requiredSize(width, height)) { ListDetail(list = { Text("repos") }, detail) }
    }

    @Test
    fun showsOnePaneOnAPhoneOnItsSide() {
        show(900.dp, height = 400.dp) { Text("history") }

        compose.onNodeWithText("repos").assertDoesNotExist()
        compose.onNodeWithText("history").assertExists()
    }

    @Test
    fun showsTheDetailBesideTheListOnAWideWindow() {
        show(900.dp) { Text("history") }

        compose.onNodeWithText("repos").assertExists()
        compose.onNodeWithText("history").assertExists()
    }

    @Test
    fun saysWhatOpensBesideTheListBeforeAnythingIsOpen() {
        show(900.dp, detail = null)

        compose.onNodeWithText("repos").assertExists()
        compose.onNodeWithText(empty).assertExists()
    }

    @Test
    fun showsTheDetailInPlaceOfTheListOnANarrowWindow() {
        show(400.dp) { Text("history") }

        compose.onNodeWithText("repos").assertDoesNotExist()
        compose.onNodeWithText("history").assertExists()
    }

    @Test
    fun showsOnlyTheListOnANarrowWindowWithNothingOpen() {
        show(400.dp, detail = null)

        compose.onNodeWithText("repos").assertExists()
        compose.onNodeWithText(empty).assertDoesNotExist()
    }
}
