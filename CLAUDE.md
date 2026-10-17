# better-git-android

SafeGit (`com.sqftware.safegit`): git sync for Android that never loses work. One Sync button commits local edits, fetches, rebases them onto the remote and pushes. A conflict puts both versions in the file between markers, as a rebase does, or asks the user which version to keep; nothing is ever overwritten. Native Kotlin + Jetpack Compose, built and tested entirely from the CLI.

## Why it exists

Git Sync (ViscousPot/GitSync, Flutter + libgit2) loses work: its pull merges and its push rebases, fast-forwards use a forced checkout whose dirty check skips untracked files, a conflicting rebase is left with a detached HEAD, and aborting a rebase or merge is a hard reset. SafeGit's whole value is not doing any of that.

## Decided so far

- **Engine: the real git CLI.** `scripts/fetch-git.sh` takes Termux's latest arm64 git and its libraries; they are bionic builds with 16 KB page alignment. They ship in `jniLibs` as `lib*.so` (`useLegacyPackaging`), because Android only executes an app's files from `nativeLibraryDir`. `BundledGit` symlinks the names git needs into that directory and overrides every Termux prefix path through the environment. This gives desktop git's exact rebase (merge-ort, rename detection) at native speed. VSCodroid ships the same setup on Play. Rejected alternatives:
  - JGit: 6.x and 7.x crash on ART, and 5.13 is slow with an old merge.
  - libgit2: no autostash, no merge-ort, and its rebase detaches HEAD.
  - gitoxide: no rebase.
- **Shell:** git's compiled-in shell is Termux's `sh`, so `fetch-git.sh` repoints it at `/system/bin/sh` in place. Hooks still fail (their `#!/bin/sh` does not exist on Android).
- **Tests:** `SafeSyncContract` (in `sync`'s test fixtures) holds the sync rules. It runs against desktop git on the JVM and against the bundled git on the emulator, since a newer git can change behavior. On the emulator it runs twice: in app storage, and with the folder on shared storage (`SharedStorageSafeSyncTest`), which is case-insensitive and behind FUSE like a real vault. CI's Linux is case-sensitive, so only a Mac or the emulator runs the case rules. `SafeSyncSoakTest` plays two phones and a laptop at random with crashes and failed writes injected. It checks that no version a phone's user wrote is ever gone, that conflicts come only from files both sides changed, and that the remote ends with every line nobody removed on purpose. A long run (`-Dsoak.seeds=40`) is the check before trusting a sync change.
- **Engine boundary:** sync logic talks to a `Git` interface in plain Kotlin, and the CLI runner is one implementation of it.
- **Storage:** repos live in shared storage (e.g. an Obsidian vault in `Documents/`) under the All-files access permission (`MANAGE_EXTERNAL_STORAGE`), declared as core functionality on Play. SAF cannot back git (no paths, lstat or locking). To keep object and index I/O off the slow FUSE layer, the repo's git dir sits in app-private storage and points at the shared worktree, with `core.untrackedCache` on. On the emulator with the notes repo (about 2,800 files), this split layout brings `status` from 36 ms to 9 ms, and committing 20 edits from 0.4–0.65 s to 0.14 s, compared with the whole repo in shared storage.
- **Auth:** ease of use first. GitHub sign-in uses the OAuth device flow, which needs no client secret or backend; the OAuth app's public client id is `safegit.githubClientId` in `gradle.properties`. The token is encrypted under an Android Keystore key and reaches git as an `http.https://github.com/.extraHeader` through `GIT_CONFIG_COUNT`, never argv or a config file. Commits carry the account's name and noreply email. HTTPS only to start, so a submodule declared by an SSH URL (as a laptop often adds one) is fetched from the same host and path over HTTPS; SSH (a bundled ssh client) comes later if needed.
- **Connecting a folder:** `RepoSetup` makes the private git dir and sets the folder storage's rules (`core.ignorecase`, `core.symlinks` probed there; no file mode). A folder with a `.git` of its own (as Git Sync leaves) starts from that repo's commit, but only when it is at rest, not sparse, and on the same remote branch; otherwise connecting is refused, since the first sync would push markers, deletions or another repo's files.
- **Syncs:** the Sync button runs one as expedited WorkManager work, not a foreground service, since it takes seconds. Each repo can also sync on its own (off by default; every 15 min up to weekly) as periodic WorkManager work that needs a network and a battery that isn't low; turning it on runs the first at once. An automatic sync:
  - is unattended (`attended = false`): a conflict stops it as `ConflictAhead` before the folder moves, so markers only appear from the button.
  - waits 5 min while a changed file was saved in the last minute (`RecentEdits`), which narrows the residual window below; files inside submodules are not checked yet.
  - never takes an edit as a held conflict's resolution: a user halfway through one may pause. Only the button finishes it.
  - retries a failure that may pass hourly when its period is 6 h or more; a shorter period waits for its next run.
  - notifies only what needs the user (a conflict, a choice, the sign-in refused, a failure that has lasted a day), once per change of state (`SyncNotices`).

## Sync must never lose data

`SafeSync` follows these rules, and `SafeSyncContract` tests each one:

1. Commit every local change, untracked files included (`add --all`). No stash and no autostash.
2. `fetch`, then combine the local changes since the fork point with the remote's in one three-way merge (`merge-tree`, `commit-tree`). This touches neither the folder nor the branch. The fork point leaves out commits the remote dropped with a force push.
3. A conflict works like a rebase's, with plain behavior and no guessing:
   - Where both sides changed the same lines of a text file, the file gets both versions between markers (`<<<<<<< <device>` … `>>>>>>> remote`), and the rest of the folder updates. The branch sits on a commit holding the markers, recorded in `refs/safegit/conflict` before the folder moves, and never pushed. While a marked file has markers left, a sync does nothing. Once none has, the marked commit leaves the branch and its files are committed with the resolution.
   - A file that would gain this device's markers is never committed, whatever its name (a note renamed before it was resolved). Markers with other labels are a note's own text.
   - Anything markers cannot show (a binary file, an edit against a delete, a file against a folder, names that differ only by case) stops the sync with nothing changed. Each such path comes back as a `Choice` holding what each side has there, and the next sync needs a `Keep` (local or remote) for each. A choice counts only while both sides still hold what it was made against.
   - Git's guess that a file added to a folder the other side renamed belongs in the renamed one is off (`merge.directoryRenames=false`): the file stays where it was added.
4. Move the folder with `FolderUpdate`, never `git checkout`, which leaves a file it cannot write half written and reports it only as an error line. The old commit goes under `refs/safegit/backup/<time>` first.
   - Git writes both versions of each changed file to private staging. Each file then goes into the folder by atomic rename, and only while it still holds the old version, so a file is always wholly old or wholly new.
   - Anything else in the way (an edit since step 1, an ignored or untracked file) blocks the update before anything is written. A file changed since step 1 gets committed, and the sync tries again.
   - A failed write puts the moved files back. If the app is killed, `refs/safegit/move-from`/`move-to` let the next sync finish the update, or put it back if a file has changed since. Once the branch has moved, it is only ever finished. The index and branch move last.
   - A file only counts as a version under its exact name. On case-insensitive storage, a name that opens a file under another case is taken, unless the two are one file renamed by case. A tree holding two names that differ only by case is refused.
   - A folder in the way of a file is cleared only once it holds no files.
   - On case-insensitive storage, a folder holds one file under either spelling of its name. So before merging, every path the remote spells in another case, by its file or a folder name, takes the remote's spelling, in the local side and the merge base alike (respelling one side alone makes git guess the rename from contents). Two files, or a file and a folder, that the local changes leave differing only by case are reported as a conflict.
5. `push` without force. If the remote moved in the meantime, repeat from `fetch`, at most three times.
6. Submodules (`SubmoduleSync`) sync like the repo, depth first. Each has its git dir under the parent's `modules/<name>` and a `.git` file in its folder pointing there.
   - The parent's `add` leaves submodule folders out; what it records for each is decided explicitly, never guessed from the folder.
   - Before the parent commits, a submodule with work of its own (edits, or commits on no remote branch or tag and not behind the last commit it followed, which may have come by its id) gets a whole sync, so the parent only ever records a commit on the submodule's remote. Its work is synced only onto the branch the recorded commit is on.
   - A submodule the parent's update moved follows to the new commit through `FolderUpdate`. The commit to follow sits in the parent's config until reached, so a kill in between never records the old one back. Otherwise the submodule's commit is this device's, and gets recorded.
   - Where both sides moved a submodule, the commit that holds the other's wins; otherwise it is a `Choice`, whether the parent's merge or a pending follow finds it.
   - Git dirs go by name and folders by path, so a submodule renamed or moved is taken out and set up afresh. A `.git` file naming one of the parent's own git dirs is never taken over.
   - A submodule the parent's update no longer declares is taken out by `FolderUpdate` before the parent's folder moves, so the parent's own files there land in a cleared folder. Its work is synced first, and it moves on a detached HEAD so the removal is never pushed. Files only it had (ignored ones) stop it.
   - A conflict or choice inside a submodule stops the sync. Any other trouble with one (out of reach, a `.git` directory of its own, files in the way) leaves it unrecorded while the rest syncs, and the sync ends `SUBMODULE`.
7. A git that fails is never read as an empty answer: Android kills child processes, and one empty diff or missing HEAD would push a mass deletion. Only git's documented "no" (exit 1 from `rev-parse --verify`, `merge-base`, `diff --quiet`) counts as one. Anything else stops the sync.

## Open questions and risks

- **Binaries:** each build takes Termux's latest packages, so security fixes arrive with Termux. git is GPLv2 (libiconv LGPL): the About screen lists the versions in `assets/git/packages` with each package's license and makes a written offer of their source, by email, for three years. So every shipped version's sources must stay obtainable: Termux's recipe at that version and the upstream release.
- **Case twins on the remote:** a remote (from Linux) holding two names that differ only by case blocks every sync (`FOLDER_BLOCKED`) until it is fixed there. Nothing is lost, but the UI should explain it.
- **Residual window:** a notes app saving a file in the instant between `FolderUpdate`'s last check and its rename would lose that save.
- **Renamed only by case on the phone:** git with `core.ignorecase` never records it. When an update changes that file, `FolderUpdate` first gives it HEAD's spelling back if its content is still HEAD's, as desktop git's checkout would. The user's rename is undone; nothing else is lost.
- **Resuming a killed update:** a file the update had already written, which the user then moved or deleted before the next sync, is written again when that sync finishes the update. Nothing is lost, but the old name comes back.
- **Dropped commits:** commits the remote dropped with a force push leave the phone's branch too (kept in the backup ref). The UI should say so.
- **Backup refs** are never pruned yet.
- **Cancellation:** `CliGit` cannot stop a running git yet. When sync becomes cancellable, kill git cleanly. A sync removes an `index.lock` it finds at the start, which is safe only while syncs of a repo never overlap: `SyncWorker` holds a per-repo lock on the thread running git, since a stopped worker's git runs on while WorkManager starts the work again.
- **Revoked token:** a token revoked on GitHub still shows as signed in, and its header makes every github.com fetch fail, public repos too. A sync or connect the remote refuses for want of a sign-in ends `SIGN_IN` (git's "could not read Username"), and the repo card offers to sign in again. GitHub keeps 10 tokens per account and app, so an 11th sign-in anywhere revokes the oldest.
- **Play review:** the All-files access declaration could be rejected.
- **FUSE:** the first `add` of a whole vault is slow (4 s for the notes repo, split layout). Measure on a real phone too.

## Commands

```bash
./gradlew testDebugUnitTest          # JVM unit tests (pre-commit runs them too)
./gradlew :sync:testDebugUnitTest --tests '*Soak*' -Dsoak.seeds=40  # long soak run, ~15 min; -Dsoak.seed=<n> replays one
./gradlew assembleDebug              # → app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug                  # Android lint → app/build/reports/lint-results-debug.html
scripts/emulator-lock.sh <command>   # device work: boots this repo's AVD, runs the command, stops it
scripts/emulator-lock.sh ./gradlew connectedDebugAndroidTest  # Compose UI tests on the emulator
scripts/emulator-lock.sh scripts/run.sh  # install the debug build and open it (VARIANT=Release for the minified build)
scripts/screenshot.sh [name]         # adb screencap → screenshots/<name>.png (gitignored)
scripts/pr-media.sh <file> <caption>... # upload shots as GitHub attachments, print the PR body's media table
scripts/emulator-lock.sh scripts/e2e.sh  # after assembleDebug: the app through connect, conflict and choice against a repo the Mac serves
./gradlew :app:fetchGit --rerun      # take Termux's newest git build; a build otherwise keeps the one it fetched first
```

## Layout

```text
sync/src/main/kotlin/com/sqftware/safegit/
├── git/                 # the Git interface and CliGit, which runs any git executable
└── sync/                # SafeSync (one sync: commit, fetch, merge, move the folder, push), FolderUpdate, RepoSetup
app/src/main/kotlin/com/sqftware/safegit/
├── MainActivity.kt      # all-files access, then the repo list or the add screen
├── about/               # the licenses of what the app ships, for the About screen
├── auth/                # GitHub device-flow sign-in, and the token in the Keystore
├── Services.kt          # the app's singletons: repo store, sync records, bundled git
├── git/BundledGit.kt    # sets up the git binaries shipped as native libraries
├── net/                 # one HTTP request, for GitHub's sign-in and API
├── repos/               # the connected repos, saved as JSON; Connector, which adds one; the account's repos to pick from
├── storage/             # all-files access, folder picker URI → path, atomic JSON files
├── work/                # each sync as WorkManager work, started or scheduled; its saved result, choices and notifications
└── ui/                  # Compose screens, tested with fake data
scripts/fetch-git.sh     # downloads Termux's git and lays it out as jniLibs and assets
```

`sync` answers to `testDebugUnitTest` too, so the shared CI and pre-commit commands run its JVM tests.

## How we work

- A PR that only refreshes shared files through `sync-common` can be merged by Claude once its checks pass. Any other change leaves the merge to the user.

## Don't

- Give `sync` an Android dependency.
- Run any git command that can discard work: `reset --hard`, `checkout --force`, `clean`, `rebase --abort` or `stash drop` in the user's worktree, or a force push.
- Add libraries (DI, navigation, Hilt) before a feature needs them.
