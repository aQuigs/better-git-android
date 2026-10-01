package com.sqftware.safegit.git

import java.io.File

/** Runs git commands. Sync logic depends only on this, so it can be tested against desktop git on the JVM. */
interface Git {
    /** Runs `git [args]` in [dir], with [environment] added for this call only, such as a token's config. */
    fun run(dir: File, args: List<String>, environment: Map<String, String> = emptyMap()): GitResult
}

fun Git.run(dir: File, vararg args: String) = run(dir, args.toList())

data class GitResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0
}
