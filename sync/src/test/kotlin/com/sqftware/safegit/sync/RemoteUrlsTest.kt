package com.sqftware.safegit.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteUrlsTest {
    @Test
    fun takesAnSshUrlOverHttps() {
        assertEquals("https://github.com/aQuigs/nier-theme.git", overHttps("git@github.com:aQuigs/nier-theme.git"))
        assertEquals("https://github.com/me/lib", overHttps("github.com:me/lib"))
        assertEquals("https://github.com/me/lib.git", overHttps("git@github.com:/me/lib.git"))
        assertEquals("https://github.com/me/lib.git", overHttps("ssh://git@github.com/me/lib.git"))
        assertEquals("https://gitlab.example.com/me/lib.git", overHttps("ssh://git@gitlab.example.com:2222/me/lib.git"))
        assertEquals("https://github.com/me/lib.git", overHttps("git+ssh://github.com/me/lib.git"))
    }

    @Test
    fun leavesAnyOtherUrlAlone() {
        for (url in listOf("https://github.com/me/lib.git", "http://host/lib", "file:///tmp/lib.git", "/tmp/lib.git", "../lib.git", "git://host/lib")) {
            assertEquals(url, overHttps(url))
        }
    }
}
