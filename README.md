# better-git-android

SafeGit: git sync for Android that never loses your work. One button commits your changes, fetches, and rebases them onto the remote; anything it cannot rebase cleanly is left untouched for you to resolve, never overwritten. Native Kotlin + Jetpack Compose, CLI-only workflow.

## Local dev

Requires JDK 21, Gradle, the Android command-line tools with `platform-tools` and `emulator`, and an arm64 `google_apis_playstore` system image. `scripts/emulator.sh` never installs anything and says what is missing.

```bash
./gradlew testDebugUnitTest                               # unit tests
scripts/emulator-lock.sh ./gradlew connectedDebugAndroidTest  # UI tests on the emulator
scripts/emulator-lock.sh scripts/run.sh                   # install and open the app
pre-commit install                                        # hygiene checks, markdownlint, lint and unit tests on commit
```
