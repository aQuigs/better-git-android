package com.sqftware.safegit.sync

import java.io.File

/** What syncing a submodule's own repo came to, with the submodules inside it left as they were. */
internal data class NestedSync(val result: SyncResult, val problems: Map<String, String>)

/**
 * Keeps the submodules of [git]'s repo in step with it during one of its syncs (CLAUDE.md rule 6). What the repo
 * records for a submodule is decided here, never guessed by `add --all` from whatever `.git` a folder holds: the repo's
 * add leaves submodule folders out ([excludes]), and [stage] records each submodule [bringUp] found ready.
 */
internal class SubmoduleSync(
    private val git: RepoGit,
    private val credentials: Credentials,
    private val choices: Map<Choice, Keep>,
    private val step: (Step) -> Unit,
    private val now: () -> Long,
    private val sync: (Repo, Map<Choice, Keep>) -> NestedSync,
) {
    /** By submodule path, why it was left as it was. */
    val problems = sortedMapOf<String, String>()

    private val submodules = Submodules(git)
    private val records: MutableMap<String, MutableMap<String, String>> by lazy {
        // Every submodule set up here has its git dir there, so a repo with none costs no git
        val any = git.repo.gitDir.resolve("modules").list().orEmpty().isNotEmpty()
        val read = if (any) git.output("config", "--null", "--get-regexp", "^$SET_UP\\.")?.paths().orEmpty() else emptyList()
        sections(read, SET_UP).mapValues { (_, values) -> values.toMutableMap() }.toMutableMap()
    }
    private var declared = emptyList<Submodule>()
    private val ready = mutableMapOf<String, String>()

    private class Skip(message: String) : Exception(message)

    /**
     * Brings every submodule the repo's HEAD declares up to date, or with [onlyMoved], those an update of the repo
     * has just moved or added. Returns what stops the sync, or null.
     */
    fun bringUp(onlyMoved: Boolean = false): SyncResult? {
        ready.clear()
        // Those it passes over keep what the last full pass found
        if (!onlyMoved) problems.clear()
        declared = git.head()?.let { submodules.of(it) }.orEmpty()
        retireUndeclared(declared)?.let { return it }
        val known = declared.map { it.path } + records.values.mapNotNull { it[PATH] }
        problems.keys.retainAll { path -> known.any { path == it || path.startsWith("$it/") } }

        for (submodule in declared) {
            val record = records[submodule.name]
            when {
                record != null && record[PATH] != submodule.path || submodule.path in problems ->
                    problems.putIfAbsent(submodule.path, "Waiting for the submodule that was there before to be taken out.")
                onlyMoved && record != null && FOLLOW !in record -> Unit
                else -> attempt(submodule.path) { bringUp(submodule) }?.let { return it }
            }
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

    /**
     * Before the repo's folder moves from [from] to [to]: each submodule set up here whose commit changes will follow,
     * and each [to] no longer declares is taken out first, so that the repo's own files at its path land in a cleared
     * folder. Returns what stops the sync, or null.
     */
    fun beforeMove(from: String?, to: String): SyncResult? {
        val before = from?.let { submodules.of(it) }.orEmpty().associate { it.path to it.commit }
        val after = submodules.of(to)
        for (submodule in after) {
            val record = records[submodule.name] ?: continue
            val old = before[submodule.path]
            if (old != submodule.commit && record[PATH] == submodule.path) {
                remember(submodule.name, FOLLOW, submodule.commit)
                if (old != null) remember(submodule.name, FROM, old)
            }
        }
        val leaving = records.filter { (name, record) -> after.none { it.name == name && it.path == record[PATH] } }.values.mapNotNull { it[PATH] }
        retireUndeclared(after)?.let { return it }
        // One still in its folder would have the repo's own files written in among its own; a submodule there would not
        val blocking = leaving.filter { path ->
            path in problems && git.check("--literal-pathspecs", "ls-tree", to, "--", path).stdout.let { it.isNotEmpty() && !it.startsWith("${Change.GITLINK} ") }
        }
        return if (blocking.isEmpty()) null else SyncResult.Failed(Problem.FOLDER_BLOCKED, blocking.joinToString("\n") { "$it: ${problems[it]}" })
    }

    private fun retireUndeclared(declared: List<Submodule>): SyncResult? {
        for ((name, record) in records.toMap()) {
            val path = record[PATH] ?: continue
            if (declared.none { it.name == name && it.path == path }) attempt(path) { retire(name, path) }?.let { return it }
        }
        return null
    }

    private fun attempt(path: String, action: () -> SyncResult?): SyncResult? = try {
        action().also { problems.remove(path) }
    } catch (e: Skip) {
        problems[path] = e.message.orEmpty()
        null
    }

    private fun bringUp(submodule: Submodule): SyncResult? {
        val (name, path) = submodule
        val new = !submodules.isSetUp(name, path)
        if (new) setUp(submodule)
        val sub = git.at(submodules.repo(name, path))
        FolderUpdate(sub).resume()?.let { throw Skip(it.detail) }
        val record = records[name].orEmpty()

        var head = sub.head()
        val worked = hasWorkOfItsOwn(sub, head, recorded = submodule.commit)
        if (worked) {
            syncWork(sub, path)?.let { return it }
            head = sub.head()
        }
        // The repo's update moved it, or it is new here: it takes the recorded commit, unless this device moved it too
        var moved = false
        if (head != submodule.commit && (new || head == null || record[FOLLOW] == submodule.commit)) {
            settle(sub, submodule, head, from = record[FROM])?.let { return it }
            moved = sub.head() != head
            head = sub.head()
        }
        for (variable in listOf(FOLLOW, FROM)) forget(name, variable)

        // A sync of its work has brought its own submodules up already
        if (!worked || moved) {
            val inner = nested(sub, path)
            inner.bringUp()?.let { return it.within(path) }
            inner.problems.forEach { (innerPath, problem) -> problems["$path/$innerPath"] = problem }
        }
        head?.let { ready[path] = it }
        return null
    }

    /**
     * Moves the submodule from [head] to the commit the repo records, or keeps [head] where it builds on that commit.
     * Where this device and the remote moved it apart, [head] having left [from], it is a choice.
     */
    private fun settle(sub: RepoGit, submodule: Submodule, head: String?, from: String?): SyncResult? {
        val commit = submodule.commit
        fetchCommit(sub, commit)
        val choice = Choice(submodule.path, local = head, remote = commit)
        return when {
            head == null || head == from || sub.isAncestor(head, commit) -> follow(sub, submodule.path, head, commit)
            sub.isAncestor(commit, head) -> null
            else -> when (choices[choice]) {
                Keep.LOCAL -> null
                Keep.REMOTE -> follow(sub, submodule.path, head, commit)
                null -> SyncResult.Choose(listOf(choice))
            }
        }
    }

    /** Connects the submodule's folder to its remote, taking over a `.git` file left there when it is at rest. */
    private fun setUp(submodule: Submodule) {
        val (name, path) = submodule
        val repo = submodules.repo(name, path)
        val pointer = submodules.pointer(path)
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
        remember(name, PATH, path)
        pointer.writeText(submodules.pointerText(name, path))
    }

    private fun fetchCommit(sub: RepoGit, commit: String) {
        if (sub.resolve("$commit^{commit}") != null) return
        fetch(sub)
        // A commit on no branch is fetched by its id, which GitHub allows for any commit it holds
        if (sub.resolve("$commit^{commit}") == null) fetch(sub, commit, required = false)
        if (sub.resolve("$commit^{commit}") == null) throw Skip("Its remote has no commit $commit. Push it from where it was made.")
    }

    private fun follow(sub: RepoGit, path: String, head: String?, commit: String): SyncResult? {
        when (val stop = nested(sub, path).beforeMove(head, commit)) {
            null -> Unit
            is SyncResult.Failed -> throw Skip(stop.detail)
            else -> return stop.within(path)
        }
        // Before the move, so that however it ends, a commit this device only followed never counts as its work
        sub.check("update-ref", FOLLOWED, commit)
        step(Step.UPDATE_FOLDER)
        move(sub, head, commit)
        return null
    }

    /** Syncs work made in a submodule, but only onto the branch the commit the repo records belongs to. */
    private fun syncWork(sub: RepoGit, path: String): SyncResult? {
        val branch = sub.branch() ?: throw Skip("It is not on a branch.")
        fetch(sub)
        val upstream = sub.upstream(branch)
        if (upstream != null && sub.resolve(upstream) != null && sub.head() != null) {
            val offUpstream = sub.check("rev-list", "--count", "HEAD", "--not", upstream).stdout.trim()
            val own = sub.check("rev-list", "--count", "HEAD", "--not", "--remotes").stdout.trim()
            // Pushing would publish another branch's commits onto this one
            if (offUpstream != own) throw Skip("Its commit is on another branch than $branch, so SafeGit won't sync changes made in it.")
        }

        val synced = sync(sub.repo, within(path))
        synced.problems.forEach { (innerPath, problem) -> problems["$path/$innerPath"] = problem }
        return when (val result = synced.result) {
            SyncResult.UpToDate, is SyncResult.Synced -> null
            is SyncResult.Failed -> throw Skip(listOf(result.problem.name, result.detail).filter { it.isNotEmpty() }.joinToString(": "))
            else -> result.within(path)
        }
    }

    /**
     * Takes the submodule [name] out of its folder at [path]. Its work is synced first, and files it alone holds, such as
     * ignored ones, stop it, so nothing of its own is lost.
     */
    private fun retire(name: String, path: String): SyncResult? {
        val repo = submodules.repo(name, path)
        if (submodules.isSetUp(name, path)) {
            val sub = git.at(repo)
            FolderUpdate(sub).resume()?.let { throw Skip(it.detail) }
            // Off its branch means an earlier sync already began taking it out
            val onBranch = sub.branch() != null
            val recorded = git.head()?.let { git.check("--literal-pathspecs", "ls-tree", it, "--", path).stdout }
                ?.takeIf { it.startsWith("${Change.GITLINK} ") }?.substringBefore('\t')?.split(' ')?.get(2)
            if (onBranch && hasWorkOfItsOwn(sub, sub.head(), recorded)) syncWork(sub, path)?.let { return it }

            val inner = nested(sub, path)
            inner.retireUndeclared(emptyList())?.let { return it.within(path) }
            inner.problems.entries.firstOrNull()?.let { (innerPath, problem) -> throw Skip("$innerPath: $problem") }

            val head = sub.head()
            if (head != null && sub.resolve("$head^{tree}") != sub.emptyTree) {
                // Off the branch first, so that no later sync could push the commit that takes its files out
                if (onBranch) sub.check("update-ref", "--no-deref", "HEAD", head)
                val out = sub.check("commit-tree", sub.emptyTree, "-p", head, "-m", "Taken out of the parent repo").stdout.trim()
                step(Step.UPDATE_FOLDER)
                try {
                    move(sub, head, out)
                } catch (e: Skip) {
                    sub.output("for-each-ref", "--count=1", "--points-at", head, "--format=%(refname)", "refs/heads")?.takeIf { it.isNotEmpty() }
                        ?.let { sub.check("symbolic-ref", "HEAD", it) }
                    throw e
                }
            }

            val pointer = submodules.pointer(path)
            val left = repo.workTree.walk().filter { it.isFile && it != pointer }.map { it.relativeTo(repo.workTree).invariantSeparatorsPath }.sorted().toList()
            if (left.isNotEmpty()) throw Skip("It is no longer in the repo, but SafeGit won't delete files only it had: ${left.joinToString(", ")}")
            pointer.delete()
            repo.workTree.deleteRecursively()
        }
        if (repo.gitDir.exists()) moveAside(repo.gitDir)
        if (records.remove(name) != null) git.check("config", "--remove-section", "$SET_UP.$name")
        return null
    }

    private fun nested(sub: RepoGit, path: String) = SubmoduleSync(sub, credentials, within(path), step, now, sync)

    private fun fetch(sub: RepoGit, vararg what: String, required: Boolean = true) {
        step(Step.FETCH)
        val fetch = sub.run("fetch", "--quiet", "--no-recurse-submodules", "origin", *what, environment = credentials.environment())
        if (required && !fetch.ok) throw Skip(fetch.stderr.trim())
    }

    private fun move(sub: RepoGit, from: String?, to: String) {
        when (val moved = FolderUpdate(sub).move(from = from ?: sub.emptyTree, to = to)) {
            is FolderUpdate.Result.Done -> Unit
            is FolderUpdate.Result.Blocked -> throw Skip("These files are in the way: ${moved.paths.joinToString(", ")}")
            is FolderUpdate.Result.WriteFailed -> throw Skip(moved.detail)
        }
    }

    /**
     * Edits, untracked files, or commits this device made, by a sync stopped before its push or otherwise: those on no
     * remote branch or tag, and neither [recorded] nor the last commit it followed, which may have come by its id.
     */
    private fun hasWorkOfItsOwn(sub: RepoGit, head: String?, recorded: String?): Boolean {
        if (sub.check("status", "--porcelain", "-z").stdout.isNotEmpty()) return true
        if (head == null || head == recorded) return false
        val known = listOfNotNull(recorded?.let { sub.resolve("$it^{commit}") }, sub.resolve(FOLLOWED))
        return sub.check("rev-list", "-n", "1", head, "--not", "--remotes", "--tags", *known.toTypedArray()).stdout.isNotBlank()
    }

    private fun RepoGit.isAncestor(ancestor: String, of: String) = probe("merge-base", "--is-ancestor", ancestor, of).exitCode == 0

    private fun remember(name: String, variable: String, value: String) {
        git.check("config", "$SET_UP.$name.$variable", value)
        records.getOrPut(name) { mutableMapOf() }[variable] = value
    }

    private fun forget(name: String, variable: String) {
        if (records[name]?.remove(variable) != null) git.check("config", "--unset", "$SET_UP.$name.$variable")
    }

    /** Into a folder of its own, where it can never pass for a submodule's git dir. */
    private fun moveAside(gitDir: File) {
        val folder = git.repo.gitDir.resolve(ASIDE).apply { mkdirs() }
        val aside = generateSequence(0) { it + 1 }.map { folder.resolve("${gitDir.name}.${now()}.$it") }.first { !it.exists() }
        if (!gitDir.renameTo(aside)) throw Skip("Could not move $gitDir aside.")
    }

    private fun within(path: String) =
        choices.filterKeys { it.path.startsWith("$path/") }.mapKeys { (choice) -> choice.copy(path = choice.path.removePrefix("$path/")) }

    /** A submodule's result as its parent reports it, its paths named from the parent's folder. */
    private fun SyncResult.within(path: String) = when (this) {
        is SyncResult.Conflict -> SyncResult.Conflict(paths.map { "$path/$it" })
        is SyncResult.ConflictAhead -> SyncResult.ConflictAhead(paths.map { "$path/$it" })
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
        const val ASIDE = "safegit-modules-aside"

        /** In a submodule's own repo, the last commit it followed. */
        const val FOLLOWED = "refs/safegit/followed"
    }
}
