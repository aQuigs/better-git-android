package com.sqftware.safegit.work

import com.sqftware.safegit.sync.FileChanges
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.sync.Transfer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncHistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val history by lazy { FileSyncHistory(temp.root.resolve("syncs")) }
    private val notes = FileChanges(changed = listOf("notes.md"), added = listOf("new note.md"), deleted = listOf("old.md"))

    @Test
    fun keepsEachSyncNewestFirst() {
        val first = RecordedSync(1000, automatic = false, SyncResult.Synced(1, 1), Transfer(pulled = notes, pushed = FileChanges(emptyList(), listOf("phone.md"), emptyList())))
        val second = RecordedSync(2000, automatic = true, SyncResult.Failed(Problem.FETCH, "no network"), Transfer())

        history.add("repo", first)
        history.add("repo", second)

        assertEquals(listOf(second, first), syncs("repo"))
        assertEquals(emptyList<RecordedSync>(), syncs("other"))
    }

    @Test
    fun keepsTheLastFifty() {
        for (at in 1L..60L) history.add("repo", RecordedSync(at, automatic = true, SyncResult.UpToDate, Transfer()))

        assertEquals((60L downTo 11L).toList(), syncs("repo").map { it.at })
    }

    @Test
    fun dropsOlderSyncsOnceTheyNameTooManyFiles() {
        val vault = FileChanges(emptyList(), (1..15_000).map { "note $it.md" }, emptyList())
        history.add("repo", RecordedSync(1, automatic = false, SyncResult.Synced(0, 1), Transfer(pushed = vault)))
        history.add("repo", RecordedSync(2, automatic = false, SyncResult.Synced(1, 0), Transfer(pulled = notes)))
        history.add("repo", RecordedSync(3, automatic = false, SyncResult.Synced(0, 1), Transfer(pushed = vault)))

        assertEquals(listOf(3L, 2L), syncs("repo").map { it.at })

        history.add("repo", RecordedSync(4, automatic = false, SyncResult.Synced(0, 1), Transfer(pushed = vault + vault)))
        assertEquals(listOf(4L), syncs("repo").map { it.at })
    }

    @Test
    fun skipsTheSyncsThisVersionCannotRead() {
        history.add("repo", RecordedSync(1, automatic = false, SyncResult.UpToDate, Transfer()))
        val file = temp.root.resolve("syncs/repo.history.json")
        file.writeText(file.readText().replaceFirst("[", """[{"at":2,"automatic":false,"result":{"type":"fromTheFuture"}},"""))

        assertEquals(listOf(1L), syncs("repo").map { it.at })

        file.writeText("not json")
        assertEquals(emptyList<RecordedSync>(), syncs("repo"))
    }

    private operator fun FileChanges.plus(other: FileChanges) = FileChanges(changed + other.changed, added + other.added.map { "more $it" }, deleted + other.deleted)

    private fun syncs(id: String) = runBlocking { history.of(id).first() }
}
