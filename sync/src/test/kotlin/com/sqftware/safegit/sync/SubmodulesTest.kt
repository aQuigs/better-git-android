package com.sqftware.safegit.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SubmodulesTest {
    @Test
    fun resolvesARelativeUrlAgainstTheParentsRemote() {
        assertEquals("https://github.com/me/lib.git", Submodules.resolveUrl("https://github.com/me/notes.git", "../lib.git"))
        assertEquals("https://github.com/other/lib.git", Submodules.resolveUrl("https://github.com/me/notes.git/", "../../other/lib.git"))
        assertEquals("https://github.com/me/notes.git/lib", Submodules.resolveUrl("https://github.com/me/notes.git", "./lib"))
        assertEquals("git@github.com:me/lib.git", Submodules.resolveUrl("git@github.com:me/notes.git", "../lib.git"))
    }

    @Test
    fun leavesAnAbsoluteUrlAlone() {
        assertEquals("https://gitlab.com/x/y.git", Submodules.resolveUrl("https://github.com/me/notes.git", "https://gitlab.com/x/y.git"))
    }
}
