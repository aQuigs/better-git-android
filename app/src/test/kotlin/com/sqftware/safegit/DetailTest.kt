package com.sqftware.safegit

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailTest {
    private fun save(detail: Detail?) = with(Detail.saver) { SaverScope { true }.save(detail) }

    @Test
    fun comesBackAsItWasSaved() {
        for (detail in listOf(Detail.Add, Detail.About, Detail.History("1"), Detail.Files("1"), Detail.Resolve("1", "notes/a.md"))) {
            assertEquals(detail, Detail.saver.restore(save(detail)!!))
        }
    }

    // Nothing saved, so rememberSaveable starts again from its initial null
    @Test
    fun savesNothingWhenNothingIsOpen() {
        assertNull(save(null))
    }
}
