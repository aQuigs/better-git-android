package com.sqftware.safegit.work

import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per repo, held by the thread running git in it, since git cannot be cancelled: a stopped worker's sync runs
 * on while WorkManager starts the same work again, and a read of the folder must not meet a sync halfway.
 */
class RepoLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun of(repo: String): Any = locks.computeIfAbsent(repo) { Any() }
}
