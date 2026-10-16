package com.sqftware.safegit.sync

import java.io.File

/** A submodule as a commit declares it, with [url] resolved against the parent's remote and [commit] the one recorded. */
internal data class Submodule(val name: String, val path: String, val url: String, val branch: String?, val commit: String)

/**
 * The submodules of [git]'s repo, each a repo of its own. As git keeps them, a submodule's git dir sits in its parent's,
 * under modules/<name>, and its folder, where the parent records it, holds a `.git` file pointing there.
 */
internal class Submodules(private val git: RepoGit) {
    private val parentBranch by lazy { git.branch().orEmpty() }
    private val parentUrl by lazy {
        val remote = git.output("config", "branch.$parentBranch.remote") ?: "origin"
        git.output("config", "remote.$remote.url").orEmpty()
    }
    private val declared = HashMap<String, List<Submodule>>()

    /** Those [commit]'s .gitmodules declares and that it records a commit for, leaving out any git would refuse. */
    fun of(commit: String): List<Submodule> = declared.getOrPut(commit) {
        if (git.resolve("$commit:.gitmodules") == null) return@getOrPut emptyList()
        val variables = sections(git.check("config", "--blob", "$commit:.gitmodules", "--null", "--list").stdout.paths(), "submodule")
        val paths = variables.values.mapNotNull { it["path"] }
        if (paths.isEmpty()) return@getOrPut emptyList()
        val recorded = git.check("--literal-pathspecs", "ls-tree", "-z", commit, "--", *paths.toTypedArray()).stdout.paths()
            .filter { it.startsWith("${Change.GITLINK} ") }
            .associate { it.substringAfter('\t') to it.substringBefore('\t').split(' ')[2] }

        val submodules = variables.mapNotNull { (name, values) ->
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
        submodules.filter { submodule -> names.none { submodule.name.startsWith("$it/") } }.sortedBy { it.path }
    }

    fun repo(name: String, path: String) = Repo(git.repo.gitDir.resolve("modules/$name"), git.repo.workTree.resolve(path))

    /** The `.git` file that makes the folder the submodule's, for the parent's git to find it. */
    fun pointer(path: String) = git.repo.workTree.resolve("$path/.git")

    fun pointerText(name: String, path: String) = "gitdir: ${repo(name, path).gitDir.path}\n"

    /** Whether [pointer], a `.git` file, names a git dir inside this repo's, as only this app's own set-ups do. */
    fun pointsIntoRepo(pointer: File) = gitDirOf(pointer)?.canonicalPath?.startsWith(git.repo.gitDir.canonicalPath + File.separator) == true

    fun isSetUp(name: String, path: String) =
        repo(name, path).gitDir.isDirectory && pointer(path).let { it.isFile && it.readText() == pointerText(name, path) }

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

/** The git dir a `.git` file names on its `gitdir:` line, as a submodule's or a separate git dir's folder holds one. */
internal fun gitDirOf(dotGit: File): File? {
    val target = dotGit.readText().lineSequence().firstOrNull()?.takeIf { it.startsWith("gitdir: ") }?.removePrefix("gitdir: ") ?: return null
    return File(target).takeIf(File::isAbsolute) ?: dotGit.parentFile.resolve(target)
}

/**
 * `config --null` entries ("<key>\n<value>") under `<section>.<subsection>.<variable>`, by subsection and variable.
 * A subsection, such as a submodule's name, may hold dots.
 */
internal fun sections(entries: List<String>, section: String): Map<String, Map<String, String>> =
    entries.map { it.substringBefore('\n') to it.substringAfter('\n', "") }
        .filter { (key) -> key.startsWith("$section.") }
        .groupBy({ (key) -> key.removePrefix("$section.").substringBeforeLast('.') }, { (key, value) -> key.substringAfterLast('.') to value })
        .mapValues { (_, values) -> values.toMap() }
