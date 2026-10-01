# better-git-android

SafeGit (`com.sqftware.safegit`): git sync for Android that never loses work. One Sync button commits local edits, fetches, rebases them onto the remote and pushes. Anything that cannot be rebased cleanly is left exactly as it was and shown to the user, never overwritten. Native Kotlin + Jetpack Compose, built and tested entirely from the CLI.

## Why it exists

Git Sync (ViscousPot/GitSync, Flutter + libgit2) loses work: its pull merges and its push rebases, fast-forwards use a forced checkout whose dirty check skips untracked files, a conflicting rebase is left with a detached HEAD, and aborting a rebase or merge is a hard reset. SafeGit's whole value is not doing any of that.

## Decided so far

- **Engine: the real git CLI.** `scripts/fetch-git.sh` takes Termux's latest arm64 git and its libraries; they are bionic builds with 16 KB page alignment. They ship in `jniLibs` as `lib*.so` (`useLegacyPackaging`), because Android only executes an app's files from `nativeLibraryDir`. `BundledGit` symlinks the names git needs into that directory and overrides every Termux prefix path through the environment. This gives desktop git's exact rebase (merge-ort, rename detection) at native speed. VSCodroid ships the same setup on Play. Rejected alternatives:
  - JGit: 6.x and 7.x crash on ART, and 5.13 is slow with an old merge.
  - libgit2: no autostash, no merge-ort, and its rebase detaches HEAD.
  - gitoxide: no rebase.
- **Shell:** git's compiled-in shell is Termux's `sh`, so `fetch-git.sh` repoints it at `/system/bin/sh` in place. Hooks still fail (their `#!/bin/sh` does not exist on Android).
- **Tests:** `SafeSyncContract` (in `sync`'s test fixtures) holds the sync rules. It runs against desktop git on the JVM and against the bundled git on the emulator, since a newer git can change behavior. On the emulator it runs twice: in app storage, and with the folder on shared storage (`SharedStorageSafeSyncTest`), which is case-insensitive and behind FUSE like a real vault. CI's Linux is case-sensitive, so only a Mac or the emulator runs the case rules.
- **Engine boundary:** sync logic talks to a `Git` interface in plain Kotlin, and the CLI runner is one implementation of it.
- **Storage:** repos live in shared storage (e.g. an Obsidian vault in `Documents/`) under the All-files access permission (`MANAGE_EXTERNAL_STORAGE`), declared as core functionality on Play. SAF cannot back git (no paths, lstat or locking). To keep object and index I/O off the slow FUSE layer, the repo's git dir sits in app-private storage and points at the shared worktree, with `core.untrackedCache` on. On the emulator with the notes repo (about 2,800 files), this split layout brings `status` from 36 ms to 9 ms, and committing 20 edits from 0.4–0.65 s to 0.14 s, compared with the whole repo in shared storage.
- **Auth:** ease of use first. GitHub sign-in uses the OAuth device flow, which needs no client secret or backend. The token lives in the Android Keystore and reaches git through `GIT_CONFIG_COUNT`/`http.extraHeader`, never argv or a config file. HTTPS only to start; SSH (a bundled ssh client) comes later if needed.
- **Sync is manual:** a button, nothing in the background yet. A sync takes seconds, so it runs as expedited WorkManager work, not a foreground service.

## Sync must never lose data

`SafeSync` follows these rules, and `SafeSyncContract` tests each one:

1. Commit every local change, untracked files included (`add --all`). No stash and no autostash.
2. `fetch`, then combine the local changes since the fork point with the remote's in one three-way merge (`merge-tree`, `commit-tree`). This touches neither the folder nor the branch. The fork point leaves out commits the remote dropped with a force push.
3. On a conflict, stop and report the files. The folder, branch and remote stay as they were. The conflict clears once the files agree.
4. Move the folder with `FolderUpdate`, never `git checkout`, which leaves a file it cannot write half written and reports it only as an error line. The old commit goes under `refs/safegit/backup/<time>` first.
   - Git writes both versions of each changed file to private staging. Each file then goes into the folder by atomic rename, and only while it still holds the old version, so a file is always wholly old or wholly new.
   - Anything else in the way (an edit since step 1, an ignored or untracked file) blocks the update before anything is written. A file changed since step 1 gets committed, and the sync tries again.
   - A failed write puts the moved files back. If the app is killed, `refs/safegit/move-from`/`move-to` let the next sync finish the update, or put it back if a file has changed since. Once the branch has moved, it is only ever finished. The index and branch move last.
   - A file only counts as a version under its exact name. On case-insensitive storage, a name that opens a file under another case is taken, unless the two are one file renamed by case. A tree holding two names that differ only by case is refused.
   - A folder in the way of a file is cleared only once it holds no files.
5. `push` without force. If the remote moved in the meantime, repeat from `fetch`, at most three times.
6. A git that fails is never read as an empty answer: Android kills child processes, and one empty diff or missing HEAD would push a mass deletion. Only git's documented "no" (exit 1 from `rev-parse --verify`, `merge-base`, `diff --quiet`) counts as one. Anything else stops the sync.

## Open questions and risks

- **Binaries:** each build takes Termux's latest packages, so security fixes arrive with Termux. git is GPLv2: the app needs a source offer naming the versions listed in `assets/git/packages`.
- **Resolving conflicts:** the UI needs a way out of a conflict other than editing the file to match: keep both versions, or keep mine (`merge-tree -X ours`).
- **Residual window:** a notes app saving a file in the instant between `FolderUpdate`'s last check and its rename would lose that save.
- **Renamed only by case on the phone:** git with `core.ignorecase` never records it, so a remote change to that file blocks the sync (`FOLDER_BLOCKED`) until the name matches again. Nothing is lost, but the UI should explain it.
- **Dropped commits:** commits the remote dropped with a force push leave the phone's branch too (kept in the backup ref). The UI should say so.
- **Repo creation:** git probes `core.ignorecase`, `core.symlinks` and `core.filemode` in the private git dir, so creating a repo must set them for shared storage (case-insensitive, no symlinks, no exec bit). Backup refs are never pruned yet.
- **Cancellation:** `CliGit` cannot stop a running git yet. When sync becomes cancellable, kill git cleanly. A sync removes an `index.lock` it finds at the start, which is safe only while syncs of a repo never overlap.
- **Play review:** the All-files access declaration could be rejected.
- **FUSE:** the first `add` of a whole vault is slow (4 s for the notes repo, split layout). Measure on a real phone too.

## Commands

```bash
./gradlew testDebugUnitTest          # JVM unit tests (pre-commit runs them too)
./gradlew assembleDebug              # → app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug                  # Android lint → app/build/reports/lint-results-debug.html
scripts/emulator-lock.sh <command>   # device work: boots this repo's AVD, runs the command, stops it
scripts/emulator-lock.sh ./gradlew connectedDebugAndroidTest  # Compose UI tests on the emulator
scripts/emulator-lock.sh scripts/run.sh  # install the debug build and open it (VARIANT=Release for the minified build)
scripts/screenshot.sh [name]         # adb screencap → screenshots/<name>.png (gitignored)
scripts/pr-media.sh <file> <caption>... # upload shots as GitHub attachments, print the PR body's media table
./gradlew :app:fetchGit --rerun      # take Termux's newest git build; a build otherwise keeps the one it fetched first
```

## Layout

```text
sync/src/main/kotlin/com/sqftware/safegit/
├── git/                 # the Git interface and CliGit, which runs any git executable
└── sync/                # SafeSync (one sync: commit, fetch, merge, move the folder, push) and FolderUpdate
app/src/main/kotlin/com/sqftware/safegit/
├── MainActivity.kt
└── git/BundledGit.kt    # sets up the git binaries shipped as native libraries
scripts/fetch-git.sh     # downloads Termux's git and lays it out as jniLibs and assets
```

`sync` answers to `testDebugUnitTest` too, so the shared CI and pre-commit commands run its JVM tests.

## How we work

- A PR that only refreshes shared files through `sync-common` can be merged by Claude once its checks pass. Any other change leaves the merge to the user.

## Don't

- Give `sync` an Android dependency.
- Run any git command that can discard work: `reset --hard`, `checkout --force`, `clean`, `rebase --abort` or `stash drop` in the user's worktree, or a force push.
- Add libraries (DI, navigation, Hilt) before a feature needs them.
