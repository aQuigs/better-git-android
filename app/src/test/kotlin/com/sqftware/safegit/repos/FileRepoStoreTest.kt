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

    @Test
    fun keepsTheReposForTheNextStart() = runBlocking {
        val file = temp.root.resolve("repos.json")
        val notes = SavedRepo("a", "https://example.com/notes.git", File("/storage/emulated/0/Documents/Notes"))
        val work = SavedRepo("b", "https://example.com/work.git", File("/storage/emulated/0/Work"))
        FileRepoStore(file).apply {
            save(notes)
            save(work)
            save(notes.copy(url = "https://example.com/moved.git"))
        }

        assertEquals(listOf(notes.copy(url = "https://example.com/moved.git"), work), FileRepoStore(file).all.first())
    }

    @Test
    fun keepsHowOftenEachSyncsOnItsOwn() = runBlocking {
        val file = temp.root.resolve("repos.json")
        val notes = SavedRepo("a", "https://example.com/notes.git", File("/storage/emulated/0/Documents/Notes"), AutoSync.HOURLY)
        FileRepoStore(file).save(notes)

        assertEquals(listOf(notes), FileRepoStore(file).all.first())
    }

    @Test
    fun syncsOnlyWhenAskedARepoSavedBeforeAutomaticSyncs() = runBlocking {
        val file = temp.root.resolve("repos.json")
        file.writeText("""[{"id":"a","url":"https://example.com/notes.git","folder":"/sdcard/Notes"}]""")

        assertEquals(AutoSync.OFF, FileRepoStore(file).all.first().single().auto)
    }
}
