package com.sqftware.safegit.work

import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per repo, held by the thread running git in it, since a stopped worker's sync runs on until it reaches a
 * point where it can stop, while WorkManager starts the same work again, and a read of the folder must not meet a
 * sync halfway.
 */
class RepoLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun of(repo: String): Any = locks.computeIfAbsent(repo) { Any() }
}
