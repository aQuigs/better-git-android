package com.sqftware.safegit.git

import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Runs a git executable as a child process, with [environment] added to the inherited one.
 *
 * Git behaves the same wherever it runs: it ignores the system and user config and any GIT_* variable it inherits,
 * so a desktop test and the phone run identical commands. It never waits for a terminal or an editor, and gives up on a
 * transfer that stalls.
 */
class CliGit(private val executable: String, private val environment: Map<String, String> = emptyMap()) : Git {
    override fun run(dir: File, args: List<String>, environment: Map<String, String>, input: String): GitResult {
        val process = ProcessBuilder(listOf(executable) + args)
            .directory(dir)
            .apply {
                environment().keys.removeAll { it.startsWith("GIT_") || it == "XDG_CONFIG_HOME" }
                environment().putAll(DEFAULTS + this@CliGit.environment + environment)
            }
            .start()
        try {
            // Each stream has its own thread, since git blocks once any pipe's buffer fills
            // A git that exits without reading its input closes the pipe; on Android an uncaught throw would crash the app
            val writer = thread {
                try {
                    process.outputStream.bufferedWriter().use { it.write(input) }
                } catch (_: IOException) {
                }
            }
            var stderr = ""
            val stderrReader = thread { stderr = process.errorStream.bufferedReader().readText() }
            val stdout = process.inputStream.bufferedReader().readText()
            stderrReader.join()
            writer.join()

            return GitResult(process.waitFor(), stdout, stderr)
        } finally {
            process.destroy()
        }
    }

    private companion object {
        // ":" and "cat" are names git itself treats as no editor and no pager, so it starts no other program
        val DEFAULTS = mapOf(
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_PAGER" to "cat",
            "GIT_EDITOR" to ":",
            "GIT_HTTP_LOW_SPEED_LIMIT" to "1000",
            "GIT_HTTP_LOW_SPEED_TIME" to "60",
        )
    }
}
