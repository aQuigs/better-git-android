package com.sqftware.safegit.diagnostics

import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.Step
import com.sqftware.safegit.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticsReportTest {
    private val versions = Versions("0.1.0 (12)", "Android 15 (API 35), Google Pixel 8", "git version 2.51.0", listOf("git 2.51.0", "libcurl 8.16.0"))
    private val notes = SavedRepo("a1", "https://github.com/me/notes.git", File("/storage/emulated/0/Documents/notes"), "main", AutoSync.HOURLY)
    private val work = SavedRepo("b2", "https://github.com/me/work.git", File("/storage/emulated/0/work"), "main")
    private val scrubber = Scrubber(mapOf(notes.folder.path to "<folder>"))

    @Test
    fun scrubsOnlyGitsOwnWords() {
        val noScrubber = { throw AssertionError("scrubbed what needs no scrubbing") }
        listOf(SyncResult.Synced(1, 1), SyncResult.Failed(Problem.FOLDER_WRITE, "a: b"), SyncResult.Failed(Problem.FETCH)).forEach {
            pastSync("a1", 0, false, emptyList(), 0, it, noScrubber)
        }
    }

    @Test
    fun listsVersionsReposAndEachSyncsStepsAndEnd() {
        val syncs = listOf(
            past("a1", 1_760_000_000_000, false, listOf(Step.COMMIT to 0, Step.FETCH to 120, Step.MERGE to 900, Step.PUSH to 1_400), 1_850, SyncResult.Synced(2, 1)),
            past("b2", 1_759_990_000_000, true, listOf(Step.COMMIT to 10, Step.FETCH to 60), 2_300, SyncResult.Failed(Problem.FETCH, "fatal: unable to access 'https://github.com/me/work.git/': Could not resolve host: github.com")),
            past("gone", 1_759_980_000_000, false, emptyList(), 50, SyncResult.Cancelled),
        )

        assertEquals(
            """
            SafeGit diagnostics, 2025-10-09 12:00:00 UTC
            App 0.1.0 (12), Android 15 (API 35), Google Pixel 8
            git version 2.51.0
            Bundled: git 2.51.0, libcurl 8.16.0

            Repos: 2
            repo 1: automatic sync: hourly
            repo 2: automatic sync: off

            Last 3 syncs, newest first:

            repo 1, 2025-10-09 08:53:20 UTC, started by the user, 1.9 s
              commit at 0.0 s, fetch at 0.1 s, merge at 0.9 s, push at 1.4 s
              synced: pulled 2, pushed 1

            repo 2, 2025-10-09 06:06:40 UTC, automatic, 2.3 s
              commit at 0.0 s, fetch at 0.1 s
              failed: FETCH
                fatal: unable to access '<url>': Could not resolve host: <host>

            a removed repo, 2025-10-09 03:20:00 UTC, started by the user, 0.1 s
              cancelled

            """.trimIndent(),
            diagnosticsReport(1_760_011_200_000, versions, listOf(notes, work), syncs),
        )
    }

    @Test
    fun countsThePathsAConflictOrChoiceNames() {
        val syncs = listOf(
            past("a1", 0, false, emptyList(), 0, SyncResult.Conflict(listOf("diary.md", "plans.md"))),
            past("a1", 0, false, emptyList(), 0, SyncResult.Choose(listOf(Choice("photo.png", "a", null)))),
        )

        val report = diagnosticsReport(0, versions, listOf(notes), syncs)

        assertFalse(report, "diary" in report || "plans" in report || "photo" in report)
        assertTrue(report, "conflicted files: 2, marked" in report)
        assertTrue(report, "choices to make: 1" in report)
    }

    @Test
    fun onlyCountsTheLinesOfAFolderProblem() {
        val syncs = listOf(
            past("a1", 0, false, emptyList(), 0, SyncResult.Failed(Problem.FOLDER_WRITE, "Jane Doe diary: open failed: ENOSPC")),
            past("a1", 0, false, emptyList(), 0, SyncResult.Failed(Problem.SUBMODULE, "vendor: These files are in the way: Therapy notes, Divorce lawyer\nlib: It is not on a branch.")),
        )

        val report = diagnosticsReport(0, versions, listOf(notes), syncs)

        assertFalse(report, listOf("Jane", "Therapy", "Divorce", "vendor").any { it in report })
        assertTrue(report, "failed: FOLDER_WRITE\n    1 lines naming files left out\n" in report)
        assertTrue(report, "failed: SUBMODULE\n    2 lines naming files left out\n" in report)
    }

    @Test
    fun showsAtMostTenLinesOfGitsWords() {
        val syncs = listOf(past("a1", 0, false, emptyList(), 0, SyncResult.Failed(Problem.PUSH, (1..14).joinToString("\n") { "remote: line $it" })))

        val report = diagnosticsReport(0, versions, listOf(notes), syncs)

        assertTrue(report, "    remote: line 10\n    … 4 more lines\n" in report)
    }

    private fun past(repo: String, started: Long, automatic: Boolean, steps: List<Pair<Step, Long>>, millis: Long, result: SyncResult) =
        pastSync(repo, started, automatic, steps, millis, result) { scrubber }
}
