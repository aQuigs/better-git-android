package com.sqftware.safegit.work

import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.FileChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OutgoingTest {
    private val notes = SavedRepo("1", "https://github.com/me/notes.git", File("/storage/emulated/0/Documents/notes"), "main")
    private val work = SavedRepo("2", "https://github.com/me/work.git", File("/storage/emulated/0/Documents/work"), "main")
    private val store = object : RepoStore {
        override val all: Flow<List<SavedRepo>> = flowOf(listOf(notes, work))

        override fun save(repo: SavedRepo) = Unit
    }
    private val one = FileChanges(listOf("ideas.md"), emptyList(), emptyList())
    private val two = FileChanges(listOf("ideas.md", "today.md"), emptyList(), emptyList())
    private val reads = mutableListOf<String>()
    private val answers = mutableMapOf<String, () -> FileChanges?>(notes.id to { one }, work.id to { null })

    // Unconfined runs each read before the call asking for it returns
    private val outgoing = Outgoing(store, { reads += it.id; answers.getValue(it.id)() }, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun readsEveryRepoAsTheAppComesToTheFront() {
        outgoing.show()

        assertEquals(mapOf(notes.id to one), outgoing.pending.value)
    }

    @Test
    fun readsNothingAgainUntilTheAppHasBeenAway() {
        outgoing.show()
        outgoing.show()
        outgoing.hide()
        outgoing.show()

        assertEquals(listOf(notes.id, work.id, notes.id, work.id), reads)
    }

    @Test
    fun readsARepoAgainAfterItSyncsWhileShown() {
        outgoing.show()
        answers[notes.id] = { two }

        outgoing.changed(notes)

        assertEquals(mapOf(notes.id to two), outgoing.pending.value)
    }

    @Test
    fun forgetsARepoThatSyncedOutOfSight() {
        outgoing.show()
        outgoing.hide()
        reads.clear()

        outgoing.changed(notes)

        assertEquals(emptyMap<String, FileChanges>(), outgoing.pending.value)
        assertEquals(emptyList<String>(), reads)
    }

    @Test
    fun dropsACountItCouldNotReadAgain() {
        outgoing.show()
        answers[notes.id] = { error("killed") }

        outgoing.changed(notes)

        assertEquals(emptyMap<String, FileChanges>(), outgoing.pending.value)
    }
}
