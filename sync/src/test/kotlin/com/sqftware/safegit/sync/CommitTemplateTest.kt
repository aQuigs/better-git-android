package com.sqftware.safegit.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class CommitTemplateTest {
    // 2026-10-17 23:30 UTC
    private val facts = facts("Pixel 9")

    @Test
    fun fillsEachPlaceholder() {
        assertEquals("Pixel 9, 2026-10-17, 3 files", write("{device}, {date}, {files} files"))
        assertEquals("Changes from Pixel 9", CommitTemplate(CommitTemplate.DEFAULT_TEMPLATE).write(facts))
    }

    @Test
    fun takesTheDayWhereTheDeviceIs() {
        assertEquals("2026-10-18", CommitTemplate("{date}", ZoneId.of("Asia/Tokyo")).write(facts))
    }

    @Test
    fun leavesOtherBracesAsWritten() {
        assertEquals("{time} {} {device", write("{time} {} {device"))
        assertEquals("Phone {date}", CommitTemplate("{device}", ZoneOffset.UTC).write(facts("Phone {date}")))
    }

    @Test
    fun aBlankMessageIsTheDefault() {
        assertEquals("Changes from Pixel 9", write(""))
        assertEquals("Changes from Pixel 9", write("  \n"))
        assertEquals("Pixel 9", write("  {device}\n"))
    }

    @Test
    fun countsTheFilesOnlyForATemplateThatShowsThem() {
        val counted = CommitFacts("Pixel 9", 0) { throw AssertionError("counted") }

        assertEquals("Changes from Pixel 9", CommitTemplate.DEFAULT.write(counted))
    }

    private fun write(template: String) = CommitTemplate(template, ZoneOffset.UTC).write(facts)

    private fun facts(device: String) = CommitFacts(device, time = 1_792_279_800_000) { 3 }
}
