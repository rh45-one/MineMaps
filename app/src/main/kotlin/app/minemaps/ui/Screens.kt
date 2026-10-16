package app.minemaps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.minemaps.data.ArrowColors
import app.minemaps.data.LabelNames
import app.minemaps.data.SavedPlace
import app.minemaps.data.Settings
import app.minemaps.data.ThemeMode
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.art.TexturePack
import app.minemaps.ui.theme.Glyph
import app.minemaps.ui.theme.Mc
import app.minemaps.ui.theme.McButton
import app.minemaps.ui.theme.McDialog
import app.minemaps.ui.theme.McDivider
import app.minemaps.ui.theme.McGlyphButton
import app.minemaps.ui.theme.McInsets
import app.minemaps.ui.theme.McListRow
import app.minemaps.ui.theme.McPanel
import app.minemaps.ui.theme.McSectionHeader
import app.minemaps.ui.theme.McSegmented
import app.minemaps.ui.theme.McSlot
import app.minemaps.ui.theme.McSwitch
import app.minemaps.ui.theme.McTopBar
import app.minemaps.ui.theme.McVariant
import app.minemaps.ui.theme.rememberMcWindow
import java.util.Locale
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@Composable
private fun ScreenFrame(title: String, onBack: () -> Unit, maxWidth: Dp = 640.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Mc.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            McTopBar(title, onBack, Modifier.windowInsetsPadding(McInsets.only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = maxWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                        .windowInsetsPadding(McInsets.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    McPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        McSectionHeader(title)
        content()
    }
}

@Composable
fun SavedPlacesScreen(
    places: List<SavedPlace>,
    images: Map<String, ImageBitmap>,
    onEdit: (SavedPlace) -> Unit,
    onBack: () -> Unit,
    onOpen: (SavedPlace) -> Unit,
    onDelete: (SavedPlace) -> Unit,
) {
    val c = Mc.colors
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val target = places.firstOrNull { it.id == confirm }
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            McTopBar("Saved places", onBack, Modifier.windowInsetsPadding(McInsets.only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                if (places.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        McSlot(size = 88.dp) { PixelIcon(images["pin"], 64.dp) }
                        Spacer(Modifier.height(16.dp))
                        Text("No saved places yet", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Text("Long-press the map to save a place.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                } else {
                    LazyColumn(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().windowInsetsPadding(McInsets.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        items(places, key = { it.id }) { place ->
                            McListRow(
                                onClick = { onOpen(place) },
                                leading = { McSlot(size = 52.dp) { PixelIcon(images[place.icon] ?: images["pin"], 36.dp) } },
                                title = place.name,
                                subtitle = String.format(Locale.US, "%.5f, %.5f", place.lat, place.lon),
                                trailing = {
                                    McGlyphButton(Glyph.EDIT, { onEdit(place) }, Modifier.semantics { contentDescription = "Edit" }, size = 48.dp)
                                    McGlyphButton(Glyph.DELETE, { confirm = place.id }, Modifier.semantics { contentDescription = "Delete" }, danger = true, size = 48.dp)
                                },
                            )
                            McDivider()
                        }
                    }
                }
            }
        }
    }
    target?.let { p ->
        McDialog(
            "Delete place?",
            onDismiss = { confirm = null },
            buttons = {
                McButton({ confirm = null }) { Text("Cancel") }
                McButton({ confirm = null; onDelete(p) }, variant = McVariant.DANGER) { Text("Delete") }
            },
        ) { Text("\"${p.name}\" will be removed from your saved places.", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun TexturePreview(atlas: TextureAtlas) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in listOf("grass", "water", "stone_bricks")) {
            val img = remember(atlas, n) { terrainImage(atlas, n) }
            PixelIcon(img, 28.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    cacheBytes: Long,
    atlasOf: (TexturePack) -> TextureAtlas,
    activeAtlas: TextureAtlas,
    vanillaAvailable: Boolean,
    onTexturePack: (TexturePack) -> Unit,
    onArrowColor: (Int) -> Unit,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onIcons: (Boolean) -> Unit,
    onLabelNames: (LabelNames) -> Unit,
    onClearCache: () -> Unit,
    onLicences: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = Mc.colors
    val win = rememberMcWindow()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val labelStyle = MaterialTheme.typography.labelLarge
    val vanillaSelected = settings.texturePack == TexturePack.VANILLA && vanillaAvailable

    val left: @Composable ColumnScope.() -> Unit = {
        Group("Textures") {
            McSegmented(2, if (vanillaSelected) 1 else 0, { onTexturePack(if (it == 1) TexturePack.VANILLA else TexturePack.ORIGINAL) }, enabled = { it == 0 || vanillaAvailable }) { i, _ ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TexturePreview(atlasOf(if (i == 1 && vanillaAvailable) TexturePack.VANILLA else TexturePack.ORIGINAL))
                    Text(if (i == 0) "MineMaps original" else "Minecraft (from your game files)", style = labelStyle, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (!vanillaAvailable) {
                Text("Run tools/art/import_minecraft.py, then rebuild", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        Group("Theme") {
            val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
            McSegmented(3, modes.indexOfFirst { it.first == settings.theme }.coerceAtLeast(0), { onTheme(modes[it].first) }) { i, _ -> Text(modes[i].second, style = labelStyle) }
        }
        Group("Arrow colour") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, color) in ArrowColors.presets) {
                    val image = remember(activeAtlas, color) { atlasImage(activeAtlas, "player", color) }
                    McSlot(Modifier.semantics { contentDescription = "Arrow colour $label" }, size = 52.dp, selected = settings.arrowColor == color, onClick = { onArrowColor(color) }) { PixelIcon(image, 36.dp) }
                }
            }
        }
    }
    val right: @Composable ColumnScope.() -> Unit = {
        Group("Label names") {
            McSegmented(2, if (settings.labelNames == LabelNames.ENGLISH) 0 else 1, { onLabelNames(if (it == 0) LabelNames.ENGLISH else LabelNames.LOCAL) }) { i, _ ->
                Text(if (i == 0) "English (Latin script)" else "Local language", style = labelStyle, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Group("Map details") {
            Row(Modifier.fillMaxWidth().clickable { onIcons(!settings.showIcons) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show item icons", style = MaterialTheme.typography.bodyLarge)
                McSwitch(settings.showIcons, onIcons)
            }
        }
        Group("Storage") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Tile cache", style = MaterialTheme.typography.bodyLarge)
                    Text(formatBytes(cacheBytes), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                McButton({ confirmClear = true }, variant = McVariant.DANGER) { Text("Clear") }
            }
        }
        extra()
        Group("About") {
            val book = remember(activeAtlas) { atlasImage(activeAtlas, "book") }
            McListRow(onClick = onLicences, leading = { McSlot(size = 44.dp) { PixelIcon(book, 28.dp) } }, title = "Licences & attribution")
            McDivider()
            MapDataCredits(Modifier.padding(top = 8.dp))
        }
    }
    ScreenFrame("Settings", onBack, maxWidth = if (win.expanded) 980.dp else 640.dp) {
        if (win.expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), content = left)
                Column(Modifier.weight(1f), content = right)
            }
        } else {
            left()
            right()
        }
    }
    if (confirmClear) {
        McDialog(
            "Clear tile cache?",
            onDismiss = { confirmClear = false },
            buttons = {
                McButton({ confirmClear = false }) { Text("Cancel") }
                McButton({ confirmClear = false; onClearCache() }, variant = McVariant.DANGER) { Text("Clear") }
            },
        ) { Text("Downloaded map tiles (${formatBytes(cacheBytes)}) are deleted and fetched again when needed.", style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
}

private class LicenceEntry(val title: String, val body: String)

private val licences = listOf(
    LicenceEntry("OpenStreetMap", "Map data \u00a9 OpenStreetMap contributors, available under the Open Database Licence (ODbL). https://www.openstreetmap.org/copyright"),
    LicenceEntry("OpenMapTiles", "Vector tile schema \u00a9 OpenMapTiles, licensed under CC-BY 4.0. https://openmaptiles.org"),
    LicenceEntry("OpenFreeMap", "Tiles served by OpenFreeMap. https://openfreemap.org"),
    LicenceEntry("Photon (komoot)", "Place search by Photon, https://photon.komoot.io. Subject to the komoot fair-use terms; geocoding data \u00a9 OpenStreetMap contributors."),
    LicenceEntry("Privacy", "Search sends your query and the map centre to photon.komoot.io; map tiles come from tiles.openfreemap.org. Release builds also check GitHub (api.github.com) for new versions according to Settings > Updates; choose \"Manual only\" to stop all automatic checks. Saved places and your location never leave the device."),
    LicenceEntry("OkHttp, AndroidX, Jetpack Compose, Kotlin", "Licensed under the Apache License 2.0. https://www.apache.org/licenses/LICENSE-2.0"),
    LicenceEntry("MineMaps artwork", "All block textures and item icons are original MineMaps artwork. No Minecraft assets are used. MineMaps is not affiliated with Mojang or Microsoft."),
)

@Composable
fun LicencesScreen(oflText: String, vanillaActive: Boolean, onBack: () -> Unit) {
    val c = Mc.colors
    ScreenFrame("Licences", onBack) {
        for (l in licences) {
            McPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(l.title, style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(l.body, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        }
        if (vanillaActive) {
            McPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("Minecraft textures", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    "Minecraft textures \u00a9 Mojang Studios, imported from your own game files for personal use; not distributed.",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            }
        }
        McPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Monocraft font (SIL Open Font License 1.1)", style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(
                oflText.ifBlank { "Loading\u2026" },
                Modifier.padding(top = 4.dp),
                style = LocalTextStyle.current.merge(MaterialTheme.typography.bodySmall),
                color = c.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

@Composable
fun MapDataCredits(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val c = Mc.colors
    Column(modifier) {
        Text("Map data", style = MaterialTheme.typography.titleSmall, color = c.text)
        Text("\u00a9 OpenStreetMap contributors \u00b7 \u00a9 OpenMapTiles \u00b7 OpenFreeMap", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Text(
            "OpenStreetMap copyright and license",
            Modifier.clickable { uri.openUri(OSM_COPYRIGHT_URL) }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = c.greenHighlight,
        )
        Text("Search by Photon (komoot)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
