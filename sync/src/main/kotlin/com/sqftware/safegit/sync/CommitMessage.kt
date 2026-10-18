package com.sqftware.safegit.sync

import java.time.Instant
import java.time.ZoneId

/**
 * What one of a sync's commits holds, for its message: [time] in epoch milliseconds, and how many [files] it changes,
 * counted only when asked for, as finding renames among many changes takes a while.
 */
class CommitFacts(val device: String, val time: Long, files: () -> Int) {
    val files by lazy(files)
}

/** Writes the message of each commit a sync makes of the local changes. */
fun interface CommitMessage {
    fun write(facts: CommitFacts): String
}

/**
 * A message from [template], in which `{device}`, `{date}` and `{files}` stand for the device's name, the day in [zone]
 * and the number of files changed. One that comes out blank is [DEFAULT]'s, since git refuses an empty message.
 */
class CommitTemplate(private val template: String, private val zone: ZoneId = ZoneId.systemDefault()) : CommitMessage {
    override fun write(facts: CommitFacts): String {
        val values = mapOf(
            "device" to { facts.device },
            "date" to { Instant.ofEpochMilli(facts.time).atZone(zone).toLocalDate().toString() },
            "files" to { facts.files.toString() },
        )
        // In one pass, so a device named "{date}" stays as named
        val message = PLACEHOLDER.replace(template) { values[it.groupValues[1]]?.invoke() ?: it.value }.trim()
        return message.ifEmpty { DEFAULT.write(facts) }
    }

    companion object {
        const val DEFAULT_TEMPLATE = "Changes from {device}"
        val DEFAULT = CommitTemplate(DEFAULT_TEMPLATE)
        // Android's ICU regex, unlike the JVM's, rejects an unescaped closing brace
        private val PLACEHOLDER = Regex("""\{(\w+)\}""")
    }
}
