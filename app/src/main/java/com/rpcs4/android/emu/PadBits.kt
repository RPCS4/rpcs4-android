package com.rpcs4.android.emu

/**
 * SCE_PAD_BUTTON_* bitmask values, byte-for-byte identical to
 * ScePadButtonDataOffset in core/OS/Libraries/ScePad/ScePad.hpp.
 *
 * Keeping the authoritative table on the Kotlin side means the JNI layer can
 * forward user input straight through without per-frame translation work.
 */
object PadBits {
    const val L3 = 0x00000002
    const val R3 = 0x00000004
    const val OPTIONS = 0x00000008
    const val UP = 0x00000010
    const val RIGHT = 0x00000020
    const val DOWN = 0x00000040
    const val LEFT = 0x00000080
    const val L2 = 0x00000100
    const val R2 = 0x00000200
    const val L1 = 0x00000400
    const val R1 = 0x00000800
    const val TRIANGLE = 0x00001000
    const val CIRCLE = 0x00002000
    const val CROSS = 0x00004000
    const val SQUARE = 0x00008000
    const val TOUCH_PAD = 0x00100000

    /** Small haptic pulse sent from the core when a game requests rumble support info. */
    const val ALL_FACE = SQUARE or CROSS or CIRCLE or TRIANGLE
}

/**
 * A single frame of pad state produced by either the on-screen overlay or a
 * hardware gamepad, merged by [PadStateMux] and forwarded to native code.
 */
data class PadSnapshot(
    val buttons: Int = 0,
    val lx: Float = 0f,
    val ly: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val l2: Float = 0f,
    val r2: Float = 0f,
) {
    companion object {
        val NEUTRAL = PadSnapshot()
    }
}
