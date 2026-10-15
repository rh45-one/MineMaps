package app.minemaps.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** Window size classes: compact < 600 dp wide, medium 600-840, expanded >= 840; compact height < 480 dp. */
@Immutable
class McWindow(val widthDp: Int, val heightDp: Int) {
    val wide: Boolean get() = widthDp >= 600
    val expanded: Boolean get() = widthDp >= 840
    val compactHeight: Boolean get() = heightDp < 480
    val landscape: Boolean get() = widthDp > heightDp
}

@Composable
fun rememberMcWindow(): McWindow {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val w = (size.width / density).toInt()
    val h = (size.height / density).toInt()
    return remember(w, h) { McWindow(w, h) }
}

@Composable
fun windowHeightDp(): Int = rememberMcWindow().heightDp

/** Safe drawing area without the keyboard: system bars (incl. side navigation bars in landscape) plus display cutouts. */
val McInsets: WindowInsets @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
