# Porting notes & roadmap

This document explains how the Android port is wired, what the SDL-compat
shim covers, and what remains on the road to full ARM64 phone support.

## 1. Architecture overview

The upstream repository keeps its emulation engine (`core/`, C++23) strictly
separated from its Qt6 GUI (`src/`): the only Qt-bridging file is
`core/Emulator.hpp/.cpp`. That separation made the port possible **without
forking any emulation code**:

| Desktop | Android port | Where |
|---------|--------------|-------|
| Qt main window / game list | Compose `LibraryScreen` + SAF importer (SFO parsing re-implemented in Kotlin) | `app/src/main/java/.../data`, `ui/screens/library` |
| `EmulatorController` QObject | `NativeBridge.nativeStart/nativeStop` spawning a `std::thread` around `PS4::loadAndRun()` | `app/src/main/cpp/jni/Rpcs4Jni.cpp` |
| SDL2 window + Vulkan surface | Shim adopts the app's `ANativeWindow` and creates the surface through `vkCreateAndroidSurfaceKHR` | `compat/SDL_shim.cpp` |
| SDL2 audio queue | AAudio low-latency output stream with source-format conversion | `compat/audio_aaudio.cpp` |
| SDL2 game controller + keyboard | Virtual DS4 fed from JNI (overlay) plus hardware pads routed by `MainActivity` | `compat/SDL_input.cpp`, `emu/PadStateMux.kt` |
| stdout logging (`Logger.hpp`) | pipe redirect → logcat **and** in-app `LogScreen` ring buffer | `compat/log_ring.cpp` |

`app/src/main/cpp/CMakeLists.txt` compiles the same file globs as upstream's
`core/CMakeLists.txt` - excluding only:

* `ChonkyStation4.cpp` (CLI entry point)
* `Emulator.cpp` (Qt bridge; replaced by our JNI layer)
* NVIDIA Aftermath sources (desktop-only profiler)

### Entry point fidelity

The Kotlin boot path intentionally mirrors `core/ChonkyStation4.cpp::main()`:

```
chdir(appDataDir)
User::init(); User::createNew("RPCS4Android") if needed; User::login(1)
Configuration::{system_dir_path, system_ex_dir_path} = app-private dirs
PS4::loadAndRun(gamePath)          // internally runs PS4::init() via g_app.run()
```

## 2. What the shim guarantees

* **Thread contract preserved.** The GCN thread still creates "its" window and
  Vulkan instance exactly as on desktop; we merely back the window object with
  an already-live `ANativeWindow`.
* **Queue-accounting parity.** `SDL_GetQueuedAudioSize()` reports bytes in the
  *source* format so `SceAudioOut.cpp`'s pacing loop behaves identically.
* **Analog input correctness.** We always expose one virtual controller so
  `scePadReadState()` gets real stick ranges; triggers are passed as floats and
  ScePad derives its digital L2/R2 bits itself.
* **Cooperative shutdown.** "Stop" injects `SDL_QUIT` into the renderer pump,
  matching how desktop closes.

## 3. Known limitations

These are honest engineering constraints of a first cut, not hidden bugs:

1. **Pause is UI-only.** Upstream `pause()/resume()` are stubs in
   `core/Emulator.cpp`; the overlay hides controls but games keep running.
2. **Light bar / rumble are no-ops** pending a VibratorManager bridge.
3. **Multi-channel audio downmixes to stereo** using the first two channels -
   sufficient for most titles, room for a proper 7.1 matrix later.
4. **Direct-path library mode** requires "All files access" (MANAGE_EXTERNAL_STORAGE);
   the default SAF import costs extra disk space instead.
5. **Bionic vs glibc differences** surface only at runtime: guest VA reservations
   and `%fs:` TLS tricks in `OS/Thread.cpp` were written for glibc hosts. First
   on-device runs should focus there (see §4).

## 4. Roadmap to ARM64 phones

The fundamental blocker: guest code is x86-64 and is *executed directly*. Three
workstreams unlock phones:

1. **CPU translator integration**
   Embed an x86-64 → AArch64 user-space translator (Box64-style or Google's
   historical `ndk_translation`) behind the linker: guest pages become translated
   regions and HLE hooks stay unchanged because they already cross the ABI
   boundary at symbol export time.
2. **Guest memory policy pass**
   Replace hard-coded `(u8*)0x800000000` assumptions with mmap hints valid under
   bionic's layout, add an address-space reservation step before `loadAndRun()`.
3. **Shim completeness for ARM64**
   libco builds need its AArch64 context switcher instead of SysV asm; zydis stays
   (guest disassembly), xbyak must be disabled or replaced with translator-emitted
   machine code at pipeline-cache load points.

Community contributions here are what will flip README's status table green.

## 5. Verification checklist after native changes

- [ ] `./gradlew :app:assembleDebug` succeeds end-to-end
- [ ] Library scan detects a synthetic folder set (`eboot.bin` + `param.sfo` dummy)
- [ ] Booting shows logs streaming in **Logs** within ~2 s
- [ ] On-screen sticks move axes in `scePadReadState` log output
- [ ] Stop returns cleanly to the library without killing the process
