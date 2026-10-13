package com.sqftware.safegit.sync

import com.sqftware.safegit.git.CliGit
import com.sqftware.safegit.git.Git
import com.sqftware.safegit.git.GitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Random play against the rules SafeSync exists for. Two phones and a laptop edit, add, delete and rename files by case.
 * The phones sync with faults injected: the app killed at a git call or part way through moving the folder, git itself
 * killed, a file saved mid-sync, a folder that cannot be written. Every line holds a unique number, so lines show what
 * a merge kept.
 * - After every sync, each version of a file a phone's user wrote is still in a phone's folder or some repo's history,
 *   unless that user replaced it before any sync committed it.
 * - A conflict is only ever reported on files both sides changed.
 * - At the end both phones sync until quiet and must match the remote, which must hold every line a phone's user
 *   wrote, unless someone removed it: by an edit or delete, by resolving or choosing a side of a conflict, or by a force
 *   push. No conflict marker may ever reach it.
 *
 * Runs `soak.rounds` rounds for each of `soak.seeds` seeds (system properties, e.g. `-Dsoak.seeds=40`); a failure names
 * its seed and the steps before it, and `-Dsoak.seed=<n>` replays one.
 */
class SafeSyncSoakTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val git = CliGit("git")

    @Test
    fun neverLosesWork() {
        val only = System.getProperty("soak.seed")?.toLong()
        val seeds = only?.let { listOf(it) } ?: (1L..(System.getProperty("soak.seeds")?.toLong() ?: SEEDS))
        val rounds = System.getProperty("soak.rounds")?.toInt() ?: ROUNDS
        for (seed in seeds) {
            Soak(seed, rounds).run()
        }
    }

    private inner class Soak(private val seed: Long, private val rounds: Int) {
        private val random = Random(seed)
        private val root = temp.newFolder("seed$seed")
        private val remote = root.resolve("remote.git")
        private val laptop = root.resolve("laptop")
        private val phones: List<Phone>
        private val steps = mutableListOf<String>()
        private var clock = 0L
        private var counter = 0

        /** Every version a phone's user wrote, by blob id, and those its user replaced before any sync committed them. */
        private val written = mutableSetOf<String>()
        private val discarded = mutableSetOf<String>()

        /** Every line a phone's user wrote, with its file's name ignoring case, and the lines someone removed on purpose. */
        private val phoneLines = mutableMapOf<String, String>()
        private val removed = mutableSetOf<String>()

        init {
            git.ok(root, "init", "-q", "--bare", "-b", "main", remote.path)
            git.ok(root, "clone", "-q", remote.path, laptop.path)
            laptop.resolve("notes.md").writeText(lines("laptop"))
            git.ok(laptop, "add", "--all")
            git.ok(laptop, "commit", "-q", "-m", "first")
            git.ok(laptop, "push", "-q", "origin", "HEAD:main")
            phones = listOf(Phone("A"), Phone("B"))
        }

        fun run() {
            try {
                repeat(rounds) {
                    when (random.nextInt(100)) {
                        in 0 until 40 -> userAction(phones.random(random))
                        in 40 until 55 -> laptopAction()
                        else -> {
                            sync(phones.random(random), Fault.entries.random(random))
                            checkNothingLost()
                        }
                    }
                }
                settle()
                checkNothingLost()
                checkTheRemoteHasEveryLine()
            } catch (e: Throwable) {
                val state = phones.joinToString("\n") { it.describe() }
                throw AssertionError("Seed $seed failed after these steps:\n${steps.takeLast(STEPS_SHOWN).joinToString("\n")}\n$state", e)
            }
        }

        // Each file holds a few lines so that edits to different lines merge and edits to the same line conflict
        private fun lines(who: String) = (1..LINES).joinToString("") { "$who ${++counter}\n" }

        private fun userAction(phone: Phone) {
            val files = phone.files()
            when (random.nextInt(3)) {
                0 -> files.randomOrNull(random)?.let { path ->
                    val current = phone.folder.resolve(path).readText().lines().dropLast(1).toMutableList()
                    current[random.nextInt(current.size)] = "phone ${phone.name} ${++counter}"
                    phone.write(path, current.joinToString("") { "$it\n" })
                }
                1 -> NAMES.filter { phone.canCreate(it) }.randomOrNull(random)?.let { phone.write(it, lines("phone ${phone.name}")) }
                else -> files.randomOrNull(random)?.let { phone.delete(it) }
            }
        }

        private fun laptopAction() {
            if (!run(laptop, "pull", "-q", "--rebase").ok) {
                run(laptop, "rebase", "--abort")
                git.ok(laptop, "reset", "-q", "--hard", "origin/main")
            }
            val files = git.ok(laptop, "ls-files", "-z").paths()
            val path = files.randomOrNull(random) ?: return
            when (random.nextInt(6)) {
                0, 1 -> {
                    removed += laptop.resolve(path).readLines()
                    laptop.resolve(path).writeText(lines("laptop"))
                }
                2 -> {
                    removed += laptop.resolve(path).readLines()
                    git.ok(laptop, "rm", "-q", path)
                }
                3 -> run(laptop, "mv", path, path.uppercase().takeIf { it != path } ?: path.lowercase())
                4 -> NAMES.filter { name ->
                    // The laptop's disk may be case-insensitive too, where a name in another case would open an existing file
                    files.map { it.lowercase() }.none { it == name || it.startsWith("$name/") || name.startsWith("$it/") }
                }
                    .randomOrNull(random)?.let { laptop.resolve(it).apply { parentFile.mkdirs() }.writeText(lines("laptop")) }
                else -> {
                    // A force push drops the remote's newest commit, which phones may have pushed
                    if (git.ok(laptop, "rev-list", "--count", "HEAD").toInt() > 1) {
                        val before = linesIn(laptop, "HEAD").values.flatten()
                        git.ok(laptop, "reset", "-q", "--hard", "HEAD~1")
                        git.ok(laptop, "push", "-q", "--force", "origin", "HEAD:main")
                        removed += before - linesIn(laptop, "HEAD").values.flatten().toSet()
                        steps += "laptop force-pushes HEAD~1"
                    }
                    return
                }
            }
            git.ok(laptop, "add", "--all")
            if (!run(laptop, "commit", "-q", "-m", "laptop ${++counter}").ok) return
            if (!run(laptop, "push", "-q", "origin", "HEAD:main").ok) {
                git.ok(laptop, "fetch", "-q")
                git.ok(laptop, "reset", "-q", "--hard", "origin/main")
            }
            steps += "laptop changes $path and pushes"
        }

        private fun sync(phone: Phone, fault: Fault) {
            val at = random.nextInt(1, CALLS)
            var readOnly: File? = null
            val faulty = when (fault) {
                Fault.APP_KILLED -> git.onCall(at) { throw Killed() }
                Fault.GIT_KILLED -> git.onCall(at) { GitResult(137, "", "Killed") }
                Fault.KILLED_MID_UPDATE -> git.onFirst({ it.updatesIndex }) {
                    phone.undoPartOfTheUpdate()
                    throw Killed()
                }
                Fault.KILLED_FINISHING_UPDATE ->
                    listOf(git.dyingAtTheLastStep(), git.dyingBeforeTheBranchMoves(), git.dyingAfterTheBranchMoved()).random(random)
                Fault.SAVED_MID_SYNC -> git.onCall(at) {
                    userAction(phone)
                    null
                }
                Fault.FOLDER_READ_ONLY -> {
                    readOnly = (listOf(phone.folder) + phone.folder.walk().filter { it.isDirectory }).random(random)
                    readOnly.setWritable(false)
                    git
                }
                Fault.NONE -> git
            }

            val held = phone.holdsConflict()
            val started = clock
            val result = try {
                phone.sync(faulty)
            } catch (_: Killed) {
                null
            } finally {
                readOnly?.setWritable(true)
            }
            steps += "phone ${phone.name} syncs with $fault at call $at: ${result ?: "killed"}"
            answer(phone, result, held, started, always = false)
        }

        /** What the user does about a conflict: resolve the markers or choose a side, or, unless [always], leave it. */
        private fun answer(phone: Phone, result: SyncResult?, held: Boolean, started: Long, always: Boolean) {
            when (result) {
                is SyncResult.Conflict -> {
                    // A held conflict reports what is left of one checked when it was made
                    if (!held) phone.checkBothSidesChanged(result.paths, phone.backupSince(started) ?: return)
                    if (always || random.nextBoolean()) phone.resolve(result.paths)
                }
                is SyncResult.Choose -> {
                    phone.checkBothSidesChanged(result.paths, "HEAD")
                    if (always || random.nextBoolean()) {
                        val choices = result.paths.associateWith { Keep.entries.random(random) }
                        phone.choosing(choices)
                        val next = clock
                        val chosen = phone.sync(choices = choices)
                        steps += "phone ${phone.name} chooses $choices: $chosen"
                        answer(phone, chosen, held = false, started = next, always)
                    }
                }
                else -> {}
            }
        }

        /** Both phones sync until neither has anything left to do, then must hold exactly what the remote holds. */
        private fun settle() {
            repeat(SETTLE_ROUNDS) {
                val results = phones.map { phone ->
                    val held = phone.holdsConflict()
                    val started = clock
                    phone.sync().also {
                        steps += "phone ${phone.name} settles: $it"
                        answer(phone, it, held, started, always = true)
                    }
                }
                if (results.all { it == SyncResult.UpToDate }) {
                    val main = git.ok(remote, "rev-parse", "main")
                    for (phone in phones) {
                        assertEquals("phone ${phone.name}'s branch", main, phone.gitOk("rev-parse", "HEAD"))
                        assertEquals("phone ${phone.name}'s folder", "", phone.gitOk("status", "--porcelain"))
                    }
                    return
                }
            }
            throw AssertionError("The phones never settled")
        }

        private fun checkNothingLost() {
            val inGit = (phones.map { it.repo.gitDir } + remote + laptop.resolve(".git")).flatMapTo(mutableSetOf(), ::objects)
            val inFolders = phones.flatMapTo(mutableSetOf()) { phone -> phone.files().map { blobId(phone.folder.resolve(it).readText()) } }
            val lost = written - discarded - inGit - inFolders
            assertTrue("Lost versions: $lost", lost.isEmpty())
        }

        private fun checkTheRemoteHasEveryLine() {
            val files = linesIn(remote, "main")
            val missing = phoneLines.filter { (line, path) -> line !in removed && line !in files[path].orEmpty() }
            assertTrue("Lines the remote lost: $missing", missing.isEmpty())

            val revisions = git.ok(remote, "rev-list", "--all").lines()
            val markers = run(remote, "grep", "-E", "^(<<<<<<<|>>>>>>>)", *revisions.toTypedArray())
            assertEquals("Conflict markers on the remote: ${markers.stdout}", 1, markers.exitCode)
        }

        /** The id of every object in the history of any ref in [gitDir]. */
        private fun objects(gitDir: File) =
            git.ok(root, "--git-dir=${gitDir.path}", "rev-list", "--all", "--objects").lines().map { it.substringBefore(" ") }

        /** The lines of each file in [commit], by its name ignoring case. */
        private fun linesIn(repo: File, commit: String): Map<String, Set<String>> =
            git.ok(repo, "ls-tree", "-r", "-z", commit).paths().associate { entry ->
                entry.substringAfter("\t").lowercase() to git.ok(repo, "cat-file", "blob", entry.substringBefore("\t").split(" ")[2]).lines().toSet()
            }

        private fun run(dir: File, vararg args: String) = git.run(dir, args.toList(), LAPTOP.environment)

        private inner class Phone(val name: String) {
            val repo = git.splitClone(remote, gitDir = root.resolve("phone$name.git"), workTree = root.resolve("phone$name"))
            val folder = repo.workTree
            private val identity = Identity("Phone $name", "$name@example.com")
            private val repoGit = RepoGit(git, repo, identity)

            fun sync(git: Git = this@SafeSyncSoakTest.git, choices: Map<String, Keep> = emptyMap()) =
                SafeSync(git, identity, "Phone $name", now = { ++clock }).sync(repo, choices = choices)

            fun holdsConflict() = repoGit.resolve("refs/safegit/conflict").let { it != null && it == repoGit.head() }

            /** The branch as the sync that started at [started] found it, kept in the backup it made last. */
            fun backupSince(started: Long) = gitOk("for-each-ref", "--format=%(refname)", "refs/safegit/backup/").lines()
                .filter { it.isNotEmpty() && it.substringAfterLast("/").toLong() > started }
                .maxByOrNull { it.substringAfterLast("/").toLong() }

            fun gitOk(vararg args: String) = repoGit.check(*args).stdout.trim()

            fun describe() = """
                |phone $name folder: ${files()}
                |  index: ${repoGit.run("ls-files", "-z").stdout.paths()}
                |  status: ${repoGit.run("status", "--porcelain", "-z").stdout.paths()}
                |  HEAD to the remote's: ${repoGit.run("diff", "--name-status", "-z", "HEAD", "refs/remotes/origin/main").stdout.paths()}
            """.trimMargin()

            fun files() = folder.walk().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.sorted().toList()

            fun canCreate(path: String): Boolean {
                val file = folder.resolve(path)
                return !file.exists() && generateSequence(file.parentFile) { it.parentFile }.takeWhile { it != folder }.none { it.isFile }
            }

            fun write(path: String, text: String) {
                replacing(path, text.lines())
                folder.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
                written += blobId(text)
                for (line in text.lines().filter { it.startsWith("phone ") }) phoneLines.putIfAbsent(line, path.lowercase())
                steps += "phone $name writes $path"
            }

            fun delete(path: String) {
                replacing(path, emptyList())
                folder.resolve(path).delete()
                steps += "phone $name deletes $path"
            }

            /** The user removes the lines they replace, and a version no sync committed yet is theirs to lose. */
            private fun replacing(path: String, newLines: List<String>) {
                val file = folder.resolve(path)
                if (!file.isFile) return
                val old = file.readText()
                removed += old.lines() - newLines.toSet()
                val id = blobId(old)
                if (id in written && id !in objects(repo.gitDir)) discarded += id
            }

            /** Names changed from [from] to [to], ignoring case, where a rename by case alone is no change. */
            private fun changed(from: String, to: String): List<String> {
                val changes = repoGit.diff(from, to)
                fun key(change: Change) = when {
                    change.newMode == Change.ABSENT -> change.path.lowercase() to change.oldBlob
                    change.oldMode == Change.ABSENT -> change.path.lowercase() to change.newBlob
                    else -> null
                }
                // Such a rename is one name gone and one added in another case, holding the same content
                val renamed = changes.mapNotNull(::key).groupingBy { it }.eachCount().filterValues { it == 2 }.keys
                return changes.filter { key(it) !in renamed }.map { it.path.lowercase() }
            }

            /**
             * A conflict is only for files both sides changed since they parted: under the reported name, the same
             * name in another case, or a file or folder it clashes with, without the "~<side>" git adds to a clash.
             */
            fun checkBothSidesChanged(reported: List<String>, head: String) {
                val upstream = "refs/remotes/origin/main"
                val base = repoGit.output("merge-base", "--fork-point", upstream, head) ?: repoGit.output("merge-base", head, upstream) ?: return
                val ours = changed(base, head)
                val theirs = changed(base, upstream)
                for (path in reported.map { it.replace(Regex("~[^/]*$"), "").lowercase() }) {
                    fun touches(changes: List<String>) = changes.any { it == path || it.startsWith("$path/") || path.startsWith("$it/") }
                    assertTrue("Conflict on $path, which only one side changed", touches(ours) && touches(theirs))
                }
            }

            /** Resolves each marked file by keeping one side or both, as a user editing it would. */
            fun resolve(paths: List<String>) {
                for (path in paths.filter { folder.resolve(it).isFile }) {
                    val keep = listOf(setOf(OURS), setOf(THEIRS), setOf(OURS, THEIRS)).random(random)
                    var section: Int? = null
                    val kept = folder.resolve(path).readText().removeSuffix("\n").split("\n").filter { line ->
                        when {
                            line.startsWith("<<<<<<<") -> false.also { section = OURS }
                            line == "=======" && section == OURS -> false.also { section = THEIRS }
                            line.startsWith(">>>>>>>") -> false.also { section = null }
                            else -> section == null || section in keep
                        }
                    }
                    write(path, kept.joinToString("") { "$it\n" })
                    steps += "phone $name resolves $path keeping ${if (keep.size == 2) "both" else if (OURS in keep) "its own" else "the remote's"}"
                }
            }

            /** The lines of the side each choice drops are removed on purpose. */
            fun choosing(choices: Map<String, Keep>) {
                val sides = mapOf(Keep.LOCAL to linesIn(repo.gitDir, "HEAD"), Keep.REMOTE to linesIn(repo.gitDir, "refs/remotes/origin/main"))
                for ((path, keep) in choices) {
                    val name = path.lowercase()
                    fun at(files: Map<String, Set<String>>) = files.filterKeys { it == name || it.startsWith("$name/") }.values.flatten().toSet()
                    removed += at(sides.getValue(if (keep == Keep.LOCAL) Keep.REMOTE else Keep.LOCAL)) - at(sides.getValue(keep))
                }
            }

            /**
             * Puts a random tail of the update FolderUpdate just finished back to its old versions, as if the app died
             * part way through: removals deepest first, then writes in path order, as FolderUpdate makes them.
             */
            fun undoPartOfTheUpdate() {
                val from = gitOk("rev-parse", "refs/safegit/move-from")
                val to = gitOk("rev-parse", "refs/safegit/move-to")
                val changes = gitOk("diff-tree", "-r", "-z", "--no-renames", "--name-only", from, to).paths()
                val inTo = gitOk("ls-tree", "-r", "-z", "--name-only", to).paths().toSet()
                val (removals, writes) = changes.partition { it !in inTo }
                val order = removals.sortedByDescending { it.count { c -> c == '/' } } + writes.sorted()
                for (path in order.drop(random.nextInt(order.size + 1)).reversed()) {
                    val file = folder.resolve(path)
                    val old = repoGit.run("cat-file", "blob", "$from:$path")
                    if (file.isFile) file.delete()
                    if (old.ok) file.apply { parentFile.mkdirs() }.writeText(old.stdout)
                }
            }
        }
    }

    private enum class Fault { NONE, APP_KILLED, GIT_KILLED, KILLED_MID_UPDATE, KILLED_FINISHING_UPDATE, SAVED_MID_SYNC, FOLDER_READ_ONLY }

    /** Calls [action] in place of the [at]th git command, running it too unless the action returns a result. */
    private fun Git.onCall(at: Int, action: () -> GitResult?): Git {
        var calls = 0
        return onFirst({ ++calls == at }, action)
    }

    private fun blobId(text: String): String {
        val bytes = text.toByteArray()
        val sha = MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()) }.digest(bytes)
        return sha.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // Short, since pre-commit runs it on every commit; a real soak run asks for more seeds
        const val SEEDS = 1L
        const val ROUNDS = 40
        const val LINES = 4
        const val CALLS = 30
        const val SETTLE_ROUNDS = 10
        const val OURS = 0
        const val THEIRS = 1
        const val STEPS_SHOWN = 40
        // "plan" is a file or a folder, whichever a device makes first
        val NAMES = listOf("notes.md", "todo.md", "ideas.md", "log/today.md", "log/older.md", "plan", "plan/week.md")
    }
}
