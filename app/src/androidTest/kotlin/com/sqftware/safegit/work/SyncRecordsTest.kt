package com.sqftware.safegit.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sqftware.safegit.git.TestDevice
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRecordsTest {
    private val records = SyncRecords(TestDevice.context.cacheDir.resolve("records-test").apply { deleteRecursively() })

    @Test
    fun keepsEachKindOfResult() {
        val results = listOf(
            SyncResult.UpToDate,
            SyncResult.Synced(2, 1),
            SyncResult.Conflict(listOf("a.md", "b c.md")),
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
}
