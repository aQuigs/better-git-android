package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
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
    fun aConflictPutsBothVersionsInTheFileAndSyncsTheRest() {
        laptop.write("laptop.md", "from the laptop\n")
        val remoteHead = conflictInNotes()

        assertEquals(SyncResult.Conflict(listOf("notes.md")), sync())

        assertEquals("one\n<<<<<<< Pixel\nPHONE\n=======\nLAPTOP\n>>>>>>> remote\nthree\n", files.read("notes.md"))
        assertEquals("from the laptop\n", files.read("laptop.md"))
        assertEquals(remoteHead, git.ok(remote, "rev-parse", "main"))
    }

    @Test
    fun pushesNothingWhileMarkersAreLeft() {
        val remoteHead = conflictInNotes()
        files.write("ideas.md", "merges cleanly\n")
        sync()
        files.write("other.md", "an edit while the conflict is open\n")

        assertEquals(SyncResult.Conflict(listOf("notes.md")), sync())

        assertEquals(remoteHead, git.ok(remote, "rev-parse", "main"))
    }

    @Test
    fun pushesTheResolutionWithEverythingElseOnceTheMarkersAreGone() {
        conflictInNotes()
        files.write("ideas.md", "merges cleanly\n")
        sync()

        files.write("notes.md", "one\nPHONE and LAPTOP\nthree\n")
        files.write("other.md", "an edit while the conflict was open\n")

        assertEquals(SyncResult.Synced(pulled = 0, pushed = 1), sync())
        laptopPull()
        assertEquals("one\nPHONE and LAPTOP\nthree\n", laptop.read("notes.md"))
        assertEquals("merges cleanly\n", laptop.read("ideas.md"))
        assertEquals("an edit while the conflict was open\n", laptop.read("other.md"))
        assertEquals(SyncResult.UpToDate, sync())
        assertEquals("", phoneGit("status", "--porcelain"))
    }

    @Test
    fun aResolutionMergesWithWhatTheRemoteGotMeanwhile() {
        conflictInNotes()
        sync()
        laptop.write("later.md", "pushed while the phone resolved\n")
        laptop.commitAndPush("later")

        files.write("notes.md", "one\nresolved\nthree\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())
        assertEquals("pushed while the phone resolved\n", files.read("later.md"))
        laptopPull()
        assertEquals("one\nresolved\nthree\n", laptop.read("notes.md"))
    }

    @Test
    fun deletingAConflictedFileResolvesItAsDeleted() {
        conflictInNotes()
        sync()

        files.resolve("notes.md").delete()

        assertEquals(SyncResult.Synced(pulled = 0, pushed = 1), sync())
        laptopPull()
        assertFalse(laptop.resolve("notes.md").exists())
    }

    @Test
    fun aConflictSurvivesTheAppKilledAtTheLastStep() = aConflictSurvives(git.dyingAtTheLastStep())

    @Test
    fun aConflictSurvivesTheAppKilledBeforeTheBranchMoves() = aConflictSurvives(git.dyingBeforeTheBranchMoves())

    @Test
    fun aConflictSurvivesTheAppKilledAfterTheBranchMoved() = aConflictSurvives(git.dyingAfterTheBranchMoved())

    private fun aConflictSurvives(dying: Git) {
        val remoteHead = conflictInNotes()

        assertThrows(Killed::class.java) { sync(dying) }

        assertEquals(SyncResult.Conflict(listOf("notes.md")), sync())
        assertEquals(remoteHead, git.ok(remote, "rev-parse", "main"))
        assertTrue(files.read("notes.md"), "<<<<<<< Pixel" in files.read("notes.md"))
    }

    @Test
    fun aFileBothSidesAddedHoldsBothVersions() {
        laptop.write("to do café.md", "laptop list\n")
        laptop.commitAndPush("laptop todo")
        files.write("to do café.md", "phone list\n")

        assertEquals(SyncResult.Conflict(listOf("to do café.md")), sync())

        assertEquals("<<<<<<< Pixel\nphone list\n=======\nlaptop list\n>>>>>>> remote\n", files.read("to do café.md"))
    }

    @Test
    fun asksWhichVersionOfABinaryFileToKeep() {
        binaryConflict()

        val asked = asked(sync())
        assertEquals(listOf("photo.png"), asked.map { it.path })
        assertEquals(listOf<Byte>(0, 1, 4), files.resolve("photo.png").readBytes().toList())

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync(choices = asked.associateWith { Keep.LOCAL }))
        laptopPull()
        assertEquals(listOf<Byte>(0, 1, 4), laptop.resolve("photo.png").readBytes().toList())
    }

    @Test
    fun keepsTheRemotesVersionWhenChosen() {
        binaryConflict()
        files.write("ideas.md", "kept too\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync(choices = asked(sync()).associateWith { Keep.REMOTE }))

        assertEquals(listOf<Byte>(0, 1, 3), files.resolve("photo.png").readBytes().toList())
        assertEquals("kept too\n", files.read("ideas.md"))
        assertEquals(listOf<Byte>(0, 1, 4), phoneGit("cat-file", "-p", "refs/safegit/backup/1000:photo.png").toByteArray().toList())
    }

    @Test
    fun asksWhetherToKeepAFileTheOtherSideDeleted() {
        git.ok(laptop, "rm", "-q", "notes.md")
        laptop.commitAndPush("delete notes")
        files.write("notes.md", "one\nedited\nthree\n")

        val choice = asked(sync()).single()
        assertEquals("notes.md", choice.path)
        assertTrue(choice.local != null && choice.remote == null)
        assertEquals("one\nedited\nthree\n", files.read("notes.md"))

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync(choices = mapOf(choice to Keep.LOCAL)))
        laptopPull()
        assertEquals("one\nedited\nthree\n", laptop.read("notes.md"))
    }

    @Test
    fun aChoiceCountsOnlyForAConflictThereIsNow() {
        laptop.write("laptop.md", "the remote moved on\n")
        laptop.commitAndPush("laptop file")
        files.write("notes.md", "one\nedited\nthree\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync(choices = mapOf(Choice("notes.md", null, null) to Keep.REMOTE)))

        laptopPull()
        assertEquals("one\nedited\nthree\n", laptop.read("notes.md"))
    }

    @Test
    fun aChoiceMadeBeforeAnotherEditIsAskedAgain() {
        binaryConflict()
        val asked = asked(sync())
        files.resolve("photo.png").writeBytes(byteArrayOf(0, 1, 5))

        val result = sync(choices = asked.associateWith { Keep.REMOTE })

        assertEquals(listOf("photo.png"), asked(result).map { it.path })
        assertEquals(listOf<Byte>(0, 1, 5), files.resolve("photo.png").readBytes().toList())
    }

    @Test
    fun renamingAMarkedFileNeverPushesItsMarkers() {
        val remoteHead = conflictInNotes()
        sync()
        files.resolve("notes.md").renameTo(files.resolve("renamed.md"))

        assertEquals(SyncResult.Conflict(listOf("renamed.md")), sync())
        assertEquals(remoteHead, git.ok(remote, "rev-parse", "main"))

        files.write("renamed.md", "one\nresolved\nthree\n")
        assertEquals(SyncResult.Synced(pulled = 0, pushed = 1), sync())
        laptopPull()
        assertEquals("one\nresolved\nthree\n", laptop.read("renamed.md"))
    }

    @Test
    fun marksEveryConflictGitFinds() {
        // Found by fuzzing: a merge only the histogram diff, which merge-tree uses, sees as a conflict
        laptop.write("notes.md", "\nx\nx\nb\nb\nb\n\n")
        laptop.commitAndPush("base")
        sync()
        laptop.write("notes.md", "\nx\nx\nb\nb\n}\nx\n\n")
        laptop.commitAndPush("laptop edit")
        files.write("notes.md", "\nx\nx\nx\nb\nb\n\n")

        assertEquals(SyncResult.Conflict(listOf("notes.md")), sync())

        assertTrue(files.read("notes.md"), "<<<<<<< Pixel" in files.read("notes.md"))
    }

    @Test
    fun aNoteQuotingConflictMarkersSyncs() {
        files.write("git.md", "A conflict looks like:\n<<<<<<< HEAD\nmine\n=======\ntheirs\n>>>>>>> main\n")

        assertEquals(SyncResult.Synced(pulled = 0, pushed = 1), sync())
    }

    @Test
    fun aNoteAddedToAFolderTheRemoteRenamedStaysWhereItWasAdded() {
        laptop.write("log/today.md", "today\n")
        laptop.commitAndPush("log")
        sync()
        git.ok(laptop, "mv", "log", "journal")
        laptop.commitAndPush("rename the folder")
        files.write("log/new.md", "a new note\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())

        assertEquals("a new note\n", files.read("log/new.md"))
        assertEquals("today\n", files.read("journal/today.md"))
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
        assertTrue(files.read("notes.md"), "edited mid-sync\n" in files.read("notes.md"))
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

        assertThrows(Killed::class.java) { sync(git.dyingAtTheLastStep()) }

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
        assertThrows(Killed::class.java) { sync(git.dyingAtTheLastStep()) }
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
        assertThrows(Killed::class.java) { sync(git.dyingAfterTheBranchMoved()) }
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
        assertThrows(Killed::class.java) { sync(git.dyingAtTheLastStep()) }
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
    fun mergesAnEditIntoAFileTheRemoteRenamedByCase() {
        git.ok(laptop, "mv", "notes.md", "NOTES.md")
        laptop.commitAndPush("rename")
        files.write("notes.md", "rewritten\non the phone\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())

        assertEquals(listOf("NOTES.md"), files.list()!!.toList())
        assertEquals("rewritten\non the phone\n", laptop.also { laptopPull() }.read("NOTES.md"))
    }

    @Test
    fun takesARenameByCaseWithAnEditWhenThePhoneChangedOtherFiles() {
        git.ok(laptop, "mv", "notes.md", "NOTES.md")
        laptop.write("NOTES.md", "rewritten\non the laptop\n")
        laptop.commitAndPush("rename and rewrite")
        files.write("ideas.md", "an idea\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())

        assertEquals(listOf("NOTES.md", "ideas.md"), files.list()!!.sorted())
        assertEquals("rewritten\non the laptop\n", files.read("NOTES.md"))
    }

    @Test
    fun mergesAnEditIntoAFolderTheRemoteRenamedByCase() {
        laptop.write("plan/week.md", "monday\n")
        laptop.commitAndPush("plan")
        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())
        // A case-insensitive disk cannot rename a folder by case in one step
        git.ok(laptop, "mv", "plan", "plan-renaming")
        git.ok(laptop, "mv", "plan-renaming", "Plan")
        laptop.commitAndPush("rename the folder")
        files.write("plan/week.md", "rewritten\non the phone\n")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync())

        assertEquals("rewritten\non the phone\n", laptop.also { laptopPull() }.read("Plan/week.md"))
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
        assertThrows(Killed::class.java) { sync(git.dyingAtTheLastStep()) }
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
    fun aNameTheRemoteSpellsInAnotherCaseIsOneFile() {
        files.write("todo.md", "from the phone\n")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "100644,${blob("from the laptop\n")},TODO.md")
        git.ok(laptop, "commit", "-q", "-m", "add TODO.md")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        val result = sync()

        if (caseInsensitive) {
            assertEquals(SyncResult.Conflict(listOf("TODO.md")), result)
            assertEquals(listOf("TODO.md", "notes.md"), files.list()!!.sorted())
            files.write("todo.md", "from the laptop\n")
            assertEquals(SyncResult.UpToDate, sync())
        } else {
            assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), result)
            assertEquals("from the phone\n", files.read("todo.md"))
        }
    }

    @Test
    fun aFileAndAFolderNamedInAnotherCaseConflict() {
        files.write("plan/week.md", "a folder on the phone")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "100644,${blob("a file on the laptop")},PLAN")
        git.ok(laptop, "commit", "-q", "-m", "add PLAN")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        val result = sync()

        if (caseInsensitive) {
            val asked = asked(result)
            assertEquals(listOf("PLAN", "plan"), asked.map { it.path })
            assertEquals("a folder on the phone", files.read("plan/week.md"))
            assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), sync(choices = asked.associateWith { Keep.LOCAL }))
            assertEquals(listOf("notes.md", "plan"), files.list()!!.sorted())
        } else {
            assertEquals(SyncResult.Synced(pulled = 1, pushed = 1), result)
        }
    }

    @Test
    fun aRenameCutShortKeepsALaterEdit() {
        git.ok(laptop, "mv", "notes.md", "Notes.md")
        laptop.write("Notes.md", "one\ntwo\nthree\nfour\n")
        laptop.commitAndPush("rename and edit")
        assertThrows(Killed::class.java) { sync(git.dyingAtTheLastStep()) }
        files.write("Notes.md", "edited on the phone\n")

        sync()

        // Case-sensitive storage sees the phone's file and the remote's rename as added twice, so both are in it
        assertTrue(files.read("Notes.md"), "edited on the phone\n" in files.read("Notes.md"))
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
    fun replacesAFolderWithAFileNamedInAnotherCase() {
        laptop.write("plan/week.md", "a folder")
        laptop.commitAndPush("plan as a folder")
        sync()
        git.ok(laptop, "rm", "-q", "-r", "plan")
        git.ok(laptop, "update-index", "--add", "--cacheinfo", "100644,${blob("now a file")},PLAN")
        git.ok(laptop, "commit", "-q", "-m", "PLAN as a file")
        git.ok(laptop, "push", "-q", "origin", "HEAD:main")

        assertEquals(SyncResult.Synced(pulled = 1, pushed = 0), sync())

        assertEquals("now a file", files.read("PLAN"))
        assertEquals("", phoneGit("status", "--porcelain"))
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

        assertEquals(listOf("plan"), asked(sync()).map { it.path })

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

    private fun asked(result: SyncResult) = (result as? SyncResult.Choose)?.choices ?: throw AssertionError("Not a choice: $result")

    private fun sync(git: Git = this.git, repo: Repo = phone, choices: Map<Choice, Keep> = emptyMap()) =
        SafeSync(git, Identity("Phone", "phone@example.com"), device = "Pixel", now = { 1000 }).sync(repo, choices = choices)

    /** Both sides change the second line of notes.md; returns the remote's commit, which has the laptop's change. */
    private fun conflictInNotes(): String {
        laptop.write("notes.md", "one\nLAPTOP\nthree\n")
        laptop.commitAndPush("laptop edit")
        files.write("notes.md", "one\nPHONE\nthree\n")
        return git.ok(remote, "rev-parse", "main")
    }

    /** Both sides change photo.png, synced before: to 0 1 3 on the laptop, 0 1 4 on the phone. */
    private fun binaryConflict() {
        laptop.resolve("photo.png").writeBytes(byteArrayOf(0, 1, 2))
        laptop.commitAndPush("photo")
        sync()
        laptop.resolve("photo.png").writeBytes(byteArrayOf(0, 1, 3))
        laptop.commitAndPush("laptop photo")
        files.resolve("photo.png").writeBytes(byteArrayOf(0, 1, 4))
    }

    private fun clone(name: String, from: File = remote) = temp.root.resolve(name).also {
        git.ok(temp.root, "clone", "-q", from.path, it.path)
    }

    private fun splitClone(name: String, from: File = remote) =
        git.splitClone(from, gitDir = temp.root.resolve("$name.git"), workTree = workTrees.root.resolve(name))

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
}
