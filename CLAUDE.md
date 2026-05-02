# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

WinFlasher is a Windows ROM flashing tool built with Kotlin Multiplatform + Compose Multiplatform targeting `mingwX64`. It uses the `miuix-mingw` UI library for a Xiaomi/HyperOS-style interface.

The app flashes Android ROMs via Fastboot, supports multiple ROOT solutions (Magisk, KernelSU-LKM, FolkPatch, HF-Team-GKI), manages ADB/Fastboot platform-tools, and handles firmware extraction (zstd packages).

The repository includes `miuix-mingw-ref` as a composite build via `includeBuild("miuix-mingw-ref")`. Root `build.gradle.kts` is empty; real wiring is in `settings.gradle.kts` and `app/build.gradle.kts`. If you need to change library code under `miuix-mingw-ref`, consult that directory first.

## Common commands

Run from the repository root. On Windows, use `gradlew.bat`.

### Build and run
- Build release: `./gradlew.bat -p . :app:linkReleaseExecutableMingwX64`
- Build debug: `./gradlew.bat -p . :app:linkDebugExecutableMingwX64`
- Run debug: `./gradlew.bat -p . :app:runDebugExecutableMingwX64`
- Run release: `./gradlew.bat -p . :app:runReleaseExecutableMingwX64`
- Clean: `./gradlew.bat -p . :app:clean`
- Compile only (no link): `./gradlew.bat -p . :app:compileKotlinMingwX64`

### Tests
- `./gradlew.bat -p . :app:check`
- `./gradlew.bat -p . :app:allTests`
- `./gradlew.bat -p . :app:mingwX64Test`
- No test source files currently exist under `app/src`.

## Architecture

### Build layout
`app/build.gradle.kts` configures:
- Single target: `mingwX64`, executable with `entryPoint = "main"`, `baseName = "MiuixWinSample"`
- Windows GUI linker: `-mwindows`
- Kotlin/Native GC: CMS with adaptive scheduler
- A `KotlinNativeLink` hook that post-link copies: ANGLE DLLs from `app/libs`, META-INF tools, firmware-update images, compose resources, app icons, FolkTool binaries, MagiskPatcher binaries. It also embeds an `.ico` into the exe via `tools/embed-exe-icon.ps1`.

Default release output: `app/build/bin/mingwX64/releaseExecutable/MiuixWinSample.exe`

### Source organization
All source is under `app/src/mingwX64Main/kotlin/` (Windows-only, no `commonMain` logic):

| File | Responsibility |
|---|---|
| `Main.mingw.kt` | Entry point, all UI composables (`SampleApp`, `FlashPage`, `DevicesPage`, `SettingsPage`, panels, progress indicators) |
| `FlashWorkflow.mingw.kt` | Core flash orchestration: `executeFlashWorkflow()`, partition flashing, zstd extraction, ROOT patching (Magisk, KernelSU-LKM, FolkPatch), ADB/Fastboot command execution |
| `FastbootSupport.mingw.kt` | `scanFastbootDevices()` - parses `fastboot devices` output |
| `FlashProgress.mingw.kt` | Progress data classes: `FlashPartitionProgress`, `ZstdExtractionProgress`, `WaitProgress`, `ManagerDownloadProgress` |
| `RomPackageSupport.mingw.kt` | ROM metadata loading from META-INF (`RomPackageInfo`, `FirmwareImageInfo`), remote device code resolution |
| `RootManagerSupport.mingw.kt` | ROOT manager APK discovery/download (`rootManagerOptionsFor()`, KernelSU cloud download via OpenList API) |
| `PlatformToolsSupport.mingw.kt` | ADB/Fastboot platform-tools install/update from Google |
| `AppRuntimePaths.mingw.kt` | Runtime path resolution: `resolveRuntimePath()`, `resolveTempPath()`, `resolveRuntimeBinPath()` - all relative to the exe directory |
| `ProcessStreamSupport.mingw.kt` | `runStreamingShellCommand()` - hidden process execution with real-time output streaming via temp file polling |
| `WindowsUnicodeSupport.mingw.kt` | Unicode-safe file I/O for paths with non-ASCII characters (falls back to PowerShell for CJK paths) |
| `RuntimeFileSupport.mingw.kt` | Log export (`exportLogLines`, `writeAutoLogLines`) |
| `WindowIconSupport.mingw.kt` | Sets the window icon via Win32 `SendMessageA` |
| `GithubAvatarSupport.mingw.kt` | Downloads and caches GitHub avatars for the acknowledgements panel |
| `LiquidBottomBar.kt` | Custom floating navigation bar component |
| `DampedDragAnimation.kt` | Drag animation utilities |
| `DragGestureInspector.kt` | Drag gesture handling |
| `InteractiveHighlight.kt` | Interactive highlight effects |

### Key architectural patterns

**Command execution**: All external processes (fastboot, adb, PowerShell) go through `runStreamingShellCommand()` in `ProcessStreamSupport.mingw.kt`. This spawns a hidden `cmd.exe` process, redirects output to a temp file, and polls for new lines. Exit codes are captured via a marker line appended to the output.

**Unicode path handling**: `WindowsUnicodeSupport.mingw.kt` provides `fileExistsUnicodeSafe()`, `readAllBytesUnicodeSafe()`, `writeBytesUnicodeSafe()` etc. For paths with non-ASCII characters, these delegate to PowerShell since Kotlin/Native's POSIX `fopen` can't handle them. Always use these functions instead of raw POSIX I/O for file operations.

**Runtime path resolution**: `AppRuntimePaths.mingw.kt` resolves all paths relative to the executable's directory (via `GetModuleFileNameW`). Key functions: `resolveRuntimePath()` (exe dir), `resolveRuntimeBinPath()` (exe dir + `bin`), `resolveRuntimeMetaInfPath()` (exe dir + `META-INF`), `resolveTempPath()` (`%TEMP%\WinFlasher`).

**Flash workflow**: `executeFlashWorkflow()` in `FlashWorkflow.mingw.kt` is the main entry point. Flow: verify package → extract zstd packages → prepare ROOT image (Magisk/FolkPatch/KernelSU-LKM) → collect flash targets from `firmware-update/` → flash partitions via fastboot → optional data wipe → optional KernelSU post-flash ROOT → reboot.

**ROOT modes**: Five modes defined in `RootMode` enum:
- `MagiskPatch`: Patches boot/init_boot locally using MagiskPatcher.exe
- `KernelSuLkm`: Post-flash ROOT - reboots to Android, pushes manager APK, runs device-side `ksud boot-patch`, pulls patched image, flashes it
- `FolkPatch`: Local kernel patching using kptools
- `HfTeamGki`: GKI kernel replacement (not yet implemented)
- `KeepOriginal`: No ROOT

**UI structure**: `SampleApp()` in `Main.mingw.kt` owns all state via `remember`. Three pages via `WorkPage` enum: `Flash` (main flashing UI), `Devices` (device management), `Settings` (preferences, theme, platform-tools). Layout adapts between wide (≥1120dp, two-column) and narrow (stacked) via `LocalWindowInfo`.

### Dependencies
From `gradle/libs.versions.toml` (Kotlin 2.3.20, Compose 1.11.0-alpha04, miuix 0.9.0):
- `compose.runtime`, `compose.ui`, `compose.components.resources`
- `top.yukonga.miuix.kmp:miuix-ui`, `miuix-preference`, `miuix-icons`, `miuix-blur`

### What matters when editing
- All source is under `app/src/mingwX64Main`, not `commonMain`.
- The app is Windows-only; it uses Win32 APIs (`CreateProcessW`, `FindWindowA`, `GetModuleFileNameW`), POSIX file I/O, and PowerShell extensively.
- For file operations on paths that may contain non-ASCII characters, use the `*UnicodeSafe` functions from `WindowsUnicodeSupport.mingw.kt`.
- The `KotlinNativeLink` hook in `app/build.gradle.kts` copies many files post-link. If adding new bundled resources, add copy logic there.
- `META-INF/` at the repo root contains bundled Android tools (adb, fastboot, magiskboot, busybox, etc.) that get copied into the build output.
- `firmware-update/` contains ROM firmware images (if present locally).
- `.codex-cache/` contains cached ROOT tools (FolkTool, MagiskPatcher, RootManagers).
- The app reads ROM metadata from `META-INF/source.properties`, `META-INF/DeviceName`, `META-INF/DeviceCPU`, etc.
