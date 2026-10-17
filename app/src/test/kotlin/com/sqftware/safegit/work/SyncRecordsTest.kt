package com.sqftware.safegit.work

import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncRecordsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val records by lazy { SyncRecords(temp.root.resolve("syncs")) }

    @Test
    fun keepsEachKindOfResult() {
        val results = listOf(
            SyncResult.UpToDate,
            SyncResult.Synced(2, 1),
            SyncResult.Conflict(listOf("a.md", "b c.md")),
            SyncResult.ConflictAhead(listOf("a.md")),
            SyncResult.Choose(listOf(Choice("photo.png", "a1", null))),
            SyncResult.Failed(Problem.FETCH, "no network"),
        )
        for (result in results) {
            records.saveResult("repo", result)
            assertEquals(result, records.result("repo"))
        }
    }

    @Test
    fun usesChoicesOnce() {
        val choices = mapOf(Choice("photo.png", null, "b2") to Keep.REMOTE)
        records.saveChoices("repo", choices)

        assertEquals(choices, records.takeChoices("repo"))
        assertEquals(emptyMap<Choice, Keep>(), records.takeChoices("repo"))
    }

    @Test
    fun keepsTheLastSyncOfAllUntilClosed() {
        assertNull(records.all())

        records.startAll("run", listOf("b", "a"))
        records.saveAllResult("run", "b", SyncResult.Synced(1, 2))
        assertEquals(AllRun(listOf("b", "a"), mapOf("b" to SyncResult.Synced(1, 2))), records.all())

        records.closeAll()
        assertNull(records.all())
    }

    @Test
    fun recordsOnlyTheResultsOfTheLatestSyncOfAll() {
        records.startAll("earlier", listOf("a"))
        records.startAll("later", listOf("a"))
        records.saveAllResult("earlier", "a", SyncResult.UpToDate)

        assertEquals(AllRun(listOf("a")), records.all())
    }

    @Test
    fun readsAResultItCannotParseAsNone() {
        temp.root.resolve("syncs").mkdirs()
        temp.root.resolve("syncs/repo.result.json").writeText("")
        assertNull(records.result("repo"))

        temp.root.resolve("syncs/repo.result.json").writeText("""{"type":"failed","problem":"RENAMED","detail":""}""")
        assertNull(records.result("repo"))
    }
}
