package com.sqftware.safegit

import android.content.Context
import android.os.Build
import androidx.work.WorkManager
import com.sqftware.safegit.about.AssetLicenses
import com.sqftware.safegit.about.LicenseSource
import com.sqftware.safegit.auth.GitHubDeviceFlow
import com.sqftware.safegit.auth.KeystoreTokenStore
import com.sqftware.safegit.auth.SignIn
import com.sqftware.safegit.diagnostics.Diagnostics
import com.sqftware.safegit.git.BundledGit
import com.sqftware.safegit.git.Git
import com.sqftware.safegit.repos.AccountRepos
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.repos.FileRepoStore
import com.sqftware.safegit.repos.GitHubRepoDirectory
import com.sqftware.safegit.repos.RemoteBranches
import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.ChoicePreview
import com.sqftware.safegit.sync.ChoicePreviews
import com.sqftware.safegit.sync.FileHistory
import com.sqftware.safegit.sync.FileVersions
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.MarkedFile
import com.sqftware.safegit.sync.MarkedFiles
import com.sqftware.safegit.sync.PastVersion
import com.sqftware.safegit.sync.Repo
import com.sqftware.safegit.work.AutoSchedule
import com.sqftware.safegit.work.FileSyncHistory
import com.sqftware.safegit.sync.PendingChanges
import com.sqftware.safegit.work.NotificationBoard
import com.sqftware.safegit.work.Outgoing
import com.sqftware.safegit.work.RepoLocks
import com.sqftware.safegit.work.RunningSyncs
import com.sqftware.safegit.work.SyncNotices
import com.sqftware.safegit.work.SyncHistory
import com.sqftware.safegit.work.SyncRecords
import com.sqftware.safegit.work.Syncer
import com.sqftware.safegit.work.WorkSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the app's parts share, built once per process. */
class Services private constructor(context: Context) {
    // Kept out of backups: a git dir can be large, and the remote has it all
    private val gitDirs = context.noBackupFilesDir.resolve("repos")

    // HEAD holds "ref: refs/heads/<branch>" while on one, as a sync always leaves it; the card shows no branch otherwise
    val repos: RepoStore = FileRepoStore(context.filesDir.resolve("repos.json")) { id ->
        gitDir(id).resolve("HEAD").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.startsWith(HEAD_BRANCH) }?.removePrefix(HEAD_BRANCH).orEmpty()
    }
    private val syncs = context.filesDir.resolve("syncs")
    val records = SyncRecords(syncs)
    val history: SyncHistory = FileSyncHistory(syncs)

    /** Installing rewrites git's links, so it happens once per process, on first use off the main thread. */
    val git: Git by lazy { BundledGit(context).install() }
    val device: String = Build.MODEL
    val licenses: LicenseSource = AssetLicenses { path -> context.assets.open(path).bufferedReader().use { it.readText() } }

    // For work that outlives the screen that started it
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A build without a client id (see app/build.gradle.kts) syncs public repos only
    val signIn = SignIn(
        context.getString(R.string.github_client_id).takeIf { it.isNotEmpty() }?.let(::GitHubDeviceFlow),
        KeystoreTokenStore(context.filesDir.resolve("github.json")),
        scope,
    )
    val accountRepos = AccountRepos(GitHubRepoDirectory(signIn::token), signIn.state.map { it is SignIn.State.SignedIn }, scope)
    val running = RunningSyncs()
    val schedule = AutoSchedule(WorkManager.getInstance(context))
    val notices = SyncNotices(syncs, NotificationBoard(context))
    val syncer: Syncer = WorkSyncer(context, repos, records, running, schedule, scope)

    init {
        // A card asking to sign in again would otherwise keep asking until its next sync
        scope.launch { signIn.granted.collect { syncer.syncRefused(signIn::reaches) } }
    }

    val locks = RepoLocks()
    val outgoing = Outgoing(repos, { synchronized(locks.of(it.id)) { PendingChanges(git).of(repo(it)) } }, scope)

    // Connecting commits nothing, so who it runs as never shows
    val connector = Connector({ git }, Identity("SafeGit", "safegit@users.noreply.github.com"), signIn::credentials, repos, syncer, ::gitDir, context.cacheDir, scope)
    val branches = RemoteBranches(connector::branches)
    val diagnostics = Diagnostics(context, repos, records, { git }, signIn, licenses, ::repo)

    fun repo(saved: SavedRepo) = Repo(gitDir(saved.id), saved.folder)

    /** Null when it cannot be read, as the choice can still be made by its path alone. */
    fun preview(saved: SavedRepo, choice: Choice): ChoicePreview? = try {
        ChoicePreviews(git, repo(saved)).of(choice)
    } catch (_: Exception) {
        null
    }

    /** A file a conflict marked, read while no sync of the repo runs; null when it cannot be read. */
    fun openMarked(saved: SavedRepo, path: String): MarkedFile? = try {
        marked(saved) { open(path) }
    } catch (_: Exception) {
        null
    }

    fun files(saved: SavedRepo) = history(saved).files()

    fun versions(saved: SavedRepo, path: String) = history(saved).of(path)

    /** Null when it cannot be read, as the version can still be restored without it. */
    fun preview(saved: SavedRepo, path: String, versions: FileVersions, version: PastVersion): ChoicePreview? = try {
        history(saved).preview(path, versions, version)
    } catch (_: Exception) {
        null
    }

    fun saveMarked(saved: SavedRepo, file: MarkedFile, text: String): MarkedFiles.Saved = try {
        marked(saved) { save(file, text) }
    } catch (e: Exception) {
        MarkedFiles.Saved.Failed(e.message.orEmpty())
    }

    private fun <T> marked(saved: SavedRepo, action: MarkedFiles.() -> T): T =
        synchronized(locks.of(saved.id)) { MarkedFiles(git, repo(saved), device).action() }

    /** Under the repo's lock, as a sync moving the folder meanwhile would meet a file it did not write. */
    fun restore(saved: SavedRepo, path: String, versions: FileVersions, version: PastVersion): FileHistory.Restored =
        synchronized(locks.of(saved.id)) { history(saved).restore(path, versions, version) }
            .also { if (it == FileHistory.Restored.DONE) outgoing.changed(saved) }

    private fun history(saved: SavedRepo) = FileHistory(git, repo(saved))

    private fun gitDir(id: String) = gitDirs.resolve("$id.git")

    companion object {
        private const val HEAD_BRANCH = "ref: refs/heads/"

        @Volatile
        private var instance: Services? = null

        fun of(context: Context) = instance ?: synchronized(this) {
            instance ?: Services(context.applicationContext).also { instance = it }
        }
    }
}
