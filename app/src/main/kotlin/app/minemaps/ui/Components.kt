package app.minemaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.minemaps.map.MapRenderer
import app.minemaps.map.MapView
import kotlin.math.cos
import kotlin.math.sin

class MapController {
    var view: MapView? = null
    var renderer: MapRenderer? = null

    fun zoomIn() = view?.zoomBy(1.0)

    fun zoomOut() = view?.zoomBy(-1.0)
}

enum class LocationIconState { NOT_FOLLOWING, FOLLOWING, NO_PERMISSION, GPS_OFF }

@Composable
fun LocationIcon(state: LocationIconState, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    Canvas(modifier.size(24.dp)) {
        val c = center
        val r = size.minDimension * 0.28f
        val w = 2.dp.toPx()
        val tint = when (state) {
            LocationIconState.FOLLOWING -> primary
            LocationIconState.NOT_FOLLOWING -> onSurface
            else -> onSurface.copy(alpha = 0.6f)
        }
        drawCircle(tint, r, c, style = Stroke(w))
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0)
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(tint, Offset(c.x + dx * (r + 1.dp.toPx()), c.y + dy * (r + 1.dp.toPx())), Offset(c.x + dx * size.minDimension * 0.46f, c.y + dy * size.minDimension * 0.46f), w, StrokeCap.Round)
        }
        when (state) {
            LocationIconState.FOLLOWING -> drawCircle(tint, r * 0.55f, c)
            LocationIconState.NOT_FOLLOWING -> drawCircle(tint, r * 0.18f, c)
            LocationIconState.GPS_OFF -> drawLine(error, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.88f, size.height * 0.88f), w * 1.2f, StrokeCap.Round)
            LocationIconState.NO_PERMISSION -> {
                drawLine(error, Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y + r * 0.15f), w * 1.2f, StrokeCap.Round)
                drawCircle(error, w * 0.8f, Offset(c.x, c.y + r * 0.55f))
            }
        }
    }
}

@Composable
fun Banner(text: String, actionLabel: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

val Transparent = Color.Transparent

@Composable
fun StreetNamesIcon(on: Boolean) {
    val color = androidx.compose.material3.LocalContentColor.current
    androidx.compose.material3.Text(
        "Aa",
        Modifier.drawBehind {
            if (!on) drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
        },
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    )
}
