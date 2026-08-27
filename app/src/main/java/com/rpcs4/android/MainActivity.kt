package com.rpcs4.android

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rpcs4.android.emu.PadBits
import com.rpcs4.android.emu.PadStateMux
import com.rpcs4.android.ui.Rpcs4NavHost
import com.rpcs4.android.ui.theme.Rpcs4Theme

class MainActivity : ComponentActivity() {

    private var immersiveEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Rpcs4Theme {
                Rpcs4NavHost()
            }
        }
    }

    // ------------------------------------------------------ emulation chrome

    fun setImmersive(enabled: Boolean) {
        immersiveEnabled = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowInsetsControllerCompat(window, findViewById<View>(android.R.id.content))
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowInsetsControllerCompat(window, findViewById<View>(android.R.id.content))
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // -------------------------------------------------- hardware gamepad feed

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (immersiveEnabled && event.source and MotionEvent.SOURCE_CLASS_JOYSTICK != 0) {
            routeMotion(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (immersiveEnabled && routeButton(keyCode, true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (immersiveEnabled && routeButton(keyCode, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun routeMotion(event: MotionEvent) {
        PadStateMux.updateHardwareMotion(
            lx = event.getAxisValue(MotionEvent.AXIS_X),
            ly = event.getAxisValue(MotionEvent.AXIS_Y),
            rx = event.getAxisValue(MotionEvent.AXIS_Z),
            ry = event.getAxisValue(MotionEvent.AXIS_RZ),
            l2 = (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) +
                event.getAxisValue(MotionEvent.AXIS_BRAKE)).coerceIn(0f, 1f),
            r2 = (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) +
                event.getAxisValue(MotionEvent.AXIS_GAS)).coerceIn(0f, 1f),
        )
    }

    /** Android keycode -> SCE_PAD bitmask subset used by PS4 titles. */
    private fun routeButton(keyCode: Int, down: Boolean): Boolean {
        val bit = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> PadBits.CROSS
            KeyEvent.KEYCODE_BUTTON_B -> PadBits.CIRCLE
            KeyEvent.KEYCODE_BUTTON_X -> PadBits.SQUARE
            KeyEvent.KEYCODE_BUTTON_Y -> PadBits.TRIANGLE
            KeyEvent.KEYCODE_BUTTON_L1 -> PadBits.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> PadBits.R1
            KeyEvent.KEYCODE_BUTTON_START -> PadBits.OPTIONS
            KeyEvent.KEYCODE_BUTTON_THUMBL -> PadBits.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> PadBits.R3
            KeyEvent.KEYCODE_DPAD_UP -> PadBits.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadBits.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadBits.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadBits.RIGHT
            else -> return false
        }
        PadStateMux.applyHardwareButton(bit, down)
        return true
    }
}
