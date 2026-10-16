package com.sqftware.safegit.sync

import java.io.File

/** A submodule as a commit declares it, with [url] resolved against the parent's remote and [commit] the one recorded. */
internal data class Submodule(val name: String, val path: String, val url: String, val branch: String?, val commit: String)

/**
 * The submodules of [git]'s repo, each a repo of its own. As git keeps them, a submodule's git dir sits in its parent's,
 * under modules/<name>, and its folder, where the parent records it, holds a `.git` file pointing there.
 */
internal class Submodules(private val git: RepoGit) {
    private val parentBranch by lazy { git.output("symbolic-ref", "--quiet", "--short", "HEAD").orEmpty() }
    private val parentUrl by lazy {
        val remote = git.output("config", "branch.$parentBranch.remote") ?: "origin"
        git.output("config", "remote.$remote.url").orEmpty()
    }

    /** Those [commit]'s .gitmodules declares and that it records a commit for, leaving out any git would refuse. */
    fun of(commit: String): List<Submodule> {
        if (git.resolve("$commit:.gitmodules") == null) return emptyList()
        // "<key>\n<value>" entries, with keys as submodule.<name>.<variable>; a name may hold dots
        val variables = git.check("config", "--blob", "$commit:.gitmodules", "--null", "--list").stdout.paths()
            .map { it.substringBefore('\n') to it.substringAfter('\n', "") }
            .filter { (key) -> key.startsWith("submodule.") }
            .groupBy({ (key) -> key.removePrefix("submodule.").substringBeforeLast('.') }, { (key, value) -> key.substringAfterLast('.') to value })
        val recorded = git.check("ls-tree", "-r", "-z", commit).stdout.paths()
            .filter { it.startsWith("${Change.GITLINK} ") }
            .associate { it.substringAfter('\t') to it.substringBefore('\t').split(' ')[2] }

        val submodules = variables.mapNotNull { (name, entries) ->
            val values = entries.toMap()
            val path = values["path"] ?: return@mapNotNull null
            val url = values["url"]?.takeIf { !it.startsWith("-") }?.let { resolveUrl(parentUrl, it) } ?: return@mapNotNull null
            // A name becomes a folder in the git dir, so one climbing out of it is refused, as git refuses it
            if (name.isEmpty() || '\n' in name || name.split('/', '\\').any { it == ".." } || File(name).isAbsolute) return@mapNotNull null
            // A path on this device would copy whatever is there into the folder; only a repo cloned from one may name one
            if (isLocal(url) && !isLocal(parentUrl)) return@mapNotNull null
            val branch = values["branch"]?.let { if (it == ".") parentBranch else it }
            recorded[path]?.let { Submodule(name, path, url, branch, it) }
        }
        // As git refuses them, a name whose git dir would sit inside another's
        val names = submodules.map { it.name }
        return submodules.filter { submodule -> names.none { submodule.name.startsWith("$it/") } }.sortedBy { it.path }
    }

    fun repo(submodule: Submodule) = Repo(git.repo.gitDir.resolve("modules/${submodule.name}"), git.repo.workTree.resolve(submodule.path))

    /** The `.git` file that makes the folder the submodule's, for the parent's git to find it. */
    fun pointer(submodule: Submodule) = git.repo.workTree.resolve("${submodule.path}/.git")

    fun pointerText(submodule: Submodule) = "gitdir: ${repo(submodule).gitDir.path}\n"

    fun isSetUp(submodule: Submodule) = repo(submodule).gitDir.isDirectory && pointer(submodule).let { it.isFile && it.readText() == pointerText(submodule) }

    companion object {
        private val SCP_LIKE = Regex("^[^/]+:")

        fun isLocal(url: String) = url.startsWith("file:") || ("://" !in url && !SCP_LIKE.containsMatchIn(url))

        /** A relative URL is relative to the parent's remote taken as a folder, as git resolves it. */
        fun resolveUrl(parent: String, url: String): String {
            if (!url.startsWith("./") && !url.startsWith("../")) return url
            var base = parent.trimEnd('/')
            var rest = url
            while (true) {
                rest = when {
                    rest.startsWith("./") -> rest.removePrefix("./")
                    rest.startsWith("../") -> rest.removePrefix("../").also { base = base.substringBeforeLast('/') }
                    else -> break
                }
            }
            return "$base/$rest"
        }
    }
}
