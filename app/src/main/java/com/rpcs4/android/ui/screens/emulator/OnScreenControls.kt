package com.rpcs4.android.ui.screens.emulator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rpcs4.android.emu.PadBits
import kotlin.math.abs
import kotlin.math.hypot

/**
 * On-screen touch controls. Each widget consumes its own pointer streams so
 * both thumbs can drive sticks while another finger holds shoulder buttons -
 * the same multitouch contract AndroidView overlays would give us.
 *
 * All widgets write into one [OverlayState]; EmulatorScreen pushes that
 * snapshot down through PadStateMux.
 */
class OverlayState {
    var buttons: Int by mutableStateOf(0)
    var lx: Float by mutableStateOf(0f)
    var ly: Float by mutableStateOf(0f)
    var rx: Float by mutableStateOf(0f)
    var ry: Float by mutableStateOf(0f)
    var l2: Float by mutableStateOf(0f)
    var r2: Float by mutableStateOf(0f)

    fun snapshot() = com.rpcs4.android.emu.PadSnapshot(buttons, lx, ly, rx, ry, l2, r2)
}

/** DualShock-colored face button hints. */
private object PsTints {
    val Cross = Color(0xFF6FA8FF)
    val Circle = Color(0xFFFF8A80)
    val Square = Color(0xFFEA80FC)
    val Triangle = Color(0xFF9BE89B)
}

// --------------------------------------------------------------------- stick

@Composable
fun VirtualStick(
    modifier: Modifier,
    onAxis: (Float, Float) -> Unit,
) {
    val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val knobColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(132.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { touchPoint ->
                        // Re-base so grabbing mid-ring does not jump the knob.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val delta = touchPoint - center
                        val maxR = size.width / 2f
                        val len = hypot(delta.x, delta.y).coerceAtLeast(0.001f)
                        val normX = (delta.x / len).coerceIn(-1f, 1f)
                        val normY = (delta.y / len).coerceIn(-1f, 1f)
                        knobOffset = delta / len * minOf(len, maxR * 0.7f)
                        applyAxis(normX, normY, onAxis)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val pos = change.position - center
                        val maxR = size.width / 2f
                        val len = hypot(pos.x, pos.y).coerceAtLeast(0.001f)
                        val clamped = minOf(len, maxR)
                        val nx = pos.x / len
                        val ny = pos.y / len
                        knobOffset = pos / len * clamped
                        applyAxis(nx, ny, onAxis)
                    },
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onAxis(0f, 0f)
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = ringColor, style = Stroke(width = 3.dp.toPx()))
            drawCircle(
                color = knobColor,
                radius = size.minDimension / 4.5f,
                center = center + knobOffset,
            )
        }
    }
}

private const val DEADZONE = 0.10f

private fun applyAxis(nx: Float, ny: Float, emit: (Float, Float) -> Unit) {
    val x = if (abs(nx) < DEADZONE) 0f else nx.coerceIn(-1f, 1f)
    val y = if (abs(ny) < DEADZONE) 0f else ny.coerceIn(-1f, 1f)
    emit(x, y)
}

// ------------------------------------------------------------------- buttons

@Composable
fun HoldButton(
    modifier: Modifier,
    sceBit: Int,
    state: OverlayState,
    label: String,
    tint: Color,
) {
    Canvas(
        modifier = modifier
            .size(if (sceBit == PadBits.OPTIONS || sceBit == PadBits.L3 || sceBit == PadBits.R3) 34.dp else 46.dp)
            .pointerInput(sceBit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    state.buttons = state.buttons or sceBit
                    waitForUpOrCancellation()
                    state.buttons = state.buttons and sceBit.inv()
                }
            },
    ) {
        val active = state.buttons and sceBit != 0
        drawCircle(
            color = if (active) tint else tint.copy(alpha = 0.35f),
            radius = size.minDimension / 2f - 2.dp.toPx(),
        )

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = (if (sceBit == PadBits.OPTIONS) 10.dp.toPx() else 13.dp.toPx())
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(2.dp.toPx(), 0f, 0f, android.graphics.Color.BLACK)
        }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            center.x,
            center.y + paint.textSize / 3f,
            paint,
        )
    }
}

/**
 * Full control cluster anchored around [state]. Uses absolute positioning with
 * offset modifiers from the screen edges - resolution independent because all
 * units are dp.
 */
@Composable
fun OnScreenOverlay(state: OverlayState) {
    Box(modifier = Modifier.fillMaxSize()) {

        // ------------------------------------------------- left cluster: DPAD + L-stick
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 18.dp, y = (-120).dp),
        ) {
            VirtualStick(Modifier) { nx, ny ->
                state.lx = nx
                state.ly = ny
            }

            // D-pad diamond
            Box(modifier = Modifier.size(112.dp)) {
                HoldButton(Modifier.align(Alignment.TopCenter).offset(y = 2.dp), PadBits.UP, state, "\u25B2", MaterialTheme.colorScheme.onSurface)
                HoldButton(Modifier.align(Alignment.BottomCenter).offset(y = (-2).dp), PadBits.DOWN, state, "\u25BC", MaterialTheme.colorScheme.onSurface)
                HoldButton(Modifier.align(Alignment.CenterStart), PadBits.LEFT, state, "\u25C0", MaterialTheme.colorScheme.onSurface)
                HoldButton(Modifier.align(Alignment.CenterEnd), PadBits.RIGHT, state, "\u25B6", MaterialTheme.colorScheme.onSurface)
            }
        }

        // ------------------------------------------------ right cluster: face + R-stick
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-18).dp, y = (-120).dp),
        ) {
            // Face-button diamond (PS layout: triangle top, circle right...)
            Box(modifier = Modifier.size(118.dp)) {
                HoldButton(Modifier.align(Alignment.TopCenter).offset(y = 2.dp), PadBits.TRIANGLE, state, "\u25B3", PsTints.Triangle)
                HoldButton(Modifier.align(Alignment.CenterStart), PadBits.SQUARE, state, "\u25A1", PsTints.Square)
                HoldButton(Modifier.align(Alignment.CenterEnd), PadBits.CIRCLE, state, "\u25CB", PsTints.Circle)
                HoldButton(Modifier.align(Alignment.BottomCenter).offset(y = (-2).dp), PadBits.CROSS, state, "\u2715", PsTints.Cross)
            }

            VirtualStick(Modifier) { nx, ny ->
                state.rx = nx
                state.ry = ny
            }
        }

        // ------------------------------------------------ shoulders / triggers
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 14.dp, y = 10.dp),
        ) {
            HoldButton(Modifier, PadBits.L1, state, "L1", MaterialTheme.colorScheme.onSurface)
            HoldButton(Modifier, PadBits.L2, state, "L2", MaterialTheme.colorScheme.onSurface)
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-14).dp, y = 10.dp),
        ) {
            HoldButton(Modifier, PadBits.R2, state, "R2", MaterialTheme.colorScheme.onSurface)
            HoldButton(Modifier, PadBits.R1, state, "R1", MaterialTheme.colorScheme.onSurface)
        }

        // Options bottom-center-left-ish
        HoldButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-16).dp),
            sceBit = PadBits.OPTIONS,
            state = state,
            label = "\u22EE",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
