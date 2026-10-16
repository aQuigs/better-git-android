package com.sqftware.safegit.sync

import com.sqftware.safegit.git.Git
import com.sqftware.safegit.git.GitResult

/**
 * Runs git against one [repo], committing as [identity].
 *
 * Android kills child processes under memory pressure, so a git that failed must never read as an empty answer: one
 * empty diff or missing HEAD is enough to push a mass deletion. [check] and [probe] throw [GitFailure] instead.
 */
internal class RepoGit(private val git: Git, val repo: Repo, private val identity: Identity) {
    val emptyTree by lazy { check("mktree").stdout.trim() }
    val ignoreCase by lazy { output("config", "--bool", "core.ignorecase") == "true" }

    fun run(vararg args: String, environment: Map<String, String> = emptyMap(), input: String = ""): GitResult = git.run(
        repo.workTree,
        listOf("--git-dir=${repo.gitDir.path}", "--work-tree=${repo.workTree.path}") + args,
        identity.environment + environment,
        input,
    )

    /** Runs a command that must succeed. */
    fun check(vararg args: String, environment: Map<String, String> = emptyMap(), input: String = ""): GitResult =
        run(*args, environment = environment, input = input).also { if (!it.ok) throw GitFailure(args.first(), it) }

    /**
     * Runs a command whose exit code 1 is an answer, "no" or "none", as `diff --quiet`, `rev-parse --verify --quiet`,
     * `merge-base` and `merge-tree` use it.
     */
    fun probe(vararg args: String): GitResult =
        run(*args).also { if (it.exitCode !in 0..NO) throw GitFailure(args.first(), it) }

    /** The output of a [probe] that answered yes, or null. */
    fun output(vararg args: String) = probe(*args).takeIf { it.ok }?.stdout?.trim()

    /** The object [name] names, or null when there is none. */
    fun resolve(name: String) = output("rev-parse", "--verify", "--quiet", name)

    /** The branch's commit, or null before its first one. */
    fun head() = resolve("HEAD^{commit}")

    /** Every path that differs from [from] to [to], without guessing renames. */
    fun diff(from: String, to: String): List<Change> =
        // Raw entries are ":<old mode> <new mode> <old blob> <new blob> <status>" then the path
        check("diff-tree", "-r", "-z", "--no-renames", from, to).stdout.paths().chunked(2)
            .map { (entry, path) -> entry.removePrefix(":").split(" ").let { Change(path, it[0], it[1], it[2], it[3]) } }

    private companion object {
        const val NO = 1
    }
}

/** One path's entry on each side of a diff; a side without one has mode [ABSENT]. */
internal class Change(val path: String, val oldMode: String, val newMode: String, val oldBlob: String, val newBlob: String) {
    val old get() = isFile(oldMode)
    val new get() = isFile(newMode)

    // A submodule's files belong to its own repo, so on this side the path holds no file
    private fun isFile(mode: String) = mode != ABSENT && mode != GITLINK

    companion object {
        const val ABSENT = "000000"
        const val GITLINK = "160000"
    }
}

/** A line of `update-index -z --index-info`; mode [Change.ABSENT] removes [path]. */
internal fun indexEntry(mode: String, blob: String, path: String) = "$mode $blob\t$path\u0000"

internal class GitFailure(command: String, result: GitResult) :
    Exception("git $command failed (${result.exitCode}): ${result.stderr.trim()}")

/** Splits git's -z output, which stays unambiguous for any file name, unlike its quoted default. */
internal fun String.paths() = split('\u0000').filter(String::isNotEmpty)
