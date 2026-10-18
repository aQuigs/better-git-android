package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepoPathsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")

    @Test
    fun listsThePathsOfTheIndexAndTheRemote() {
        val remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        val laptop = temp.newFolder("laptop")
        git.ok(laptop, "clone", "-q", remote.path, ".")
        laptop.resolve("Old notes").mkdirs()
        laptop.resolve("Old notes/Jane Doe.md").writeText("from the laptop\n")
        git.ok(laptop, "add", "--all")
        git.ok(laptop, "commit", "-q", "-m", "laptop")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        val phone = git.splitClone(remote, gitDir = temp.root.resolve("phone.git"), workTree = temp.root.resolve("phone"))
        phone.workTree.resolve("diary.md").writeText("from the phone\n")
        RepoGit(git, phone, PHONE).check("add", "diary.md")

        assertEquals(setOf("Old notes/Jane Doe.md", "diary.md"), RepoPaths(git).of(phone))
    }

    @Test
    fun listsNoneWhereGitHasNone() {
        val phone = Repo(temp.root.resolve("missing.git"), temp.newFolder("phone"))

        assertEquals(emptySet<String>(), RepoPaths(git).of(phone))
    }
}
