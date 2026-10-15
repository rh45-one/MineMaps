package app.minemaps.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

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
    val c = LocalConfiguration.current
    return remember(c.screenWidthDp, c.screenHeightDp) { McWindow(c.screenWidthDp, c.screenHeightDp) }
}

/** Safe drawing area without the keyboard: system bars (incl. side navigation bars in landscape) plus display cutouts. */
val McInsets: WindowInsets @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
