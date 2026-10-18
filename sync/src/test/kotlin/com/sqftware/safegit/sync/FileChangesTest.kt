package com.sqftware.safegit.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class FileChangesTest {
    @Test
    fun laterChangesFollowEarlierOnes() {
        val first = FileChanges(changed = listOf("edited.md", "gone.md"), added = listOf("brief.md", "new.md"), deleted = listOf("back.md"))
        val later = FileChanges(changed = listOf("new.md", "other.md"), added = listOf("back.md"), deleted = listOf("brief.md", "gone.md"))

        assertEquals(
            FileChanges(changed = listOf("back.md", "edited.md", "other.md"), added = listOf("new.md"), deleted = listOf("gone.md")),
            first.then(later),
        )
    }
}
