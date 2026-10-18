package com.sqftware.safegit.diagnostics

import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.Step
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.work.PastSync
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a report says of the app and where it runs: [git] is what the bundled git says of its version. */
data class Versions(val app: String, val android: String, val git: String, val packages: List<String>)

/**
 * The text of a bug report, made [now]: [versions], each of [repos]' settings, and the last [syncs], each with its steps
 * and how it ended. Repos go by their place in the list, never their name, folder or remote.
 */
fun diagnosticsReport(now: Long, versions: Versions, repos: List<SavedRepo>, syncs: List<PastSync>): String {
    val label = repos.withIndex().associate { (index, repo) -> repo.id to "repo ${index + 1}" }

    return buildString {
        appendLine("SafeGit diagnostics, ${time(now)}")
        appendLine("App ${versions.app}, ${versions.android}")
        appendLine(versions.git)
        appendLine("Bundled: ${versions.packages.joinToString(", ")}")

        appendLine()
        appendLine("Repos: ${repos.size}")
        for (repo in repos) appendLine("${label[repo.id]}: automatic sync: ${repo.auto.words()}")

        appendLine()
        appendLine(if (syncs.isEmpty()) "No syncs yet" else "Last ${syncs.size} syncs, newest first:")
        for (sync in syncs) {
            appendLine()
            val by = if (sync.automatic) "automatic" else "started by the user"
            appendLine("${label[sync.repo] ?: "a removed repo"}, ${time(sync.started)}, $by, ${seconds(sync.millis)}")
            if (sync.steps.isNotEmpty()) appendLine("  " + sync.steps.joinToString(", ") { (step, at) -> "${step.words()} at ${seconds(at)}" })
            appendLine("  ${sync.outcome}")
            sync.detail.forEach { appendLine("    $it") }
        }
    }
}

/**
 * A sync of [repo] that ended with [result], told as the report tells it: the paths a conflict or choice names are only
 * counted, and so are the lines of a failure that name files. Only git's own words are kept, through [scrubber], which
 * is made only when there are some.
 */
fun pastSync(repo: String, started: Long, automatic: Boolean, steps: List<Pair<Step, Long>>, millis: Long, result: SyncResult, scrubber: () -> Scrubber): PastSync {
    val outcome = when (result) {
        SyncResult.UpToDate -> "up to date"
        is SyncResult.Synced -> "synced: pulled ${result.pulled}, pushed ${result.pushed}"
        is SyncResult.Conflict -> "conflicted files: ${result.paths.size}, marked"
        is SyncResult.ConflictAhead -> "conflicted files: ${result.paths.size}, left for a sync the user starts"
        is SyncResult.Choose -> "choices to make: ${result.choices.size}"
        is SyncResult.Failed -> "failed: ${result.problem}"
        SyncResult.Cancelled -> "cancelled"
    }
    val detail = (result as? SyncResult.Failed)?.let { detail(it, scrubber) }.orEmpty()
    return PastSync(repo, started, automatic, steps, millis, outcome, detail)
}

/**
 * The failure's detail, at most [DETAIL_LINES] lines. Every problem but those in [GITS_WORDS] is SafeSync's sentences
 * around paths in the folder, such as a file in the way, which may be no file git knows and so none a [Scrubber] could
 * take out: those lines are only counted, since the problem already says what went wrong.
 */
private fun detail(failed: SyncResult.Failed, scrubber: () -> Scrubber): List<String> {
    val lines = failed.detail.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    if (failed.problem !in GITS_WORDS) return listOf("${lines.size} lines naming files left out")
    val scrub = scrubber()
    val shown = lines.take(DETAIL_LINES).map(scrub::scrub)
    return if (lines.size > DETAIL_LINES) shown + "… ${lines.size - DETAIL_LINES} more lines" else shown
}

private fun Enum<*>.words() = name.lowercase().replace('_', ' ')

private fun time(millis: Long) = TIME.format(Instant.ofEpochMilli(millis))

// The report is read by whoever takes the bug, so it never follows the phone's locale
private fun seconds(millis: Long) = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

private val GITS_WORDS = setOf(Problem.FETCH, Problem.PUSH, Problem.SIGN_IN, Problem.GIT)
private const val DETAIL_LINES = 10
private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
