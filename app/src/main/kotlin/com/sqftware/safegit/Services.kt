package com.sqftware.safegit

import android.content.Context
import android.os.Build
import com.sqftware.safegit.git.BundledGit
import com.sqftware.safegit.git.Git
import com.sqftware.safegit.repos.FileRepoStore
import com.sqftware.safegit.repos.RepoStore
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Identity
import com.sqftware.safegit.sync.Repo
import com.sqftware.safegit.work.SyncRecords

/** What the app's parts share, built once per process. */
class Services private constructor(context: Context) {
    val repos: RepoStore = FileRepoStore(context.filesDir.resolve("repos.json"))
    val records = SyncRecords(context.filesDir.resolve("syncs"))

    /** Installing rewrites git's links, so it happens once per process, on first use off the main thread. */
    val git: Git by lazy { BundledGit(context).install() }
    val device: String = Build.MODEL
    val identity = Identity("SafeGit on $device", "safegit@users.noreply.github.com")

    // Kept out of backups: a git dir can be large, and the remote has it all
    private val gitDirs = context.noBackupFilesDir.resolve("repos")

    fun repo(saved: SavedRepo) = Repo(gitDir(saved.id), saved.folder)

    fun gitDir(id: String) = gitDirs.resolve("$id.git")

    companion object {
        @Volatile
        private var instance: Services? = null

        fun of(context: Context) = instance ?: synchronized(this) {
            instance ?: Services(context.applicationContext).also { instance = it }
        }
    }
}
