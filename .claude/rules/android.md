<!-- Shared rules: sync-common keeps every repo's copy identical to the original in the tooling checkout; edit the original only. -->

# Shared rules for Android apps

## Stack

- Kotlin + Jetpack Compose (Material 3); a new app's package name (`applicationId` and `namespace`) is `com.sqftware.<app>`, where `<app>` is a lowercase name chosen per app (not necessarily the repo name, which may not be a valid package segment). Never change an existing app's `applicationId`: Play treats it as a new app
- Android Gradle Plugin 9 with built-in Kotlin: do **not** apply `org.jetbrains.kotlin.android` to the module, only the Compose compiler plugin
- Build config every app needs goes in the shared `scripts/android-app.gradle`, applied from `app/build.gradle.kts`; its `preBuild` hook runs `sync-common`
- No Android Studio, and no Android Studio-only files (`.idea/`, run configurations). Never install tooling from the repo; if something is missing, tell the user

## Emulator

- The emulator is the test target. Gradle downloads the platform and build-tools for `compileSdk`; never install SDK packages or system images from the repo (`android sdk`, `sdkmanager`). When `scripts/emulator.sh` reports a missing image, pass its message on to the user.
- The machine runs one emulator at a time, and every repo's sessions take turns on it through one machine-wide lock: build first, then run device work through `scripts/emulator-lock.sh <command>` (e.g. `scripts/emulator-lock.sh ./gradlew connectedDebugAndroidTest`) with `ANDROID_SERIAL` pinned. Never hold the lock while building, debugging or waiting. It boots the emulator before the command and stops it after; pass `KEEP_EMULATOR=1` only when more device work follows within about 30 s.
- Every wait has a deadline and watches the real completion signal (process exit, result file, lock release), never `adb shell ps`: Android keeps a finished test process cached.

## Architecture and tests

- Logic that does not need Android lives in plain Kotlin with no `android.*` imports, so it is unit tested on the JVM. Android system and storage access sits behind interfaces, so UI and logic can be tested with fakes.
- UI behaviour gets a Compose test in `androidTest` that renders the composable with fake data; keep end-to-end tests against the real system few.
- Kotlin official code style, 4-space indent (`.editorconfig`). Terse over verbose.

## CI and release

- Install the repo's pre-commit hooks with `pre-commit install`.
- GitHub Actions run on every push: `android-ci` (build, lint, unit tests) and the pre-commit hooks. The emulator tests run locally only.
- Every merge to `main` publishes to the Play Store internal testing track through the shared `play_internal.yaml`, versioned by commit count, and to the closed testing track ("Alpha") too while that has a live release. The workflow header names the repo secrets it needs.
