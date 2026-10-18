package com.sqftware.safegit.git

import java.io.File

/** Runs git commands. Sync logic depends only on this, so it can be tested against desktop git on the JVM. */
interface Git {
    /**
     * Runs `git [args]` in [dir], with [environment] added for this call only, such as a token's config, and [input] on
     * its stdin, such as a list of paths too long for the command line.
     *
     * An interrupt of the calling thread stops git cleanly and throws [InterruptedException] once git has exited.
     */
    fun run(dir: File, args: List<String>, environment: Map<String, String> = emptyMap(), input: String = ""): GitResult
}

fun Git.run(dir: File, vararg args: String) = run(dir, args.toList())

/** [bytes] is stdout as git wrote it, for a file's contents; [stdout] is the same as text. */
class GitResult(val exitCode: Int, val bytes: ByteArray, val stderr: String) {
    constructor(exitCode: Int, stdout: String, stderr: String) : this(exitCode, stdout.encodeToByteArray(), stderr)

    val stdout by lazy { bytes.decodeToString() }
    val ok get() = exitCode == 0
}
