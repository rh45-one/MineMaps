package app.minemaps.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue

enum class McVariant { PRIMARY, SECONDARY, DANGER }

private val LedgeHeight = 4.dp

@Composable
private fun BevelBox(
    onClick: () -> Unit,
    modifier: Modifier,
    variant: McVariant,
    enabled: Boolean,
    role: Role,
    minHeight: Dp,
    minWidth: Dp,
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    val c = Mc.colors
    val source = remember { MutableInteractionSource() }
    val isHeld by source.collectIsPressedAsState()
    var tapHold by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(isHeld) {
        if (isHeld) tapHold = true
    }
    val depressed = (isHeld || tapHold) && enabled
    val focused by source.collectIsFocusedAsState()
    val drop by animateDpAsState(if (depressed) LedgeHeight else 0.dp, tween(45), label = "press")
    val green = variant == McVariant.PRIMARY || selected
    val fill = when {
        variant == McVariant.DANGER -> c.danger
        green -> if (depressed) c.greenHighlight else c.green
        else -> if (depressed) c.highlight else c.raised
    }
    val ledge = when {
        variant == McVariant.DANGER -> c.dangerLedge
        green -> c.greenLedge
        else -> c.stoneLedge
    }
    val onFill = if (variant == McVariant.DANGER || green) c.onGreen else c.text
    val fillWidth = minWidth == Dp.Unspecified
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .drawBehind {
                val b = 2.dp.toPx()
                val l = LedgeHeight.toPx()
                drawRect(c.outline, Offset(0f, size.height - l - 2 * b), Size(size.width, l + 2 * b))
                drawRect(ledge, Offset(b, size.height - l), Size(size.width - 2 * b, l - b))
            }
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = {
                    tapHold = true
                    scope.launch {
                        delay(115)
                        tapHold = false
                        onClick()
                    }
                },
            ),
    ) {
        Box(
            Modifier
                .padding(bottom = LedgeHeight)
                .offset { IntOffset(0, drop.roundToPx()) }
                .let { if (fillWidth) it.fillMaxWidth() else it.widthIn(min = minWidth) }
                .heightIn(min = minHeight)
                .mcBevel(fill, if (focused) c.focus else c.outline, if (green) c.greenHighlight else if (variant == McVariant.DANGER) Color(0xFFE06060) else c.highlight, if (green) c.greenLedge else if (variant == McVariant.DANGER) c.dangerLedge else c.shadow),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides onFill) { content() }
        }
    }
}

@Composable
fun McButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: McVariant = McVariant.SECONDARY,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    BevelBox(onClick, if (fullWidth) modifier.fillMaxWidth() else modifier, variant, enabled, Role.Button, 44.dp, if (fullWidth) Dp.Unspecified else 64.dp, false) {
        Row(
            Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

@Composable
fun McText(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = MaterialTheme.typography.labelLarge)

/** Square map/toolbar button; [size] is the visual size (>= 48 dp keeps the touch target). */
@Composable
fun McIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 52.dp,
    content: @Composable () -> Unit,
) {
    BevelBox(
        onClick, modifier, if (danger) McVariant.DANGER else McVariant.SECONDARY, enabled, Role.Button,
        size, size, active,
    ) { Box(Modifier.size(size), contentAlignment = Alignment.Center) { content() } }
}

@Composable
fun McGlyphButton(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, danger: Boolean = false, size: Dp = 52.dp, enabled: Boolean = true) {
    McIconButton(onClick, modifier, active, danger, enabled, size) {
        PixelGlyph(glyph, LocalContentColor.current, size = 24.dp)
    }
}

@Composable
fun McPanel(modifier: Modifier = Modifier, contentPadding: Dp = 12.dp, raised: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Mc.colors
    Column(modifier.mcPanel(c, raised).padding(4.dp + contentPadding), content = content)
}

@Composable
fun McTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = Mc.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    if (tfv.text != value) {
        tfv = androidx.compose.ui.text.input.TextFieldValue(
            text = value,
            selection = if (focused) androidx.compose.ui.text.TextRange(value.length) else androidx.compose.ui.text.TextRange.Zero,
        )
    }
    LaunchedEffect(focused) {
        if (!focused && tfv.selection != androidx.compose.ui.text.TextRange.Zero) {
            tfv = tfv.copy(selection = androidx.compose.ui.text.TextRange.Zero)
        }
    }
    BasicTextField(
        value = tfv,
        onValueChange = { next ->
            tfv = next
            if (next.text != value) onValueChange(next.text)
        },
        modifier = modifier,
        singleLine = true,
        interactionSource = source,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
        cursorBrush = SolidColor(c.text),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .mcBevel(if (c.dark) c.background else Color(0xFF8B8B8B), if (focused) c.focus else c.outline, c.highlight, c.shadow, raised = false)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) Box(Modifier.padding(horizontal = 6.dp)) { leading() }
                Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.labelLarge, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

@Composable
fun McSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Mc.colors
    val knob by animateDpAsState(if (checked) 26.dp else 0.dp, tween(80), label = "knob")
    Box(
        modifier.size(width = 56.dp, height = 32.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .mcBevel(if (checked) c.greenLedge else (if (c.dark) c.background else Color(0xFF8B8B8B)), c.outline, c.highlight, c.shadow, raised = false)
            .padding(4.dp),
    ) {
        Box(
            Modifier.offset { IntOffset(knob.roundToPx(), 0) }.size(22.dp)
                .mcBevel(if (checked) c.greenHighlight else c.raised, c.outline, if (checked) Color(0xFF7BC96A) else c.highlight, if (checked) c.green else c.shadow, border = 1.dp),
        )
    }
}

@Composable
fun McSegmented(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
    item: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    val cell: @Composable (Int, Modifier) -> Unit = { i, m ->
        val on = i == selected
        BevelBox(
            onClick = { onSelect(i) },
            modifier = m.fillMaxWidth(),
            variant = McVariant.SECONDARY,
            enabled = enabled(i),
            role = Role.RadioButton,
            minHeight = 48.dp,
            minWidth = 0.dp,
            selected = on,
        ) { Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { item(i, on) } }
    }
    if (stacked) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (i in 0 until count) cell(i, Modifier) }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (i in 0 until count) cell(i, Modifier.weight(1f)) }
    }
}

/** Inventory-style slot; the selected slot gets a white focus frame. */
@Composable
fun McSlot(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = Mc.colors
    val base = modifier.size(size).mcInset(c)
    val framed = if (selected) base.border(3.dp, c.focus) else base
    val clickable = if (onClick != null) framed.selectable(selected = selected, role = Role.RadioButton, onClick = onClick) else framed
    Box(clickable, contentAlignment = Alignment.Center) { content() }
}

@Composable
fun McSectionHeader(text: String, modifier: Modifier = Modifier) {
    val c = Mc.colors
    Column(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(c.green))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        McDivider()
    }
}

@Composable
fun McDivider(modifier: Modifier = Modifier) {
    val c = Mc.colors
    Box(modifier.fillMaxWidth().height(4.dp).drawBehind {
        drawRect(c.outline, Offset.Zero, Size(size.width, size.height / 2))
        drawRect(c.highlight, Offset(0f, size.height / 2), Size(size.width, size.height / 2))
    })
}

@Composable
fun McListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    title: String,
    titleColor: Color = Color.Unspecified,
) {
    val c = Mc.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (titleColor == Color.Unspecified) c.text else titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun McTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Mc.colors
    Column(modifier.fillMaxWidth().background(c.panel)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            McGlyphButton(Glyph.BACK, onBack, modifier = Modifier.semanticsDescription("Back"), size = 48.dp)
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        McDivider()
    }
}

fun Modifier.semanticsDescription(text: String): Modifier = semantics { contentDescription = text }

@Composable
fun McDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (windowHeightDp() - 32).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            McPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Mc.colors.text)
                Spacer(Modifier.height(8.dp))
                McDivider()
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
            }
        }
    }
}

@Composable
fun mcBodyStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(color = Mc.colors.text, fontSize = 15.sp)
