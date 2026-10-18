package com.sqftware.safegit.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubberTest {
    private val scrubber = Scrubber(
        mapOf(
            "/storage/emulated/0/Documents/My Notes" to "<folder>",
            "https://github.com/me/notes.git" to "<url>",
            "gho_abc123" to "<secret>",
            "Diary 2026" to "<file>",
        ),
    )

    @Test
    fun hidesWhatItIsGivenByItsLabel() {
        assertEquals(
            "error: unable to write <folder><name>: No space left on device",
            scrubber.scrub("error: unable to write /storage/emulated/0/Documents/My Notes/inbox.md: No space left on device"),
        )
        assertEquals("token <secret> refused", scrubber.scrub("token gho_abc123 refused"))
        assertEquals("<file> is binary", scrubber.scrub("Diary 2026 is binary"))
    }

    @Test
    fun hidesAddressesAndSecretsItWasNotGiven() {
        assertEquals("fatal: unable to access <url>: Could not resolve host: <host>", scrubber.scrub("fatal: unable to access https://example.org/x/y/: Could not resolve host: example.org"))
        assertEquals("from <url>", scrubber.scrub("from git@example.org:me/repo.git"))
        assertEquals("by <email>", scrubber.scrub("by someone@example.org"))
        assertEquals("Failed to connect to <address> port 8418", scrubber.scrub("Failed to connect to 192.168.1.20 port 8418"))
        assertEquals("Authorization: Basic <secret>", scrubber.scrub("Authorization: Basic eC1hY2Nlc3M="))
        assertEquals("<secret> leaked", scrubber.scrub("ghp_0123456789abcdef leaked"))
    }

    @Test
    fun hidesWhatCouldBeAFileName() {
        assertEquals("error: '<name>' would be overwritten", scrubber.scrub("error: 'Secret plans' would be overwritten"))
        assertEquals("<name>: Permission denied", scrubber.scrub("journal/today.md: Permission denied"))
        assertEquals("cannot lock ref '<name>' (<name>)", scrubber.scrub("cannot lock ref 'refs/heads/main' (a.txt)"))
        assertEquals("couldn't open '<name>': x", scrubber.scrub("couldn't open 'Jane Doe medical.md': x"))
        assertEquals("<name> kept", scrubber.scrub("2025.01.15-JaneDoe kept"))
        assertEquals("Could not resolve host: <host>", scrubber.scrub("Could not resolve host: gitea"))
    }

    @Test
    fun keepsGitsOwnWords() {
        val text = "! [rejected] main -> main (fetch first)\nhint: Updates were rejected. Try again."
        assertEquals(text, scrubber.scrub(text))
        assertEquals("git version 2.51.0", scrubber.scrub("git version 2.51.0"))
        assertEquals("java.io.IOException: <name>", scrubber.scrub("java.io.IOException: /data/x"))
    }
}
