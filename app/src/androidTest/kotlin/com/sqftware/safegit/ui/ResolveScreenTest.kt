package com.sqftware.safegit.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.sync.MarkedFile
import com.sqftware.safegit.sync.MarkedFile.Part
import com.sqftware.safegit.sync.MarkedFiles.Saved
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResolveScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val file = MarkedFile(
        "ideas.md",
        listOf(
            Part.Same("# Ideas\n"),
            block("phone idea\n", "laptop idea\n"),
            Part.Same("middle\n"),
            block("phone end\n", "laptop end\n"),
        ),
        stray = false,
        ByteArray(0),
    )

    @Test
    fun showsEachConflictWithBothSides() {
        show(file)

        compose.onNodeWithText("Conflict 1 of 2").assertExists()
        compose.onNodeWithText("phone idea").assertExists()
        compose.onNodeWithText("laptop idea").assertExists()
        compose.onNodeWithText("# Ideas").assertExists()
        compose.onNodeWithText("Save these").assertIsNotEnabled()
    }

    @Test
    fun savesEachBlockAsPickedAndLeavesTheRestMarked() {
        val saved = mutableListOf<String>()
        var done = false
        show(file, save = { _, text -> saved += text; Saved.Done }, onDone = { done = true })

        compose.onAllNodesWithText("Keep the remote's")[0].performClick()
        compose.onNodeWithText("Save these").performClick()
        compose.waitUntil { done }

        assertEquals(listOf("# Ideas\nlaptop idea\nmiddle\n<<<<<<< Pixel\nphone end\n=======\nlaptop end\n>>>>>>> remote\n"), saved)
    }

    @Test
    fun keepsBothOrAnEditOfThem() {
        val saved = mutableListOf<String>()
        var done = false
        show(file, save = { _, text -> saved += text; Saved.Done }, onDone = { done = true })

        compose.onAllNodesWithText("Keep both")[0].performClick()
        compose.onAllNodesWithText("Edit")[1].performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("the end")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil { done }

        assertEquals(listOf("# Ideas\nphone idea\nlaptop idea\nmiddle\nthe end\n"), saved)
    }

    @Test
    fun anEditStartsFromBothSides() {
        show(file)

        compose.onAllNodesWithText("Edit")[0].performClick()

        compose.onNode(hasSetTextAction()).assert(hasText("phone idea\nlaptop idea\n"))
    }

    @Test
    fun saysTheFileChangedAndOpensItAgain() {
        var opens = 0
        show(file, open = { opens++; file }, save = { _, _ -> Saved.Changed })

        compose.onAllNodesWithText("Keep this phone's")[0].performClick()
        compose.onNodeWithText("Save these").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Open again").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save these").assertIsNotEnabled()
        compose.onNodeWithText("Open again").performClick()

        compose.waitUntil { opens == 2 }
        compose.waitUntil { compose.onAllNodesWithText("Open again").fetchSemanticsNodes().isEmpty() }
        // The block is still there, so its pick is too
        compose.onNodeWithText("Save these").assertIsEnabled()
    }

    @Test
    fun saysWhenMarkersAreLeftThatItCannotResolve() {
        show(MarkedFile("ideas.md", listOf(Part.Same("<<<<<<< Pixel\nhalf\n")), stray = true, ByteArray(0)))

        compose.waitUntil { compose.onAllNodesWithText("Edit them in another app", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun showsALongSideCutShortUntilAllIsAskedFor() {
        val phone = (1..20).joinToString("") { "phone $it\n" }
        val remote = (1..15).joinToString("") { "remote $it\n" }
        show(MarkedFile("ideas.md", listOf(block(phone, remote)), stray = false, ByteArray(0)))

        compose.onNodeWithText("phone 12", substring = true).assertExists()
        compose.onNodeWithText("phone 13", substring = true).assertDoesNotExist()
        compose.onNodeWithText("remote 15", substring = true).assertExists()
        compose.onNodeWithText("Show all 20 lines").performClick()

        compose.onNodeWithText("phone 20", substring = true).assertExists()
        compose.onNodeWithText("Show fewer").assertExists()
    }

    @Test
    fun saysWhenNoConflictIsLeft() {
        show(MarkedFile("ideas.md", listOf(Part.Same("done\n")), stray = false, ByteArray(0)))

        compose.waitUntil { compose.onAllNodesWithText("No conflicts left").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun saysWhenTheFileCannotBeResolvedHere() {
        compose.setContent { ResolveScreen("photo.png", open = { null }, save = { _, _ -> Saved.Done }, onDone = {}) }

        compose.waitUntil { compose.onAllNodesWithText("Edit it in another app", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun show(file: MarkedFile, open: () -> MarkedFile = { file }, save: (MarkedFile, String) -> Saved = { _, _ -> Saved.Done }, onDone: () -> Unit = {}) {
        compose.setContent { ResolveScreen(file.path, open, save, onDone) }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Conflict 1 of", substring = true).fetchSemanticsNodes().isNotEmpty() || file.blocks.isEmpty() }
    }

    private fun block(local: String, remote: String) =
        Part.Block(local, remote, "<<<<<<< Pixel\n$local=======\n$remote>>>>>>> remote\n", "\n")
}
