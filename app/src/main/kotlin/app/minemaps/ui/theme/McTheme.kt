package app.minemaps.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
class McColors(
    val dark: Boolean,
    val background: Color,
    val panel: Color,
    val raised: Color,
    val outline: Color,
    val highlight: Color,
    val shadow: Color,
    val text: Color,
    val textSecondary: Color,
    val green: Color,
    val greenHighlight: Color,
    val greenLedge: Color,
    val onGreen: Color,
    val danger: Color,
    val dangerLedge: Color,
    val gold: Color,
    val focus: Color,
    val stoneLedge: Color,
)

val McDark = McColors(
    dark = true,
    background = Color(0xFF1E1E1F), panel = Color(0xFF313233), raised = Color(0xFF48494A), outline = Color(0xFF0E0E0F),
    highlight = Color(0xFF5A5B5C), shadow = Color(0xFF242425), text = Color(0xFFFFFFFF), textSecondary = Color(0xFFB5B6B8),
    green = Color(0xFF3C8527), greenHighlight = Color(0xFF52A535), greenLedge = Color(0xFF1D4D13), onGreen = Color(0xFFFFFFFF),
    danger = Color(0xFFC33636), dangerLedge = Color(0xFF7A1E1E), gold = Color(0xFFFFD83D), focus = Color(0xFFFFFFFF),
    stoneLedge = Color(0xFF242425),
)

val McLight = McColors(
    dark = false,
    background = Color(0xFFC6C6C6), panel = Color(0xFFC6C6C6), raised = Color(0xFFDADADA), outline = Color(0xFF1E1E1F),
    highlight = Color(0xFFFFFFFF), shadow = Color(0xFF555555), text = Color(0xFF1E1E1F), textSecondary = Color(0xFF3F3F40),
    green = Color(0xFF3C8527), greenHighlight = Color(0xFF52A535), greenLedge = Color(0xFF1D4D13), onGreen = Color(0xFFFFFFFF),
    danger = Color(0xFFC33636), dangerLedge = Color(0xFF7A1E1E), gold = Color(0xFFB8860B), focus = Color(0xFF1E1E1F),
    stoneLedge = Color(0xFF555555),
)

val LocalMcColors = compositionLocalOf { McDark }
val LocalMcTitleFont = compositionLocalOf<FontFamily> { FontFamily.Monospace }

object Mc {
    val colors: McColors @Composable @ReadOnlyComposable get() = LocalMcColors.current
    val titleFont: FontFamily @Composable @ReadOnlyComposable get() = LocalMcTitleFont.current
}

@Composable
fun MineMapsTheme(dark: Boolean, typeface: android.graphics.Typeface?, content: @Composable () -> Unit) {
    val c = if (dark) McDark else McLight
    val title = remember(typeface) { if (typeface != null) FontFamily(androidx.compose.ui.text.font.Typeface(typeface)) else FontFamily.Monospace }
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.green, onPrimary = c.onGreen, primaryContainer = c.greenLedge, onPrimaryContainer = c.text,
            secondary = c.raised, onSecondary = c.text, secondaryContainer = c.raised, onSecondaryContainer = c.text,
            tertiary = c.gold, background = c.background, onBackground = c.text, surface = c.panel, onSurface = c.text,
            surfaceVariant = c.raised, onSurfaceVariant = c.textSecondary, outline = c.outline, outlineVariant = c.shadow,
            error = c.danger, onError = Color.White, surfaceContainer = c.panel, surfaceContainerHigh = c.raised, surfaceContainerHighest = c.raised,
        )
    } else {
        lightColorScheme(
            primary = c.green, onPrimary = c.onGreen, primaryContainer = c.greenHighlight, onPrimaryContainer = c.onGreen,
            secondary = c.raised, onSecondary = c.text, secondaryContainer = c.raised, onSecondaryContainer = c.text,
            tertiary = c.gold, background = c.background, onBackground = c.text, surface = c.panel, onSurface = c.text,
            surfaceVariant = c.raised, onSurfaceVariant = c.textSecondary, outline = c.outline, outlineVariant = c.shadow,
            error = c.danger, onError = Color.White, surfaceContainer = c.panel, surfaceContainerHigh = c.raised, surfaceContainerHighest = c.raised,
        )
    }
    val sans = FontFamily.SansSerif
    val typography = Typography(
        displaySmall = TextStyle(fontFamily = title, fontSize = 24.sp),
        headlineSmall = TextStyle(fontFamily = title, fontSize = 20.sp),
        titleLarge = TextStyle(fontFamily = title, fontSize = 20.sp),
        titleMedium = TextStyle(fontFamily = title, fontSize = 16.sp),
        titleSmall = TextStyle(fontFamily = title, fontSize = 14.sp),
        labelLarge = TextStyle(fontFamily = title, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = title, fontSize = 14.sp),
        labelSmall = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = sans, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp),
    )
    val square = RoundedCornerShape(0)
    CompositionLocalProvider(LocalMcColors provides c, LocalMcTitleFont provides title) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = Shapes(square, square, square, square, square)) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text, content = content)
        }
    }
}
