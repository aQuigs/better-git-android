package com.sqftware.safegit.diagnostics

import android.content.Context
import android.os.Build
import com.sqftware.safegit.about.LicenseSource
import com.sqftware.safegit.about.appVersion
import com.sqftware.safegit.auth.SignIn
import com.sqftware.safegit.git.Git
import com.sqftware.safegit.git.run
import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Repo
import com.sqftware.safegit.sync.RepoPaths
import com.sqftware.safegit.sync.Step
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.work.PastSync
import com.sqftware.safegit.work.SyncRecords
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Records each sync for a [diagnosticsReport], and makes one from those and the phone it runs on. Blocks on file I/O and
 * runs git.
 */
class Diagnostics(
    private val context: Context,
    private val repos: RepoStore,
    private val records: SyncRecords,
    private val git: () -> Git,
    private val signIn: SignIn,
    private val licenses: LicenseSource,
    private val repo: (SavedRepo) -> Repo,
) {
    /**
     * Records a sync of [saved] as [pastSync] tells it. Its error text is scrubbed now, while what could name the repo's
     * files, folder or remote is at hand, so the history never holds it.
     */
    fun record(saved: SavedRepo, started: Long, automatic: Boolean, steps: List<Pair<Step, Long>>, millis: Long, result: SyncResult) {
        try {
            records.log(pastSync(saved.id, started, automatic, steps, millis, result) { Scrubber(hidden(saved)) })
        } catch (_: Exception) {
            // A report missing one sync is better than a sync that fails for want of its record
        }
    }

    fun report(): String {
        val versions = Versions(
            app = context.appVersion(),
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            git = Scrubber(emptyMap()).scrub(gitVersion()),
            packages = licenses.load().packages.map { "${it.name} ${it.version}" },
        )
        return diagnosticsReport(System.currentTimeMillis(), versions, runBlocking { repos.all.first() }, records.history())
    }

    private fun gitVersion() = try {
        val result = git().run(context.cacheDir, "--version")
        if (result.ok) result.stdout.trim() else "git --version failed (${result.exitCode}): ${result.stderr.trim()}"
    } catch (e: Exception) {
        "git did not run: $e"
    }

    /**
     * What error text could hold that names the user, [saved]'s files, folder or remote, or the sign-in, each with what
     * stands for it. A name with a dot or slash goes anyway; these are for the rest, such as a folder with a space.
     */
    private fun hidden(saved: SavedRepo): Map<String, String> = buildMap {
        // A message can name a file by its path, a folder on the way, or the file's name alone; each part is matched alone
        val paths = runCatching { RepoPaths(git()).of(repo(saved)) }.getOrNull().orEmpty()
        for (path in paths) path.split('/').forEach { put(it, "<file>") }

        put(saved.folder.path, "<folder>")
        put(saved.url, "<url>")
        (signIn.state.value as? SignIn.State.SignedIn)?.account?.let { account ->
            put(account.login, "<account>")
            account.name?.let { put(it, "<account>") }
        }
        runCatching { signIn.savedToken() }.getOrNull()?.let { put(it, "<secret>") }
    }
}
