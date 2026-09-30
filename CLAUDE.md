# better-git-android

SafeGit (`com.sqftware.safegit`): git sync for Android that never loses work. One Sync button commits local edits, fetches, rebases them onto the remote and pushes. Anything that cannot be rebased cleanly is left exactly as it was and shown to the user, never overwritten. Native Kotlin + Jetpack Compose, built and tested entirely from the CLI.

## Why it exists

Git Sync (ViscousPot/GitSync, Flutter + libgit2) loses work: its pull merges and its push rebases, fast-forwards use a forced checkout whose dirty check skips untracked files, a conflicting rebase is left with a detached HEAD, and aborting a rebase or merge is a hard reset. SafeGit's whole value is not doing any of that.

## Decided so far

- **Engine: the real git CLI**, cross-compiled for arm64-v8a against bionic, shipped in `jniLibs` as `lib*.so` (`useLegacyPackaging = true`) and exec'd from `applicationInfo.nativeLibraryDir`. Helpers such as `git-remote-https` are symlinks from app storage into `nativeLibraryDir`, found through `GIT_EXEC_PATH`. This gives desktop git's exact rebase (merge-ort, rename detection, fork-point) at native speed. VSCodroid ships the same setup on Play at targetSdk 36. Rejected alternatives:
  - JGit: 6.x and 7.x crash on ART, and 5.13 is slow with an old merge.
  - libgit2: no autostash, no merge-ort, and its rebase detaches HEAD.
  - gitoxide: no rebase.
- **Engine boundary:** sync logic talks to a `Git` interface in plain Kotlin, and the CLI runner is one implementation of it. The logic is then unit tested on the JVM against real desktop git in temp repos, plus fakes.
- **Storage:** repos live in shared storage (e.g. an Obsidian vault in `Documents/`) under the All-files access permission (`MANAGE_EXTERNAL_STORAGE`), declared as core functionality on Play. SAF cannot back git (no paths, lstat or locking). To keep object and index I/O off the slow FUSE layer, the repo's git dir sits in app-private storage and points at the shared worktree (`core.worktree`). Tuning: `core.untrackedCache`, `feature.manyFiles`.
- **Auth:** ease of use first. GitHub sign-in uses the OAuth device flow, which needs no client secret or backend. The token lives in the Android Keystore and reaches git through `GIT_CONFIG_COUNT`/`http.extraHeader`, never argv or a config file. HTTPS only to start; SSH (a bundled ssh client) comes later if needed.
- **Sync is manual:** a button, nothing in the background yet. A sync takes seconds, so it runs as expedited WorkManager work, not a foreground service.

## Sync must never lose data

Every step can be undone, and nothing ever writes over a file git has not first saved in a commit.

1. Commit every local change, untracked files included (`add -A`), as a commit named for the device and time, and record HEAD under `refs/safegit/backup/<time>`. No stash and no autostash.
2. `fetch`, then rebase onto `@{u}` in a private linked worktree in app storage. The user's folder is not touched while the rebase runs, so an app like Obsidian can keep writing to it.
3. On a conflict, abort the rebase in the private worktree and report which files conflict. The user's folder and branch stay as they were.
4. On success, move the user's checkout to the rebased commit with a checkout that refuses to overwrite changes (never `--force`, never `reset --hard`). If files changed since step 1, go back to step 1.
5. `push` without force. If the remote moved in the meantime, repeat from `fetch`, a bounded number of times.

Kill git cleanly when a sync is cancelled, and recover a stale `index.lock` only when no git process is running.

## Open questions and risks

- **Binaries:** extract Termux's packages (their hardcoded `/data/data/com.termux` paths need `GIT_EXEC_PATH`, `OPENSSL_CONF` and a CA bundle file built from the system and user stores) or build from source with the NDK in CI. Whichever supplies them owns their security updates, and git (GPLv2) needs a written source offer.
- **16 KB pages:** since Nov 2025 Play requires 16 KB page alignment for targetSdk 35 and above, and that covers bundled executables. Check it on every binary.
- **Play review:** the All-files access declaration could be rejected.
- **FUSE:** `status` on a big vault may be slow. Benchmark against `~/repos/notes` (about 2,800 files).

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
```

## How we work

- A PR that only refreshes shared files through `sync-common` can be merged by Claude once its checks pass. Any other change leaves the merge to the user.

## Don't

- Run any git command that can discard work: `reset --hard`, `checkout --force`, `clean`, `rebase --abort` or `stash drop` in the user's worktree, or a force push.
- Add libraries (DI, navigation, Hilt) before a feature needs them.
