package com.sqftware.safegit

import android.content.Context
import android.os.Build
import com.sqftware.safegit.auth.GitHubDeviceFlow
import com.sqftware.safegit.auth.KeystoreTokenStore
import com.sqftware.safegit.auth.SignIn
import com.sqftware.safegit.git.BundledGit
import com.sqftware.safegit.git.Git
import com.sqftware.safegit.repos.AccountRepos
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.repos.FileRepoStore
import com.sqftware.safegit.repos.GitHubRepoDirectory
import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.Repo
import com.sqftware.safegit.work.SyncRecords
import com.sqftware.safegit.work.Syncer
import com.sqftware.safegit.work.WorkSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

/** What the app's parts share, built once per process. */
class Services private constructor(context: Context) {
    val repos: RepoStore = FileRepoStore(context.filesDir.resolve("repos.json"))
    val records = SyncRecords(context.filesDir.resolve("syncs"))

    /** Installing rewrites git's links, so it happens once per process, on first use off the main thread. */
    val git: Git by lazy { BundledGit(context).install() }
    val device: String = Build.MODEL

    // Kept out of backups: a git dir can be large, and the remote has it all
    private val gitDirs = context.noBackupFilesDir.resolve("repos")

    // For work that outlives the screen that started it
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A build without a client id (see app/build.gradle.kts) syncs public repos only
    val signIn = SignIn(
        context.getString(R.string.github_client_id).takeIf { it.isNotEmpty() }?.let(::GitHubDeviceFlow),
        KeystoreTokenStore(context.filesDir.resolve("github.json")),
        scope,
    )
    val accountRepos = AccountRepos(GitHubRepoDirectory(signIn::token), signIn.state.map { it is SignIn.State.SignedIn }, scope)
    val syncer: Syncer = WorkSyncer(context, repos, records, scope)

    // Connecting commits nothing, so who it runs as never shows
    val connector = Connector({ git }, Identity("SafeGit", "safegit@users.noreply.github.com"), signIn::credentials, repos, syncer, ::gitDir, scope)

    fun repo(saved: SavedRepo) = Repo(gitDir(saved.id), saved.folder)

    private fun gitDir(id: String) = gitDirs.resolve("$id.git")

    companion object {
        @Volatile
        private var instance: Services? = null

        fun of(context: Context) = instance ?: synchronized(this) {
            instance ?: Services(context.applicationContext).also { instance = it }
        }
    }
}
