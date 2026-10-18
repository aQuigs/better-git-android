package com.sqftware.safegit.sync

/**
 * Stops a sync at once, as the system killing the app would, which every step of a sync survives, except while the
 * folder moves: a move always runs to its end, so the folder is never left half updated. A running git is stopped
 * through an interrupt of the sync's thread, which [com.sqftware.safegit.git.CliGit] answers by ending it cleanly.
 */
class Cancellation {
    private var cancelled = false
    private var thread: Thread? = null
    private var shielded = false

    /** Safe from any thread, at any time, any number of times. */
    @Synchronized
    fun cancel() {
        if (cancelled) return
        cancelled = true
        if (!shielded) thread?.interrupt()
    }

    /** Runs [sync] on this thread, which ends in [InterruptedException] once cancelled, at once if it was already. */
    internal fun <T> running(sync: () -> T): T {
        synchronized(this) {
            if (cancelled) throw InterruptedException()
            thread = Thread.currentThread()
        }
        try {
            return sync()
        } finally {
            synchronized(this) {
                thread = null
                // An interrupt that came after the last git must not reach whatever runs on this thread next
                Thread.interrupted()
            }
        }
    }

    /** Runs [block] to its end whatever a cancel says meanwhile, unless the sync was cancelled before it began. */
    internal fun <T> shielded(block: () -> T): T {
        synchronized(this) {
            if (cancelled) throw InterruptedException()
            shielded = true
        }
        try {
            return block()
        } finally {
            synchronized(this) {
                shielded = false
                if (cancelled) thread?.interrupt()
            }
        }
    }
}
