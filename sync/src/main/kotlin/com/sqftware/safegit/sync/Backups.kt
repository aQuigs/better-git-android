package com.sqftware.safegit.sync

import kotlin.time.Duration.Companion.days

/**
 * The commits syncs replaced, each under refs/safegit/backup/<time in ms>. A backup is kept while it is among the
 * newest [KEEP_COUNT], or made within [KEEP_FOR] of the newest.
 *
 * Ages count from the newest backup rather than the clock, and each name is later than the last, so a phone clock set
 * wrong and then corrected never makes a recent backup look old. Only a new backup makes others old, so pruning happens
 * as one is recorded.
 */
internal class Backups(private val git: RepoGit) {
    fun record(commit: String, now: Long) {
        val backups = git.check("for-each-ref", "--format=%(refname) %(objectname)", PREFIX).stdout.lines()
            .filter(String::isNotEmpty)
            .mapNotNull { line ->
                val (ref, old) = line.split(" ")
                ref.removePrefix(PREFIX).takeIf(TIME::matches)?.toLongOrNull()?.let { Backup(ref, old, it) }
            }
            .sortedByDescending { it.time }
        val time = maxOf(now, (backups.firstOrNull()?.time ?: 0) + 1)
        val pruned = backups.drop(KEEP_COUNT - 1).filter { time - it.time > KEEP_FOR.inWholeMilliseconds }

        // Each delete names the commit read, so a ref that changed since is left as it is
        val deletes = pruned.joinToString("") { "delete ${it.ref} ${it.commit}\n" }
        git.check("update-ref", "--stdin", input = "create $PREFIX$time $commit\n$deletes")
    }

    private class Backup(val ref: String, val commit: String, val time: Long)

    companion object {
        const val PREFIX = "refs/safegit/backup/"
        const val KEEP_COUNT = 10
        val KEEP_FOR = 30.days

        /** A ref under the prefix named otherwise was not made by a sync, and is left alone. */
        private val TIME = Regex("[0-9]+")
    }
}
