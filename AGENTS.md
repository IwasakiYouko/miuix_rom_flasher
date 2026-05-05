# AGENTS.md

## What this is

A Kotlin/Native Windows desktop app (Compose Multiplatform + miuix-mingw UI) for flashing MIUI/HyperOS ROMs via fastboot. Single `:app` module targeting `mingwX64`. No tests exist.

## Build commands

All from repo root. **Windows only** — always use `gradlew.bat`.

```
gradlew.bat :app:linkReleaseExecutableMingwX64    # release build (primary)
gradlew.bat :app:linkDebugExecutableMingwX64      # debug build
gradlew.bat :app:compileKotlinMingwX64            # compile only, no link
gradlew.bat :app:clean                            # clean outputs
```

Output: `app/build/bin/mingwX64/releaseExecutable/MiuixWinSample.exe`

## Post-link hook does a lot

`app/build.gradle.kts` has a `KotlinNativeLink.doLast` block that copies DLLs, META-INF tools, firmware-update dir, compose resources, app icons, FolkTool/MagiskPatcher binaries, and embeds the exe icon via PowerShell. If linking succeeds but the exe is missing files, check this block.

## Architecture

- **Entrypoint**: `app/src/mingwX64Main/kotlin/Main.mingw.kt` — `main()` → `SampleApp()` composable (~2350 lines, all UI state lives here with `remember`)
- **FlashWorkflow.mingw.kt** (~1800 lines) — core ROM flashing logic, fastboot command execution, partition flashing, root patching
- **Other `*.mingw.kt` files** — platform-specific support: `FastbootSupport`, `PlatformToolsSupport`, `ProcessStreamSupport`, `RootManagerSupport`, `RomPackageSupport`, `AppRuntimePaths`, `WindowsUnicodeSupport`, `RuntimeFileSupport`, `GithubAvatarSupport`, `WindowIconSupport`
- **Pure UI files** (no `.mingw` suffix): `LiquidBottomBar`, `DampedDragAnimation`, `DragGestureInspector`, `InteractiveHighlight`, `FlashProgress`

## Composite build: miuix-mingw-ref

The UI library is resolved from `miuix-mingw-ref/` via `includeBuild`. It's a git submodule pointing to `https://github.com/YuKongA/miuix-mingw`. If you need to modify library code, check `miuix-mingw-ref/` for its own docs. Run `git submodule update --init` if the directory is empty.

## Dependencies

Versions in `gradle/libs.versions.toml`: Kotlin 2.3.20, Compose 1.11.0-alpha04, miuix 0.9.0. Repositories include a custom `compose-mingw-maven-repository` from GitHub raw — this is required, not all artifacts are on Maven Central.

## Key gradle.properties flags

- `configuration-cache=false` — configuration cache is off
- `kotlin.mpp.applyDefaultHierarchyTemplate=false` — no default source set hierarchy
- `kotlin.native.binary.smallBinary=true` — optimized for binary size
- `kotlin.mpp.enableCInteropCommonization=true` — CInterop commonization enabled

## Runtime dependencies bundled at build time

- ANGLE DLLs (`libEGL.dll`, `libGLESv2.dll`) from `app/libs/`
- Windows platform tools (adb, fastboot, magiskboot, busybox, etc.) from `META-INF/`
- FolkTool and MagiskPatcher from `.codex-cache/` (gitignored, must exist for full build)
- `firmware-update/` directory if present

## No lint/typecheck/test commands

No linting, formatting, typecheck, or test tasks are configured. The only verification is a successful `linkReleaseExecutableMingwX64`.

## CLAUDE.md is stale

`CLAUDE.md` describes the repo as a "minimal sample" — this is outdated. The app is now a full ROM flasher with 17 source files. Prefer this AGENTS.md for current guidance.
