package com.sqftware.safegit.repos

import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.RepoSetup
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBranchesTest {
    private val url = "https://github.com/me/notes.git"

    @Test
    fun listsTheDefaultBranchFirst() = runBlocking {
        val branches = RemoteBranches({ RepoSetup.Branches.Listed(listOf("drafts", "main", "topic/idea"), default = "main") }, wait = 0)

        assertEquals(
            listOf(RemoteBranches.State.Loading(url), RemoteBranches.State.Listed(url, listOf("main", "drafts", "topic/idea"), default = "main")),
            branches.of(url).toList(),
        )
    }

    @Test
    fun asksNothingWithoutAUrl() = runBlocking {
        val branches = RemoteBranches({ error("Nothing to ask") }, wait = 0)

        assertEquals(listOf(RemoteBranches.State.None("")), branches.of("").toList())
    }

    @Test
    fun offersNoPickFromARemoteWithoutBranches() = runBlocking {
        val branches = RemoteBranches({ RepoSetup.Branches.Listed(emptyList(), default = null) }, wait = 0)

        assertEquals(RemoteBranches.State.None(url), branches.of(url).toList().last())
    }

    @Test
    fun saysWhenTheyCannotBeListed() = runBlocking {
        val branches = RemoteBranches({ RepoSetup.Branches.Failed(Problem.SIGN_IN) }, wait = 0)

        assertEquals(RemoteBranches.State.Failed(url), branches.of(url).toList().last())
    }
}
