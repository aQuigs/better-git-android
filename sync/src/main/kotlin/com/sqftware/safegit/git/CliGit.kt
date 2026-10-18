package com.sqftware.safegit.git

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
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
        if (Thread.interrupted()) throw InterruptedException()
        val process = ProcessBuilder(listOf(executable) + args)
            .directory(dir)
            .apply {
                environment().keys.removeAll { it.startsWith("GIT_") || it == "XDG_CONFIG_HOME" }
                environment().putAll(DEFAULTS + this@CliGit.environment + environment)
            }
            .start()
        try {
            // Each stream has its own thread, since git blocks once any pipe's buffer fills, and this one waits for git
            // A git that exits without reading its input closes the pipe; on Android an uncaught throw would crash the app
            val writer = if (input.isEmpty()) {
                process.outputStream.close()
                null
            } else {
                thread {
                    try {
                        process.outputStream.bufferedWriter().use { it.write(input) }
                    } catch (_: IOException) {
                    }
                }
            }
            // A stopped git's streams are closed under their readers, which Android fails rather than ends; uncaught, a
            // read's failure would crash the app, and ignored, it would read as git saying nothing
            val output = arrayOf("", "")
            var failure: IOException? = null
            val readers = listOf(process.inputStream, process.errorStream).mapIndexed { i, stream ->
                thread {
                    try {
                        output[i] = stream.bufferedReader().readText()
                    } catch (e: IOException) {
                        failure = e
                    }
                }
            }
            val exitCode = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                stop(process)
                throw e
            }
            readers.forEach(Thread::join)
            writer?.join()
            failure?.let { throw it }

            return GitResult(exitCode, output[0], output[1])
        } finally {
            process.destroy()
        }
    }

    /**
     * Ends git with SIGTERM, on which it removes its lock and temporary files, and waits for it to exit, so nothing of
     * it runs on in the repo once the caller moves on.
     */
    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
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

        const val STOP_SECONDS = 5L
    }
}
