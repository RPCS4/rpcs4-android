# Building RPCS4 for Android

## Table of Contents

- [Prerequisites](#prerequisites)
- [Getting the source](#getting-the-source)
- [Building from Android Studio](#building-from-android-studio)
- [Building from the command line](#building-from-the-command-line)
- [Enabling CI builds](#enabling-ci-builds)
- [Troubleshooting](#troubleshooting)

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Android Studio | Ladybug (2024.2) or newer | bundles JDK 21 + CMake |
| Android SDK | API 35, build-tools 35.0.0 | |
| Android NDK | r27 (27.0.12077973) | first NDK release with reliable `-std=c++23` |
| CMake | 3.22.1+ | matches AGP's bundled version |
| Gradle | 8.10.2 (via wrapper) | wrapper jar is committed |

Disk-wise expect roughly: 6 GB SDK/NDK + ~15 min per full native build on a
modern desktop (the core is ~200 translation units; ninja parallelizes well).

## Getting the source

The upstream emulation engine is consumed as a git submodule:

```bash
git clone https://github.com/rpcs4/rpcs4-android.git
cd rpcs4-android
git submodule update --init --recursive
```

If you cannot use submodules (or want a pinned snapshot), vendor the trees
instead:

```bash
./scripts/vendor_core.sh --download     # snapshot of upstream master
# or copy from a local submodule checkout:
./scripts/vendor_core.sh
```

Either path is sufficient - `app/src/main/cpp/CMakeLists.txt` resolves
`core/` and `Dependencies/` preferring the vendored copies under `app/cpp`,
falling back to the `rpcs4/` submodule.

## Building from Android Studio

1. Open the repository root (`File → Open`).
2. Let Gradle sync once; Studio installs any missing components
   automatically.
3. Select the `app` configuration and press **Run** on an x86_64 device,
   emulator, or Chromebook.

## Building from the command line

Debug APK (fastest):

```bash
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Release APK (unsigned unless you add signing config):

```bash
./gradlew :app:assembleRelease
```

Native-only iteration while debugging the shim layer (Android Studio's LLDB
attach works after a normal assemble):

```bash
./gradlew :app:externalNativeBuildDebug --info
```

Restricting target ABIs is already handled inside `app/build.gradle.kts`
(`abiFilters += listOf("x86_64")`). Do **not** remove that filter until the
ARM64 translator work described in PORTING.md lands.

## Enabling CI builds

The workflow at `.github/workflows/android.yml` ships disabled so your first
pushes do not burn minutes on a half-finished port. To switch it to automatic:

1. Open `.github/workflows/android.yml`.
2. Uncomment the two lines under `on:` (`push:` / `pull_request:`).
3. Commit; GitHub Actions then produces debug + release APKs as artifacts on
   every commit. Manual runs remain available through **Actions → android →
   Run workflow** regardless.

## Troubleshooting

**Gradle cannot find NDK r27**

The NDK is resolved via `ndkVersion`-less auto-detection. Install it explicitly
or pin in `app/build.gradle.kts` with `ndk { ... } // ndkVersion = "27.0.12077973"`.

**`miniz_export.h not found` / zydis include errors**

Third-party dependency directories are empty, i.e. the recursive submodule was
not initialized. Run `git submodule update --init --recursive` or re-run
`scripts/vendor_core.sh`.

**C++23 compile errors about `<format>` or `std::expected`**

Verify you are actually using NDK r27's clang 18 toolchain; older NDKs lack
large parts of the standard library surface used by the core.

**App boots but games fail immediately with "no ANativeWindow registered"**

The renderer creates its Vulkan window only after Kotlin hands over a Surface.
Keep the emulation screen open until boot completes - backgrounding the app
during initialization destroys the SurfaceView and aborts the window handoff.
