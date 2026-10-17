package com.sqftware.safegit.repos

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRepoStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val file get() = temp.root.resolve("repos.json")

    @Test
    fun keepsTheReposForTheNextStart() = runBlocking {
        val notes = SavedRepo("a", "https://example.com/notes.git", File("/storage/emulated/0/Documents/Notes"), "main")
        val work = SavedRepo("b", "https://example.com/work.git", File("/storage/emulated/0/Work"), "drafts")
        store().apply {
            save(notes)
            save(work)
            save(notes.copy(url = "https://example.com/moved.git"))
        }

        assertEquals(listOf(notes.copy(url = "https://example.com/moved.git"), work), store().all.first())
    }

    @Test
    fun keepsHowOftenEachSyncsOnItsOwn() = runBlocking {
        val notes = SavedRepo("a", "https://example.com/notes.git", File("/storage/emulated/0/Documents/Notes"), "main", AutoSync.HOURLY)
        store().save(notes)

        assertEquals(listOf(notes), store().all.first())
    }

    @Test
    fun syncsOnlyWhenAskedARepoSavedBeforeAutomaticSyncs() = runBlocking {
        file.writeText("""[{"id":"a","url":"https://example.com/notes.git","folder":"/sdcard/Notes"}]""")

        assertEquals(AutoSync.OFF, FileRepoStore(file) { "main" }.all.first().single().auto)
    }

    @Test
    fun readsTheBranchOfARepoSavedBeforeBranchesWere() = runBlocking {
        file.writeText("""[{"id":"a","url":"https://example.com/notes.git","folder":"/sdcard/Notes"}]""")

        assertEquals("trunk", FileRepoStore(file) { id -> if (id == "a") "trunk" else "" }.all.first().single().branch)
    }

    private fun store() = FileRepoStore(file) { error("Every repo here was saved with its branch") }
}
