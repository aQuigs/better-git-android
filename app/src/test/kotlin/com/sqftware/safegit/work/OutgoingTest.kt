package com.sqftware.safegit.work

import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Pending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OutgoingTest {
    private val notes = SavedRepo("1", "https://github.com/me/notes.git", File("/storage/emulated/0/Documents/notes"))
    private val work = SavedRepo("2", "https://github.com/me/work.git", File("/storage/emulated/0/Documents/work"))
    private val store = object : RepoStore {
        override val all: Flow<List<SavedRepo>> = flowOf(listOf(notes, work))

        override fun save(repo: SavedRepo) = Unit
    }
    private val one = Pending(listOf("ideas.md"), emptyList(), emptyList())
    private val reads = mutableMapOf<String, () -> Pending?>(notes.id to { one }, work.id to { null })

    // Unconfined runs each read before refresh returns
    private val outgoing = Outgoing(store, { reads.getValue(it.id)() }, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun readsEveryRepo() {
        outgoing.refresh()

        assertEquals(mapOf(notes.id to one), outgoing.pending.value)
    }

    @Test
    fun dropsACountItCouldNotReadAgain() {
        outgoing.refresh()
        reads[notes.id] = { error("killed") }

        outgoing.refresh(notes)

        assertEquals(emptyMap<String, Pending>(), outgoing.pending.value)
    }
}
