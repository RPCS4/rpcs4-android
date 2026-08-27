package com.rpcs4.android.emu

import com.rpcs4.android.native.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Aggregates the two input sources into one authoritative DualShock 4 state:
 *
 *   1. The on-screen touch overlay ([pushOverlayState]) - buttons OR together,
 *      sticks take whichever axis has the larger magnitude so both thumbs stay live.
 *   2. Hardware gamepads routed through MainActivity ([applyHardwareKey/motion]).
 *
 * A 60 Hz ticker flushes the merged snapshot to the native poller because PS4
 * games call scePadReadState() at their own cadence; sending continuously is
 * simpler and race-free compared to edge-triggered events.
 */
object PadStateMux {

    @Volatile private var overlay: PadSnapshot = PadSnapshot.NEUTRAL
    @Volatile private var hardware: PadSnapshot = PadSnapshot.NEUTRAL
    @Volatile private var paused = false

    private var flushJob: Job? = null

    fun pushOverlayState(snapshot: PadSnapshot) {
        overlay = snapshot
    }

    fun updateHardwareMotion(lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        hardware = hardware.copy(
            lx = clampAxis(lx), ly = clampAxis(ly),
            rx = clampAxis(rx), ry = clampAxis(ry),
            l2 = clamp01(l2), r2 = clamp01(r2),
        )
    }

    fun applyHardwareButton(sceBit: Int, pressed: Boolean) {
        val cur = hardware.buttons
        hardware = hardware.copy(buttons = if (pressed) cur or sceBit else cur and sceBit.inv())
    }

    fun clearHardware() {
        hardware = PadSnapshot.NEUTRAL
    }

    private fun merged(): PadSnapshot {
        val o = overlay
        val h = hardware
        return PadSnapshot(
            buttons = o.buttons or h.buttons,
            lx = stronger(o.lx, h.lx),
            ly = stronger(o.ly, h.ly),
            rx = stronger(o.rx, h.rx),
            ry = stronger(o.ry, h.ry),
            l2 = max(o.l2, h.l2),
            r2 = max(o.r2, h.r2),
        )
    }

    fun clearAll() {
        overlay = PadSnapshot.NEUTRAL
        clearHardware()
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            // Freeze inputs and drop everything held before the pause so a
            // resumed session never carries stale button presses.
            clearAll()
        }
    }

    fun start(scope: CoroutineScope) {
        stop()
        flushJob = scope.launch {
            while (isActive) {
                NativeBridge.ensureLoaded()
                val m = merged()
                if (!paused) {
                    NativeBridge.nativeSendPad(m.buttons, m.lx, m.ly, m.rx, m.ry, m.l2, m.r2)
                }
                delay(16)
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
        paused = false
        overlay = PadSnapshot.NEUTRAL
        hardware = PadSnapshot.NEUTRAL
    }

    private fun stronger(a: Float, b: Float): Float =
        if (kotlin.math.abs(a) >= kotlin.math.abs(b)) clampAxis(a) else clampAxis(b)

    private fun clampAxis(v: Float): Float = v.coerceIn(-1f, 1f)
    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
}
