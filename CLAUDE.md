# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This repository is a minimal Windows sample app built with Kotlin Multiplatform + Compose Multiplatform for the `mingwX64` target and the `miuix-mingw` UI library.

The app itself is a single executable target in `:app`. The repository also includes `miuix-mingw-ref` as an included build via `includeBuild("miuix-mingw-ref")`, so library sources are resolved from the local checkout instead of Maven artifacts.

Important consequences:
- Root `build.gradle.kts` is effectively empty; the real project wiring is in `settings.gradle.kts` and `app/build.gradle.kts`.
- If you need to change the referenced library code under `miuix-mingw-ref`, consult `miuix-mingw-ref/CLAUDE.md` first; that nested repository has its own conventions and commands.

## Common commands

Run commands from the repository root. On Windows, prefer `gradlew.bat`.

### Build and run
- Build release executable: `./gradlew.bat -p . :app:linkReleaseExecutableMingwX64`
- Build debug executable: `./gradlew.bat -p . :app:linkDebugExecutableMingwX64`
- Run debug executable via Gradle: `./gradlew.bat -p . :app:runDebugExecutableMingwX64`
- Run release executable via Gradle: `./gradlew.bat -p . :app:runReleaseExecutableMingwX64`
- Clean app outputs: `./gradlew.bat -p . :app:clean`
- List available tasks: `./gradlew.bat -p . tasks --all`

README-documented build command:
- `./gradlew.bat linkReleaseExecutableMingwX64`

### Tests and verification
- Run all checks for the app: `./gradlew.bat -p . :app:check`
- Run all tests: `./gradlew.bat -p . :app:allTests`
- Run the native test target: `./gradlew.bat -p . :app:mingwX64Test`

Current state:
- No test source files were found under `app/src`, so test tasks exist but there are currently no repository-local tests to target.
- A single-test command could not be confirmed from repository sources because no tests are present.

### Useful compilation tasks
- Compile the native main target without linking: `./gradlew.bat -p . :app:compileKotlinMingwX64`
- Link all binaries for the Windows target: `./gradlew.bat -p . :app:linkMingwX64`

## Architecture

### Project structure
- `settings.gradle.kts` defines a single app module, `:app`, and includes `miuix-mingw-ref` as a composite build.
- `app/build.gradle.kts` defines the actual Kotlin Multiplatform target configuration.
- `app/src/mingwX64Main/kotlin/Main.mingw.kt` contains the whole sample application UI and entrypoint.

This is currently a very small sample project rather than a layered application. Most work will happen in one source file unless the app is expanded.

### Build layout
`app/build.gradle.kts` configures:
- one target: `mingwX64`
- an executable binary with `entryPoint = "main"`
- `baseName = "MiuixWinSample"`
- Windows GUI linker option `-mwindows`
- Kotlin/Native GC tuning through `freeCompilerArgs`

It also adds a `KotlinNativeLink` hook that copies all DLLs from `app/libs` into the executable output directory after linking. This is specifically used for bundling the ANGLE runtime next to the produced `.exe`.

Default release output from the README:
- `app/build/bin/mingwX64/releaseExecutable/MiuixWinSample.exe`

### UI structure
The app entrypoint is `main()` in `app/src/mingwX64Main/kotlin/Main.mingw.kt`, which opens a Compose `Window` and renders `SampleApp()`.

`SampleApp()` owns all UI state locally with `remember`:
- theme mode
- keep-window-on-top flag
- package verification flag
- auto-backup flag
- run counter
- selected device

There is no persistence layer, domain layer, or external service integration yet; the app is a stateful Compose sample with mocked UI actions.

### Screen composition pattern
`SampleApp()` builds the entire screen inside `MiuixTheme` + `Scaffold` and switches between two layouts based on window width from `LocalWindowInfo`:
- wide layout: two-column `Row`
- narrow layout: stacked `Column`

The screen is decomposed into local composables in the same file:
- `QuickActionsPanel`
- `DevicePanel`
- `OverviewPanel` / `OverviewTile`
- `TaskPanel`
- `PreferencesPanel`
- `ThemePanel`
- `DeviceOption`
- `ThemeOption`

These composables are presentational wrappers around Miuix components such as `Card`, `TextButton`, `BasicComponent`, `SwitchPreference`, and `CheckboxPreference`.

### Dependency shape
The app depends only on `commonMain` libraries declared in `app/build.gradle.kts`:
- `compose.foundation`
- `compose.runtime`
- `compose.ui`
- `top.yukonga.miuix.kmp:miuix-ui`
- `top.yukonga.miuix.kmp:miuix-preference`
- `top.yukonga.miuix.kmp:miuix-icons`

Versions come from `gradle/libs.versions.toml`.

### What matters when editing
- The repository is Windows-target-first; main source is under `app/src/mingwX64Main`, not `commonMain`.
- There is currently no app-wide architecture beyond a single Compose file, so refactors that introduce additional files or layers should keep the existing simple entrypoint easy to follow.
- If a change affects native packaging or runtime startup, inspect both `app/build.gradle.kts` and the `app/libs` DLL-copy behavior.
- If a change appears to require library internals, the actual source may live in `miuix-mingw-ref` because of the composite build.

## Repository-specific notes from existing docs
- README states this project uses the local `miuix-mingw-ref` source because the published artifacts do not yet provide a directly consumable `mingw_x64` variant.
- The sample is intended to demonstrate Miuix components on Windows, including `MiuixTheme`, top app bar, cards, text buttons, and preference components.
