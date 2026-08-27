package com.rpcs4.android.native

import android.view.Surface

/**
 * Thin bridge over librpcs4_android.so.
 *
 * The C++ side lives in app/src/main/cpp/jni/Rpcs4Jni.cpp. Every function here
 * MUST stay in sync with the Java_com_rpcs4_android_native_NativeBridge_*
 * symbols - ProGuard rules keep this class unobfuscated for exactly that reason.
 *
 * Threading model:
 *  - [nativeStart] spawns a dedicated std::thread inside the native layer,
 *    mirroring the desktop build where the emulator runs off the Qt main loop.
 *  - [nativePollLogs] drains a bounded lock-guarded ring buffer fed by the
 *    core's MAKE_LOG_FUNCTION output (stdout redirect reader thread).
 */
object NativeBridge {

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("rpcs4_android")
            loaded = true
        }
    }

    // ------------------------------------------------------------------ setup

    /**
     * Hand the upcoming presentation surface to the native side. Must be called
     * from the thread that owns the SurfaceView lifecycle whenever the surface
     * is created or destroyed. Pass null to release.
     */
    external fun nativeSetSurface(surface: Surface?)

    /**
     * Apply the persistent emulator configuration (mirrors PS4::Configuration.hpp).
     * Call immediately before [nativeStart].
     */
    external fun nativeSetConfiguration(
        resolutionScale: Float,
        copyCommandBuffers: Boolean,
        skipAsyncComputeDispatches: Boolean,
        skipWaitRegMem: Boolean,
        disableGnmDetilerTextureSize: Boolean,
        disableSgprInitHack: Boolean,
        clampGpuBuffers: Boolean,
        skipBindlessBuffers: Boolean,
        forceInitSceCompositor: Boolean,
        lleSsl: Boolean,
    )

    // ------------------------------------------------------------------- run

    /**
     * Boot a game directory (or raw .self/.elf). Spawns the emulation thread
     * running PS4::loadAndRun(), which internally drives PS4::init().
     */
    external fun nativeStart(
        gamePath: String,
        systemDir: String,
        systemExDir: String,
        appDataDir: String,
    ): Boolean

    /** Cooperative stop: injects a quit event into the renderer pump. */
    external fun nativeStop()

    external fun nativeIsRunning(): Boolean

    /** True once a Surface has been registered (boot gate for the Vulkan window). */
    external fun nativeIsSurfaceReady(): Boolean

    // ----------------------------------------------------------------- input

    /**
     * Push a full virtual DualShock 4 state. Buttons use the SCE_PAD_BUTTON_*
     * bitmask (same layout as ScePadButtonDataOffset in the core). Stick and
     * trigger values are normalized floats (-1..1 / 0..1).
     */
    external fun nativeSendPad(
        buttons: Int,
        lx: Float, ly: Float,
        rx: Float, ry: Float,
        l2: Float, r2: Float,
    )

    /** Legacy keyboard-mapping fallback (SDL scancode virtual key press). */
    external fun nativeSendKey(scancode: Int, down: Boolean)

    // ------------------------------------------------------------------ logs

    /** Drain up to [maxLines] log lines captured since the last call. */
    external fun nativePollLogs(maxLines: Int): Array<String>
}
