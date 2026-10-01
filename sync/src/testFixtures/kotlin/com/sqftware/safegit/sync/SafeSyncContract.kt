package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import com.sqftware.safegit.git.GitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rules SafeSync must keep, run against a real git: desktop git on the JVM, and the bundled git on the phone, so a
 * change in a newer git cannot slip past. A laptop clone plays the other device.
 *
 * [tempParent] is where temporary repos go, since Android has no writable default temp dir, and [workTreeParent] where
 * the phone's folder goes, so it can sit on shared storage like a real one.
 */
abstract class SafeSyncContract(tempParent: File? = null, workTreeParent: File? = tempParent) {
    protected abstract val git: Git

    @get:Rule
    val temp = TemporaryFolder(tempParent)

    @get:Rule
    val workTrees = TemporaryFolder(workTreeParent)

    private lateinit var remote: File
    private lateinit var laptop: File
    private lateinit var phone: Repo
    private val files get() = phone.workTree

    @Before
    fun setUp() {
        remote = temp.newFolder("remote.git")
        git.ok(remote, "init", "-q", "--bare", "-b", "main")
        laptop = clone("laptop")
        laptop.write("notes.md", "one\ntwo\nthree\n")
        laptop.commitAndPush("first notes")
        phone = splitClone("phone")
    }

    @Test
    fun upToDateDoesNothing() {
        val head = phoneGit("rev-parse", "HEAD")

        assertEquals(SyncResult.UpToDate, sync())

        assertEquals(head, phoneGit("rev-parse", "HEAD"))
    }

    @Test
    fun pushesEveryKindOfLocalChange() {
        files.write("notes.md", "one\ntwo\nthree\nfour\n")
        files.write("new.md", "untracked until now")
        files.write("folder/deep.md", "nested")

        assertEquals(SyncResult.Synced(pulled = 0, pushed = 1), sync())

        laptopPull()
        assertEquals("one\ntwo\nthree\nfour\n", laptop.read("notes.md"))
        assertEquals("untracked until now", laptop.read("new.md"))
        assertEquals("nested", laptop.read("folder/deep.md"))
        assertEquals("Changes from Pixel", git.ok(laptop, "log", "-1", "--format=%s"))
        assertEquals("Phone <phone@example.com>", git.ok(laptop, "log", "-1", "--format=%an <%ae>"))
    }

    @Test
    fun pushesDeletions() {
        files.resolve("notes.md").delete()

        sync()

        laptopPull()
        assertFalse(laptop.resolve("notes.md").exists())
    }

    @Test
    fun pullsRemoteChanges() {
        laptop.write("laptop.md", "from the laptop")
        laptop.commitAndPush("laptop work")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())

        assertEquals("from the laptop", files.read("laptop.md"))
    }

    @Test
    fun pullsIntoAnEmptyClone() {
        val emptyRemote = temp.newFolder("empty.git")
        git.ok(emptyRemote, "init", "-q", "--bare", "-b", "main")
        val emptyPhone = splitClone("empty-phone", emptyRemote)
        val firstLaptop = clone("first-laptop", emptyRemote)
        firstLaptop.write("hello.md", "hello")
        firstLaptop.commitAndPush("hello")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync(repo = emptyPhone))

        assertEquals("hello", emptyPhone.workTree.read("hello.md"))
    }

    @Test
    fun rebasesLocalWorkOntoRemoteWork() {
        laptop.write("notes.md", "one\ntwo\nthree\nlaptop line\n")
        laptop.commitAndPush("laptop work")
        files.write("notes.md", "phone line\none\ntwo\nthree\n")
        files.write("phone.md", "from the phone")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())

        assertEquals("phone line\none\ntwo\nthree\nlaptop line\n", files.read("notes.md"))
        laptopPull()
        assertEquals("phone line\none\ntwo\nthree\nlaptop line\n", laptop.read("notes.md"))
        assertEquals("from the phone", laptop.read("phone.md"))
        assertEquals(listOf("Changes from Pixel", "laptop work", "first notes"), git.ok(laptop, "log", "--format=%s").lines())
    }

    @Test
    fun keepsTheOriginalCommitsUnderABackupRef() {
        laptop.write("laptop.md", "x")
        laptop.commitAndPush("laptop work")
        files.write("phone.md", "y")

        sync()

        val backups = phoneGit("for-each-ref", "--format=%(refname)", "refs/safegit/backup/").lines()
        assertEquals(listOf("refs/safegit/backup/1000"), backups)
        assertEquals("y", phoneGit("show", "refs/safegit/backup/1000:phone.md"))
    }

    @Test
    fun conflictLeavesEverythingAsItWas() {
        laptop.write("notes.md", "one\nLAPTOP\nthree\n")
        laptop.commitAndPush("laptop edit")
        val remoteHead = git.ok(remote, "rev-parse", "main")
        files.write("notes.md", "one\nPHONE\nthree\n")

        assertEquals(SyncResult.Conflict(listOf("notes.md")), sync())

        assertEquals("one\nPHONE\nthree\n", files.read("notes.md"))
        assertEquals("Changes from Pixel", phoneGit("log", "-1", "--format=%s"))
        assertEquals("", phoneGit("status", "--porcelain"))
        assertEquals(remoteHead, git.ok(remote, "rev-parse", "main"))
    }

    @Test
    fun aConflictClearsOnceTheFilesAgree() {
        laptop.write("notes.md", "one\nLAPTOP\nthree\n")
        laptop.commitAndPush("laptop edit")
        files.write("notes.md", "one\nPHONE\nthree\n")
        sync()

        files.write("notes.md", "one\nLAPTOP\nthree\n")
        files.write("other.md", "an unrelated edit")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())
        laptopPull()
        assertEquals("an unrelated edit", laptop.read("other.md"))
        assertEquals(SyncResult.UpToDate, sync())
    }

    @Test
    fun neverOverwritesALocalFileThatTheRemoteAddsToo() {
        laptop.write("to do café.md", "laptop list")
        laptop.commitAndPush("laptop todo")
        files.write("to do café.md", "phone list")

        assertEquals(SyncResult.Conflict(listOf("to do café.md")), sync())

        assertEquals("phone list", files.read("to do café.md"))
    }

    @Test
    fun neverOverwritesAnIgnoredFileThatTheRemoteAdds() {
        phone.gitDir.resolve("info/exclude").apply { parentFile.mkdirs() }.writeText("cache.json\n")
        files.write("cache.json", "phone cache")
        laptop.write("cache.json", "laptop cache")
        laptop.commitAndPush("track the cache")

        assertEquals(Problem.FOLDER_BLOCKED, (sync() as SyncResult.Failed).problem)

        assertEquals("phone cache", files.read("cache.json"))
    }

    @Test
    fun keepsAnEditMadeWhileSyncing() {
        laptop.write("notes.md", "one\ntwo\nthree\nlaptop line\n")
        laptop.commitAndPush("laptop work")
        val editsDuringUpdate = git.beforeFirst({ "diff-tree" in it }) { files.write("notes.md", "edited mid-sync\n") }

        val result = sync(editsDuringUpdate)

        assertTrue(result.toString(), result is SyncResult.Conflict)
        assertEquals("edited mid-sync\n", files.read("notes.md"))
        assertEquals("", phoneGit("status", "--porcelain"))
    }

    @Test
    fun retriesWhenTheRemoteMovesBeforeThePush() {
        files.write("phone.md", "from the phone")
        val laptopPushesFirst = git.beforeFirst({ "push" in it }) {
            laptop.write("laptop.md", "from the laptop")
            laptop.commitAndPush("laptop work")
        }

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync(laptopPushesFirst))

        laptopPull()
        assertEquals("from the phone", laptop.read("phone.md"))
        assertEquals("from the laptop", files.read("laptop.md"))
    }

    @Test
    fun leavesOutCommitsTheRemoteDropped() {
        laptop.write("secret.env", "oops")
        laptop.commitAndPush("add secret")
        sync()
        git.ok(laptop, "reset", "-q", "--hard", "HEAD~1")
        laptop.write("laptop.md", "rewritten history")
        laptop.commitAndPush("laptop work", force = true)

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())

        assertFalse(files.resolve("secret.env").exists())
        assertEquals(git.ok(remote, "rev-parse", "main"), phoneGit("rev-parse", "HEAD"))
    }

    @Test
    fun neverMergesPathsThatDifferOnlyInCase() {
        // Built in the index, since the laptop's own folder may be case-insensitive too
        val blob = git.ok(laptop, "hash-object", "-w", "--stdin")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "100644,$blob,NOTES.md")
        git.ok(laptop, "commit", "-q", "-m", "same name, other case")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        val result = sync()

        assertEquals("one\ntwo\nthree\n", files.read("notes.md"))
        if (caseInsensitive) {
            assertEquals(SyncResult.Failed(Problem.FOLDER_BLOCKED, "NOTES.md"), result)
        } else {
            assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), result)
        }
    }

    @Test
    fun undoesAnUpdateItCannotFinish() {
        laptop.write("about.md", "about")
        laptop.write("dir/a.md", "a")
        laptop.commitAndPush("add dir")
        sync()
        laptop.write("about.md", "about, edited on the laptop")
        laptop.write("dir/a.md", "a, edited on the laptop")
        laptop.write("dir/new café.md", "new on the laptop")
        laptop.commitAndPush("laptop work")
        val head = phoneGit("rev-parse", "HEAD")
        val dir = files.resolve("dir")
        dir.setWritable(false)

        assertEquals(Problem.FOLDER_WRITE, (sync() as SyncResult.Failed).problem)

        assertEquals("about", files.read("about.md"))
        assertEquals(head, phoneGit("rev-parse", "HEAD"))
        assertEquals("", phoneGit("status", "--porcelain"))

        dir.setWritable(true)
        files.write("phone.md", "written after the failure")
        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())
        laptopPull()
        assertEquals("a, edited on the laptop", laptop.read("dir/a.md"))
        assertEquals("new on the laptop", laptop.read("dir/new café.md"))
        assertEquals("written after the failure", laptop.read("phone.md"))
        assertEquals("about, edited on the laptop", files.read("about.md"))
    }

    @Test
    fun finishesAnUpdateThatWasCutShort() {
        laptop.write("notes.md", "ONE\ntwo\nthree\n")
        laptop.write("laptop.md", "from the laptop")
        laptop.commitAndPush("laptop work")

        assertThrows(IllegalStateException::class.java) { sync(git.dyingAtTheLastStep()) }

        assertEquals(SyncResult.UpToDate, sync())
        assertEquals("ONE\ntwo\nthree\n", files.read("notes.md"))
        assertEquals("from the laptop", files.read("laptop.md"))
        assertEquals(git.ok(remote, "rev-parse", "main"), phoneGit("rev-parse", "HEAD"))
        assertEquals("", phoneGit("status", "--porcelain"))
    }

    @Test
    fun anUpdateCutShortNeverOverwritesALaterEdit() {
        laptop.write("notes.md", "ONE\ntwo\nthree\n")
        laptop.commitAndPush("laptop work")
        assertThrows(IllegalStateException::class.java) { sync(git.dyingAtTheLastStep()) }
        files.write("notes.md", "ONE\ntwo\nTHREE\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())

        assertEquals("ONE\ntwo\nTHREE\n", files.read("notes.md"))
        laptopPull()
        assertEquals("ONE\ntwo\nTHREE\n", laptop.read("notes.md"))
    }

    @Test
    fun anUpdateCutShortAfterTheBranchMovedStaysDone() {
        laptop.write("notes.md", "ONE\ntwo\nthree\n")
        laptop.write("laptop.md", "from the laptop")
        laptop.commitAndPush("laptop work")
        assertThrows(IllegalStateException::class.java) { sync(git.dyingAfterTheBranchMoved()) }
        files.write("notes.md", "ONE\ntwo\nTHREE\n")

        assertEquals(SyncResult.Synced(pulled = 0, pushed = 1), sync())

        laptopPull()
        assertEquals("ONE\ntwo\nTHREE\n", laptop.read("notes.md"))
        assertEquals("from the laptop", laptop.read("laptop.md"))
    }

    @Test
    fun undoesAResumedUpdateItCannotFinish() {
        laptop.write("a.md", "a")
        laptop.write("z/z.md", "z")
        laptop.commitAndPush("add files")
        sync()
        laptop.write("a.md", "a, edited on the laptop")
        laptop.write("z/z.md", "z, edited on the laptop")
        laptop.commitAndPush("laptop work")
        val head = phoneGit("rev-parse", "HEAD")
        assertThrows(IllegalStateException::class.java) { sync(git.dyingAtTheLastStep()) }
        // As if the killed sync had moved only a.md
        files.write("z/z.md", "z")
        val z = files.resolve("z")
        z.setWritable(false)

        assertEquals(Problem.FOLDER_WRITE, (sync() as SyncResult.Failed).problem)

        assertEquals("a", files.read("a.md"))
        assertEquals(head, phoneGit("rev-parse", "HEAD"))
        assertEquals("", phoneGit("status", "--porcelain"))
        z.setWritable(true)
        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())
        assertEquals("z, edited on the laptop", files.read("z/z.md"))
    }

    @Test
    fun neverTakesAFailedGitForAnAnswer() {
        val steps = mapOf<String, (List<String>) -> Boolean>(
            "head" to { "HEAD^{commit}" in it },
            "fork point" to { "--fork-point" in it },
            "add" to { "add" in it },
            "staged" to { "diff" in it },
            "changes" to { "diff-tree" in it },
            "index" to { "update-index" in it },
        )
        for ((step, command) in steps) {
            laptop.write("laptop.md", "laptop, $step")
            laptop.write("laptop $step.md", "kept")
            laptop.commitAndPush(step)
            files.write("phone.md", "phone, $step")
            val remoteHead = git.ok(remote, "rev-parse", "main")

            assertEquals(step, Problem.GIT, (sync(git.killing(command)) as? SyncResult.Failed)?.problem)
            assertEquals(step, remoteHead, git.ok(remote, "rev-parse", "main"))

            assertTrue(step, sync() is SyncResult.Synced)
            laptopPull()
            assertEquals(step, "phone, $step", laptop.read("phone.md"))
            assertEquals(step, "laptop, $step", files.read("laptop.md"))
            for (kept in steps.keys.takeWhile { it != step } + step) {
                assertEquals(step, "kept", files.read("laptop $kept.md"))
            }
        }
    }

    @Test
    fun followsARenameThatOnlyChangesCase() {
        git.ok(laptop, "mv", "notes.md", "Notes.md")
        laptop.commitAndPush("rename")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())

        assertEquals(listOf("Notes.md"), files.list()!!.toList())
        assertEquals("one\ntwo\nthree\n", files.read("Notes.md"))
        assertEquals("", phoneGit("status", "--porcelain"))
    }

    @Test
    fun aRenameCutShortNeverLosesTheFile() {
        laptop.write("other.md", "a\nb\nc\n")
        laptop.commitAndPush("add other.md")
        sync()
        git.ok(laptop, "mv", "notes.md", "Notes.md")
        laptop.write("other.md", "A\nb\nc\n")
        laptop.commitAndPush("rename, edit other.md")
        assertThrows(IllegalStateException::class.java) { sync(git.dyingAtTheLastStep()) }
        // An edit on top of the pulled version, which blocks finishing the update, so it goes back first
        files.write("other.md", "A\nb\nC\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())

        assertEquals(listOf("Notes.md", "other.md"), files.list()!!.sorted())
        assertEquals("one\ntwo\nthree\n", files.read("Notes.md"))
        laptopPull()
        assertEquals("A\nb\nC\n", laptop.read("other.md"))
    }

    @Test
    fun aBlockedRenameKeepsTheFile() {
        laptop.write(".gitignore", "local.txt\n")
        laptop.commitAndPush("ignore local.txt")
        sync()
        files.write("local.txt", "phone only")
        git.ok(laptop, "mv", "notes.md", "Notes.md")
        laptop.write("local.txt", "from the laptop")
        git.ok(laptop, "add", "--force", "local.txt")
        laptop.commitAndPush("rename, add local.txt")

        assertEquals(SyncResult.Failed(Problem.FOLDER_BLOCKED, "local.txt"), sync())

        assertEquals(listOf(".gitignore", "local.txt", "notes.md"), files.list()!!.sorted())
        assertEquals("one\ntwo\nthree\n", files.read("notes.md"))
        assertEquals("phone only", files.read("local.txt"))
    }

    @Test
    fun replacesAFileWithAFolderAndBack() {
        laptop.write("plan", "a file")
        laptop.commitAndPush("plan as a file")
        sync()
        git.ok(laptop, "rm", "-q", "plan")
        laptop.write("plan/week.md", "now a folder")
        laptop.commitAndPush("plan as a folder")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())
        assertEquals("now a folder", files.read("plan/week.md"))

        git.ok(laptop, "rm", "-q", "-r", "plan")
        laptop.write("plan", "a file again")
        laptop.commitAndPush("plan as a file again")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())
        assertEquals("a file again", files.read("plan"))
        assertEquals("", phoneGit("status", "--porcelain"))
    }

    @Test
    fun leavesSubmoduleFoldersToTheirRepos() {
        val commit = git.ok(laptop, "rev-parse", "HEAD")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "160000,$commit,library")
        git.ok(laptop, "commit", "-q", "-m", "add a submodule")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())

        assertEquals(git.ok(remote, "rev-parse", "main"), phoneGit("rev-parse", "HEAD"))
        assertEquals("", phoneGit("status", "--porcelain"))
    }

    @Test
    fun neverWritesOverACaseTwin() {
        files.write("notes.md", "one\ntwo\nthree, edited on the phone\n")
        laptop.write("notes.md", "one, edited on the laptop\ntwo\nthree\n")
        git.ok(laptop, "add", "notes.md")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "100644,${blob("other case")},NOTES.md")
        git.ok(laptop, "commit", "-q", "-m", "edit notes.md, add NOTES.md")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        val result = sync()

        if (caseInsensitive) {
            assertEquals(SyncResult.Failed(Problem.FOLDER_BLOCKED, "NOTES.md"), result)
            assertEquals("one\ntwo\nthree, edited on the phone\n", files.read("notes.md"))
        } else {
            assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), result)
            assertEquals("one, edited on the laptop\ntwo\nthree, edited on the phone\n", files.read("notes.md"))
        }
    }

    @Test
    fun aRenameCutShortKeepsALaterEdit() {
        git.ok(laptop, "mv", "notes.md", "Notes.md")
        laptop.write("Notes.md", "one\ntwo\nthree\nfour\n")
        laptop.commitAndPush("rename and edit")
        assertThrows(IllegalStateException::class.java) { sync(git.dyingAtTheLastStep()) }
        files.write("Notes.md", "edited on the phone\n")

        sync()

        assertEquals("edited on the phone\n", files.read("Notes.md"))
        assertTrue(phoneGit("log", "--all", "--format=%H", "-S", "edited on the phone").isNotEmpty())
    }

    @Test
    fun followsFoldersWhoseNamesDifferOnlyInCase() {
        laptop.write("Dir/a.md", "a")
        git.ok(laptop, "add", "Dir/a.md")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "100644,${blob("b")},dir/b.md")
        git.ok(laptop, "commit", "-q", "-m", "two spellings of one folder")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")
        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())

        git.ok(laptop, "update-index", "--cacheinfo", "100644,${blob("b, edited")},dir/b.md")
        git.ok(laptop, "commit", "-q", "-m", "edit dir/b.md")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())
        assertEquals("b, edited", files.read("dir/b.md"))
    }

    @Test
    fun clearsEmptyFoldersInTheWay() {
        laptop.write("plan/week.md", "a folder")
        laptop.commitAndPush("plan as a folder")
        sync()
        files.resolve("plan/empty/emptier").mkdirs()
        files.resolve("idea").mkdirs()
        git.ok(laptop, "rm", "-q", "-r", "plan")
        laptop.write("plan", "now a file")
        laptop.write("idea", "a file where the phone has an empty folder")
        laptop.commitAndPush("files where folders were")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())

        assertEquals("now a file", files.read("plan"))
        assertEquals("a file where the phone has an empty folder", files.read("idea"))
    }

    @Test
    fun neverClearsAFolderWithFilesInIt() {
        laptop.write("plan/week.md", "a folder")
        laptop.commitAndPush("plan as a folder")
        sync()
        files.write("plan/mine.md", "only on the phone")
        git.ok(laptop, "rm", "-q", "-r", "plan")
        laptop.write("plan", "now a file")
        laptop.commitAndPush("plan as a file")

        assertTrue(sync() is SyncResult.Conflict)

        assertEquals("only on the phone", files.read("plan/mine.md"))
    }

    @Test
    fun replacesAFileWithASubmoduleAndBack() {
        git.ok(laptop, "rm", "-q", "notes.md")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "160000,${git.ok(laptop, "rev-parse", "HEAD")},notes.md")
        git.ok(laptop, "commit", "-q", "-m", "notes.md becomes a submodule")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())
        assertTrue(files.resolve("notes.md").isDirectory)
        assertEquals("", phoneGit("status", "--porcelain"))

        git.ok(laptop, "rm", "-q", "--cached", "notes.md")
        laptop.write("notes.md", "a file again")
        laptop.commitAndPush("notes.md becomes a file again")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())
        assertEquals("a file again", files.read("notes.md"))
        assertEquals("", phoneGit("status", "--porcelain"))
    }

    @Test
    fun failsWhenThePushIsRefused() {
        remote.resolve("hooks/pre-receive").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\necho no pushes today >&2\nexit 1\n")
            setExecutable(true)
        }
        files.write("phone.md", "from the phone")

        val result = sync()

        assertEquals(Problem.PUSH, (result as SyncResult.Failed).problem)
    }

    @Test
    fun failsWithoutAnUpstream() {
        phoneGit("switch", "-q", "-c", "local-only")

        assertEquals(SyncResult.Failed(Problem.NO_UPSTREAM), sync())
    }

    private fun sync(git: Git = this.git, repo: Repo = phone) =
        SafeSync(git, Identity("Phone", "phone@example.com"), device = "Pixel", now = { 1000 }).sync(repo)

    private fun clone(name: String, from: File = remote) = temp.root.resolve(name).also {
        git.ok(temp.root, "clone", "-q", from.path, it.path)
    }

    /** Clones the way the app does: the git dir in private storage, set for the case rules of the folder's storage. */
    private fun splitClone(name: String, from: File = remote): Repo {
        val repo = Repo(gitDir = temp.root.resolve("$name.git"), workTree = workTrees.root.resolve(name))
        git.ok(temp.root, "clone", "-q", "--no-checkout", "--separate-git-dir", repo.gitDir.path, from.path, repo.workTree.path)
        repo.workTree.resolve(".git").delete()
        val probe = repo.workTree.resolve("CaseProbe").apply { writeText("") }
        phoneGit(repo, "config", "core.ignorecase", repo.workTree.resolve("caseprobe").exists().toString())
        probe.delete()
        if (RepoGit(git, repo, LAPTOP).head() != null) phoneGit(repo, "checkout", "-q")
        return repo
    }

    private fun phoneGit(vararg args: String) = phoneGit(phone, *args)

    private fun phoneGit(repo: Repo, vararg args: String) = RepoGit(git, repo, LAPTOP).check(*args).stdout.trim()

    private fun laptopPull() = git.ok(laptop, "pull", "-q", "--rebase")

    private fun File.commitAndPush(message: String, force: Boolean = false) {
        git.ok(this, "add", "--all")
        git.ok(this, "commit", "-q", "-m", message)
        git.ok(this, "push", "-q", *(if (force) arrayOf("--force") else emptyArray()), "origin", "HEAD:main")
    }

    private fun File.write(path: String, text: String) = resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private fun File.read(path: String) = resolve(path).readText()

    private val caseInsensitive get() = files.resolve("NOTES.MD").exists()

    /** Stores [text] as a blob on the laptop, for commits its own case-insensitive folder could not hold. */
    private fun blob(text: String): String {
        val file = temp.root.resolve("blob").apply { writeText(text) }
        return git.ok(laptop, "hash-object", "-w", file.path).also { file.delete() }
    }

    private fun Git.ok(dir: File, vararg args: String): String {
        val result = run(dir, args.toList(), LAPTOP.environment)
        assertTrue("git ${args.joinToString(" ")}: ${result.stderr}", result.ok)
        return result.stdout.trim()
    }

    /** Runs [action] just before the first git command whose arguments match [command]. */
    private fun Git.beforeFirst(command: (List<String>) -> Boolean, action: () -> Unit) =
        onFirst({ args, _ -> command(args) }) {
            action()
            null
        }

    /**
     * Calls [action] in place of the first git command that [command] matches by its arguments and input, running the
     * command too unless the action returns a result of its own.
     */
    private fun Git.onFirst(command: (List<String>, String) -> Boolean, action: () -> GitResult?): Git {
        val git = this
        var done = false
        return object : Git {
            override fun run(dir: File, args: List<String>, environment: Map<String, String>, input: String): GitResult {
                if (!done && command(args, input)) {
                    done = true
                    action()?.let { return it }
                }
                return git.run(dir, args, environment, input)
            }
        }
    }

    /** Stands in for Android killing the first git that [command] matches, as it does to child processes. */
    private fun Git.killing(command: (List<String>) -> Boolean) =
        onFirst({ args, _ -> command(args) }) { GitResult(exitCode = 137, stdout = "", stderr = "Killed") }

    /** Stands in for the system killing the app after the folder moved, before the index and branch follow. */
    private fun Git.dyingAtTheLastStep() = beforeFirst({ "--index-info" in it }) { error("killed") }

    /** Stands in for the system killing the app after the branch moved, before the update's record is cleared. */
    private fun Git.dyingAfterTheBranchMoved() =
        onFirst({ args, input -> "update-ref" in args && input.startsWith("delete") }) { error("killed") }

    private companion object {
        val LAPTOP = Identity("Laptop", "laptop@example.com")
    }
}
