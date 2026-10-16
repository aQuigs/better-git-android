package com.sqftware.safegit.sync

import java.io.File

/**
 * Keeps the submodules of [git]'s repo in step with it during one of its syncs. What the repo records for a submodule
 * is decided here, never guessed by `add --all` from whatever `.git` a folder holds: the repo's add leaves submodule
 * folders out, and [stage] records each submodule that is ready.
 *
 * - A submodule with work of its own, edits or commits on no remote, is [sync]ed like any repo before the repo
 *   records it, so what the repo records is always on the submodule's remote.
 * - One the repo's own update moved follows to what it now records. The commit to follow is kept in the repo's config
 *   until it is reached, so a sync killed in between still follows it rather than recording the old one back.
 * - Otherwise the submodule's commit is this device's to record.
 * - One the repo no longer declares has its files taken out, its work synced first.
 *
 * A conflict or choice inside a submodule stops the sync, as it needs the user. Any other trouble with one submodule
 * leaves it as it is, unrecorded, while the rest syncs, and [problems] says what it was.
 */
internal class SubmoduleSync(
    private val git: RepoGit,
    private val credentials: Credentials,
    private val choices: Map<Choice, Keep>,
    private val step: (Step) -> Unit,
    private val now: () -> Long,
    private val sync: (Repo, Map<Choice, Keep>) -> SyncResult,
) {
    /** By submodule path, why it was left as it was. */
    val problems = sortedMapOf<String, String>()

    private val submodules = Submodules(git)
    private var declared = emptyList<Submodule>()
    private var records = emptyMap<String, Map<String, String>>()
    private val ready = mutableMapOf<String, String>()

    private class Skip(message: String) : Exception(message)

    /**
     * Brings every submodule the repo's HEAD declares up to date, and takes out those it no longer declares. Returns
     * what stops the sync, or null.
     */
    fun bringUp(): SyncResult? {
        ready.clear()
        problems.clear()
        // Every submodule set up here has its git dir there, so a repo with none costs no more git
        val any = git.repo.gitDir.resolve("modules").isDirectory
        records = if (any) readRecords() else emptyMap()
        val head = git.head()
        declared = head?.let { submodules.of(it) }.orEmpty()

        // Git dirs go by name and folders by path, so one renamed or moved is taken out and starts afresh
        val waitingNames = mutableSetOf<String>()
        val waitingPaths = mutableSetOf<String>()
        for ((name, record) in records) {
            val path = record[PATH] ?: continue
            if (declared.any { it.name == name && it.path == path }) continue
            val there = head?.let { git.entry(it, path) }
            attempt(path) { retire(name, path, there?.tree ?: git.emptyTree, keepFolder = there != null) }?.let { return it }
            if (path in problems) {
                waitingNames += name
                waitingPaths += path
            }
        }
        for (submodule in declared) {
            if (submodule.name in waitingNames || submodule.path in waitingPaths) {
                problems.putIfAbsent(submodule.path, "Waiting for the submodule that was there before to be taken out.")
                continue
            }
            attempt(submodule.path) { bringUp(submodule) }?.let { return it }
        }
        return null
    }

    /** Pathspecs that keep the repo's `add --all` out of submodule folders, those being taken out too. */
    fun excludes() = (declared.map { it.path } + records.values.mapNotNull { it[PATH] }).distinct().map { ":(exclude,literal)$it" }

    /** Records in the index the commit of each submodule [bringUp] found ready. */
    fun stage() {
        if (ready.isEmpty()) return
        git.check("update-index", "-z", "--index-info", input = ready.entries.joinToString("") { (path, commit) -> indexEntry(Change.GITLINK, commit, path) })
    }

    /** Before the repo's folder moves from [from] to [to]: each submodule set up here whose commit changes will follow. */
    fun beforeMove(from: String?, to: String) {
        val before = from?.let { submodules.of(it) }.orEmpty().associate { it.path to it.commit }
        for (submodule in submodules.of(to)) {
            val old = before[submodule.path]
            if (old != submodule.commit && submodules.isSetUp(submodule)) {
                git.check("config", key(submodule.name, FOLLOW), submodule.commit)
                if (old != null) git.check("config", key(submodule.name, FROM), old)
            }
        }
    }

    private fun attempt(path: String, action: () -> SyncResult?): SyncResult? = try {
        action().also { problems.remove(path) }
    } catch (e: Skip) {
        problems[path] = e.message.orEmpty()
        null
    }

    private fun bringUp(submodule: Submodule): SyncResult? {
        val new = !submodules.isSetUp(submodule)
        if (new) setUp(submodule)
        val sub = git.at(submodules.repo(submodule))
        FolderUpdate(sub).resume()?.let { throw Skip(it.detail) }
        val inner = SubmoduleSync(sub, credentials, within(submodule.path), step, now, sync)
        val record = records[submodule.name].orEmpty()

        if (hasWorkOfItsOwn(sub, submodule.commit, untracked = { true })) syncWork(sub, submodule.path)?.let { return it }
        val head = sub.head()
        // The repo's update moved it, or it is new here: it takes the recorded commit, unless this device moved it too
        if (head != submodule.commit && (new || head == null || record[FOLLOW] == submodule.commit)) {
            settle(sub, inner, submodule, head, from = record[FROM])?.let { return it }
        }
        for (variable in listOf(FOLLOW, FROM)) {
            if (variable in record) git.check("config", "--unset", key(submodule.name, variable))
        }

        inner.bringUp()?.let { return it.within(submodule.path) }
        inner.problems.forEach { (path, problem) -> problems["${submodule.path}/$path"] = problem }
        sub.head()?.let { ready[submodule.path] = it }
        return null
    }

    /**
     * Moves the submodule from [head] to the commit the repo records, or keeps [head] where it builds on that commit.
     * Where this device and the remote moved it apart, [head] having left [from], it is a choice.
     */
    private fun settle(sub: RepoGit, inner: SubmoduleSync, submodule: Submodule, head: String?, from: String?): SyncResult? {
        val commit = submodule.commit
        fetchCommit(sub, commit)
        when {
            head == null || head == from || sub.isAncestor(head, commit) -> follow(sub, inner, head, commit)
            sub.isAncestor(commit, head) -> Unit
            else -> when (choices[Choice(submodule.path, local = head, remote = commit)]) {
                Keep.LOCAL -> Unit
                Keep.REMOTE -> follow(sub, inner, head, commit)
                null -> return SyncResult.Choose(listOf(Choice(submodule.path, local = head, remote = commit)))
            }
        }
        return null
    }

    /** Connects the submodule's folder to its remote, taking over a `.git` file left there when it is at rest. */
    private fun setUp(submodule: Submodule) {
        val repo = submodules.repo(submodule)
        val pointer = submodules.pointer(submodule)
        if (pointer.isDirectory) throw Skip("Its folder has a repo of its own, which SafeGit leaves alone.")
        // One of this repo's own git dirs is never taken over: it is a submodule an earlier set-up or take-out left
        if (pointer.isFile && submodules.pointsIntoRepo(pointer)) pointer.delete()
        // Left by a sync killed while setting it up, or by a submodule taken out before: kept, out of the way
        if (repo.gitDir.exists()) moveAside(repo.gitDir)

        repo.workTree.mkdirs()
        val setUp = RepoSetup(git.cli, git.identity).connect(submodule.url, repo.gitDir, repo.workTree, credentials, submodule.branch, submodule = true)
        if (setUp is RepoSetup.Result.Failed) throw Skip(setUp.detail.ifEmpty { "It could not be set up." })
        fetch(git.at(repo))
        // Last, so that until the set-up is whole the next sync starts it again
        git.check("config", key(submodule.name, PATH), submodule.path)
        pointer.writeText(submodules.pointerText(submodule))
    }

    private fun fetchCommit(sub: RepoGit, commit: String) {
        if (sub.resolve("$commit^{commit}") != null) return
        fetch(sub)
        // A commit on no branch is fetched by its id, which GitHub allows for any commit it holds
        if (sub.resolve("$commit^{commit}") == null) fetch(sub, commit, required = false)
        if (sub.resolve("$commit^{commit}") == null) throw Skip("Its remote has no commit $commit. Push it from where it was made.")
    }

    private fun follow(sub: RepoGit, inner: SubmoduleSync, head: String?, commit: String) {
        inner.beforeMove(head, commit)
        step(Step.UPDATE_FOLDER)
        move(sub, head, commit)
    }

    /** Syncs work made in a submodule, but only onto the branch the commit the repo records belongs to. */
    private fun syncWork(sub: RepoGit, path: String): SyncResult? {
        val branch = sub.output("symbolic-ref", "--quiet", "--short", "HEAD") ?: throw Skip("It is not on a branch.")
        fetch(sub)
        val upstream = "refs/remotes/origin/$branch"
        if (sub.resolve(upstream) != null && sub.head() != null) {
            val offUpstream = sub.check("rev-list", "--count", "HEAD", "--not", upstream).stdout.trim()
            val own = sub.check("rev-list", "--count", "HEAD", "--not", "--remotes").stdout.trim()
            // Pushing would publish another branch's commits onto this one
            if (offUpstream != own) throw Skip("Its commit is on another branch than $branch, so SafeGit won't sync changes made in it.")
        }

        return when (val result = sync(sub.repo, within(path))) {
            SyncResult.UpToDate, is SyncResult.Synced -> null
            is SyncResult.Failed -> throw Skip(listOf(result.problem.name, result.detail).filter { it.isNotEmpty() }.joinToString(": "))
            else -> result.within(path)
        }
    }

    /**
     * Takes the submodule [name] at [path] out of the folder, leaving only the files of [keep], the tree the repo itself
     * now has there. Its work is synced first, and files it alone holds stop it, so nothing of its own is lost.
     */
    private fun retire(name: String, path: String, keep: String, keepFolder: Boolean): SyncResult? {
        val submodule = Submodule(name, path, url = "", branch = null, commit = "")
        val repo = submodules.repo(submodule)
        if (submodules.isSetUp(submodule)) {
            val sub = git.at(repo)
            // [keep] is the parent's, and so are the files it names
            repo.gitDir.resolve("objects/info/alternates").apply { parentFile.mkdirs() }.writeText("${git.repo.gitDir.resolve("objects").absolutePath}\n")
            FolderUpdate(sub).resume()?.let { throw Skip(it.detail) }
            val kept = sub.check("ls-tree", "-r", "-z", "--name-only", keep).stdout.paths().toSet()
            // Off its branch means an earlier sync already began taking it out
            val onBranch = sub.output("symbolic-ref", "--quiet", "HEAD") != null
            if (onBranch && hasWorkOfItsOwn(sub, recorded = null, untracked = { it !in kept })) {
                syncWork(sub, path)?.let { return it }
            }

            val inner = SubmoduleSync(sub, credentials, within(path), step, now, sync)
            for ((innerName, record) in inner.readRecords()) {
                val innerPath = record[PATH] ?: continue
                val there = sub.entry(keep, innerPath)
                inner.retire(innerName, innerPath, there?.tree ?: sub.emptyTree, keepFolder = there != null)?.let { return it.within(path) }
            }

            val head = sub.head()
            if (head != null && sub.resolve("$head^{tree}") != keep) {
                // Off the branch first, so that no later sync could push the commit that takes its files out
                if (onBranch) sub.check("update-ref", "--no-deref", "HEAD", head)
                val out = sub.check("commit-tree", keep, "-p", head, "-m", "Taken out of the parent repo").stdout.trim()
                step(Step.UPDATE_FOLDER)
                try {
                    move(sub, head, out)
                } catch (e: Skip) {
                    sub.output("for-each-ref", "--count=1", "--points-at", head, "--format=%(refname)", "refs/heads")?.takeIf { it.isNotEmpty() }
                        ?.let { sub.check("symbolic-ref", "HEAD", it) }
                    throw e
                }
            }

            val left = repo.workTree.walk().filter { it.isFile }.map { it.relativeTo(repo.workTree).invariantSeparatorsPath }
                .filter { it != ".git" && it !in kept }.sorted().toList()
            if (left.isNotEmpty()) throw Skip("It is no longer in the repo, but SafeGit won't delete files only it had: ${left.joinToString(", ")}")
            submodules.pointer(submodule).delete()
            if (!keepFolder && repo.workTree.walk().all { it.isDirectory }) repo.workTree.deleteRecursively()
        }
        if (repo.gitDir.exists()) moveAside(repo.gitDir)
        git.check("config", "--remove-section", "$SET_UP.$name")
        return null
    }

    private fun fetch(sub: RepoGit, vararg what: String, required: Boolean = true) {
        step(Step.FETCH)
        val fetch = sub.run("fetch", "--quiet", "--no-recurse-submodules", "origin", *what, environment = credentials.environment())
        if (required && !fetch.ok) throw Skip(fetch.stderr.trim())
    }

    private fun move(sub: RepoGit, from: String?, to: String) {
        when (val moved = FolderUpdate(sub).move(from = from ?: sub.emptyTree, to = to)) {
            FolderUpdate.Result.Done -> Unit
            is FolderUpdate.Result.Blocked -> throw Skip("These files are in the way: ${moved.paths.joinToString(", ")}")
            is FolderUpdate.Result.WriteFailed -> throw Skip(moved.detail)
        }
    }

    /** Edits, files [untracked] counts, or commits on no remote and not [recorded]: made here, or by a stopped sync. */
    private fun hasWorkOfItsOwn(sub: RepoGit, recorded: String?, untracked: (String) -> Boolean): Boolean {
        val status = sub.check("status", "--porcelain", "-z", "--untracked-files=all").stdout.paths()
        if (status.any { !it.startsWith("?? ") || untracked(it.removePrefix("?? ").trimEnd('/')) }) return true
        val head = sub.head() ?: return false
        val known = listOfNotNull(recorded?.let { sub.resolve("$it^{commit}") })
        return sub.check("rev-list", "-n", "1", head, "--not", "--remotes", *known.toTypedArray()).stdout.isNotBlank()
    }

    private class Entry(val type: String, val id: String) {
        val tree get() = id.takeIf { type == "tree" }
    }

    /** What [treeish] holds at [path]: a tree, a file, or a submodule's commit, which this repo never has. */
    private fun RepoGit.entry(treeish: String, path: String) = check("ls-tree", "-z", treeish, "--", path).stdout.paths().firstOrNull()
        ?.substringBefore('\t')?.split(' ')?.let { Entry(type = it[1], id = it[2]) }

    private fun RepoGit.isAncestor(ancestor: String, of: String) = probe("merge-base", "--is-ancestor", ancestor, of).exitCode == 0

    private fun readRecords(): Map<String, Map<String, String>> =
        git.output("config", "--null", "--get-regexp", "^$SET_UP\\.")?.paths().orEmpty()
            .map { it.substringBefore('\n').removePrefix("$SET_UP.") to it.substringAfter('\n', "") }
            .groupBy({ (key) -> key.substringBeforeLast('.') }, { (key, value) -> key.substringAfterLast('.') to value })
            .mapValues { (_, entries) -> entries.toMap() }

    private fun moveAside(gitDir: File) {
        val aside = generateSequence(0) { it + 1 }.map { gitDir.resolveSibling("${gitDir.name}.${now()}.$it.old") }.first { !it.exists() }
        if (!gitDir.renameTo(aside)) throw Skip("Could not move $gitDir aside.")
    }

    private fun within(path: String) =
        choices.filterKeys { it.path.startsWith("$path/") }.mapKeys { (choice) -> choice.copy(path = choice.path.removePrefix("$path/")) }

    /** A submodule's result as its parent reports it, its paths named from the parent's folder. */
    private fun SyncResult.within(path: String) = when (this) {
        is SyncResult.Conflict -> SyncResult.Conflict(paths.map { "$path/$it" })
        is SyncResult.Choose -> SyncResult.Choose(choices.map { it.copy(path = "$path/${it.path}") })
        else -> this
    }

    private companion object {
        /**
         * Where a repo's config keeps each submodule set up in its folder, `<SET_UP>.<name>.<variable>`: its [PATH], and
         * while it has yet to follow an update of the repo, the commit to [FOLLOW] and the one it moves [FROM].
         */
        const val SET_UP = "safegit-submodule"
        const val PATH = "path"
        const val FOLLOW = "follow"
        const val FROM = "from"

        fun key(name: String, variable: String) = "$SET_UP.$name.$variable"
    }
}
