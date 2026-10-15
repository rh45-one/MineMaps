package app.minemaps.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.minemaps.map.MapRenderer
import app.minemaps.map.MapView
import app.minemaps.ui.theme.Glyph
import app.minemaps.ui.theme.Mc
import app.minemaps.ui.theme.McButton
import app.minemaps.ui.theme.McIconButton
import app.minemaps.ui.theme.McPanel
import app.minemaps.ui.theme.PixelGlyph
import app.minemaps.ui.theme.mcPanel
import androidx.compose.runtime.getValue

class MapController {
    var view: MapView? = null
    var renderer: MapRenderer? = null

    fun zoomIn() = view?.zoomBy(1.0)

    fun zoomOut() = view?.zoomBy(-1.0)
}

enum class LocationIconState { NOT_FOLLOWING, FOLLOWING, NO_PERMISSION, GPS_OFF }

@Composable
fun LocationButton(state: LocationIconState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val following = state == LocationIconState.FOLLOWING
    val problem = state == LocationIconState.NO_PERMISSION || state == LocationIconState.GPS_OFF
    McIconButton(onClick, modifier.semantics { contentDescription = "My location" }, active = following, danger = problem) {
        val tint = androidx.compose.material3.LocalContentColor.current
        Box(
            Modifier.drawBehind {
                if (state == LocationIconState.GPS_OFF) {
                    drawLine(Color.White, Offset(size.width * 0.05f, size.height * 0.95f), Offset(size.width * 0.95f, size.height * 0.05f), 3.dp.toPx())
                }
            },
        ) { PixelGlyph(if (following) Glyph.LOCATE_FILLED else Glyph.LOCATE, tint, 24.dp) }
    }
}

@Composable
fun McBanner(text: String, actionLabel: String?, onAction: () -> Unit, modifier: Modifier = Modifier, warning: Boolean = true) {
    val c = Mc.colors
    Row(
        modifier.fillMaxWidth().mcPanel(c).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (warning) c.gold else c.green))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text)
        if (actionLabel != null) McButton(onAction, variant = app.minemaps.ui.theme.McVariant.PRIMARY) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun StreetNamesButton(on: Boolean, onClick: () -> Unit) {
    McIconButton(onClick, Modifier.semantics { contentDescription = if (on) "Hide street names" else "Show street names" }, active = on) {
        val color = androidx.compose.material3.LocalContentColor.current
        Text(
            "Aa",
            Modifier.drawBehind {
                if (!on) drawLine(color, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 3.dp.toPx())
            },
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
fun CompassButton(bearing: State<Double>, icon: ImageBitmap?, onClick: () -> Unit) {
    val visible by remember { derivedStateOf { bearing.value > 0.5 && bearing.value < 359.5 } }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        McIconButton(onClick, Modifier.semantics { contentDescription = "Reset map to north" }) {
            Box(
                Modifier.size(34.dp).graphicsLayer { rotationZ = -bearing.value.toFloat() }.drawBehind {
                    val w = size.width
                    val t = 9.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(w / 2, 0f)
                            lineTo(w / 2 - t * 0.6f, t)
                            lineTo(w / 2 + t * 0.6f, t)
                            close()
                        },
                        Color(0xFFE53935),
                    )
                },
                contentAlignment = Alignment.Center,
            ) { PixelIcon(icon, 24.dp) }
        }
    }
}

/** Small "i" button (48 dp touch target, subtle square visual). */
@Composable
fun CreditsButton(onClick: () -> Unit) {
    val c = Mc.colors
    Box(Modifier.size(48.dp).semantics { contentDescription = "Map credits" }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).mcPanel(c), contentAlignment = Alignment.Center) { PixelGlyph(Glyph.INFO, c.text, 16.dp) }
    }
}

@Composable
fun McTag(text: String, modifier: Modifier = Modifier) {
    val c = Mc.colors
    Text(
        text.uppercase(),
        modifier.background(c.greenLedge).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

@Composable
fun McCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    McPanel(modifier.fillMaxWidth(), content = content)
}
