package app.minemaps.ui.theme

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Chunky block border: 2dp outline, then a 2dp highlight (top/left) and shadow (bottom/right); swapped when [raised] is false (inset). */
fun Modifier.mcBevel(
    fill: Color,
    outline: Color,
    highlight: Color,
    shadow: Color,
    raised: Boolean = true,
    border: Dp = 2.dp,
): Modifier = drawBehind {
    val b = border.toPx()
    drawRect(fill)
    drawBevelFrame(b, outline, if (raised) highlight else shadow, if (raised) shadow else highlight)
}

private fun DrawScope.drawBevelFrame(b: Float, outline: Color, topLeft: Color, bottomRight: Color) {
    val w = size.width
    val h = size.height
    drawRect(topLeft, Offset(b, b), Size(w - 2 * b, b))
    drawRect(topLeft, Offset(b, b), Size(b, h - 2 * b))
    drawRect(bottomRight, Offset(b, h - 2 * b), Size(w - 2 * b, b))
    drawRect(bottomRight, Offset(w - 2 * b, b), Size(b, h - 2 * b))
    drawRect(outline, Offset(0f, 0f), Size(w, b))
    drawRect(outline, Offset(0f, h - b), Size(w, b))
    drawRect(outline, Offset(0f, 0f), Size(b, h))
    drawRect(outline, Offset(w - b, 0f), Size(b, h))
}

/** Consumes taps, double-taps, and long-presses on a floating map overlay panel so they do not fall through to the map View behind it, without blocking parent scroll containers on full screens. */
fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    detectTapGestures(onPress = { tryAwaitRelease() }, onTap = {}, onDoubleTap = {}, onLongPress = {})
}

fun Modifier.mcPanel(colors: McColors, raised: Boolean = true): Modifier =
    mcBevel(colors.panel, colors.outline, colors.highlight, colors.shadow, raised).consumeTouches()

fun Modifier.mcInset(colors: McColors): Modifier =
    mcBevel(if (colors.dark) colors.background else Color(0xFF8B8B8B), colors.outline, colors.highlight, colors.shadow, raised = false)
