package app.minemaps.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.minemaps.R
import app.minemaps.data.ArrowColors
import app.minemaps.data.LabelNames
import app.minemaps.data.SavedPlace
import app.minemaps.data.Settings
import app.minemaps.data.ThemeMode
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.search.PlaceIndex
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
import app.minemaps.ui.theme.McTextField
import app.minemaps.ui.theme.McTopBar
import app.minemaps.ui.theme.McVariant
import app.minemaps.ui.theme.PixelGlyph
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
private fun TexturePreview(atlas: TextureAtlas, isMinecraftSlot: Boolean, vanillaAvailable: Boolean) {
    val ctx = LocalContext.current
    if (isMinecraftSlot && !vanillaAvailable) {
        val strip = remember {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(ctx.resources, R.drawable.preview_pack_minecraft, opts)?.asImageBitmap()
        }
        if (strip != null) {
            Image(bitmap = strip, contentDescription = null, modifier = Modifier.width(92.dp).height(28.dp), filterQuality = FilterQuality.None)
            return
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in listOf("grass", "water", "stone_bricks")) {
            val img = remember(atlas, n) { terrainImage(atlas, n) }
            PixelIcon(img, 28.dp)
        }
    }
}

enum class SettingsSubmenu(val title: String, val subtitle: String, val iconName: String) {
    TEXTURES_APPEARANCE("Textures & Appearance", "Texture pack, theme, arrow colour", "emerald"),
    MAP_LABELS("Map & Labels", "Place language, POI item icons", "map"),
    NAVIGATION("Navigation & Driving", "Routing, Google Maps, traffic, radars, auto-zoom", "compass"),
    PLACES_STORAGE("Saved Places & Storage", "Export/import backups, tile cache", "chest"),
    UPDATES_ABOUT("Updates & About", "Version checks, licences, OpenStreetMap credits", "book"),
}

object SettingsSearch {
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "textures" to listOf("textures", "pack", "minecraft", "vanilla", "original", "blocks", "resource", "mojang", "skins", "jar", "style"),
        "theme" to listOf("theme", "dark", "light", "night", "day", "system", "mode", "appearance", "colour", "color", "brightness"),
        "arrow" to listOf("arrow", "player", "marker", "cursor", "pointer", "colour", "color", "tint", "icon"),
        "labels" to listOf("label", "names", "language", "english", "latin", "local", "alphabet", "arabic", "cyrillic", "script", "translate", "street"),
        "details" to listOf("map", "details", "item", "icons", "poi", "shops", "markers", "pins", "symbols"),
        "navigation" to listOf("navigation", "route", "routing", "directions", "turn", "osrm", "google", "maps", "assisted", "standalone", "mirror", "notification", "api", "key", "places", "traffic", "congestion", "jam", "alerts", "radar", "speed", "camera", "accident", "hazard", "roadworks", "construction", "dynamic", "zoom", "driving", "auto"),
        "saved_places" to listOf("saved", "places", "bookmarks", "favourites", "favorites", "export", "import", "backup", "restore", "json", "file"),
        "storage" to listOf("storage", "tile", "cache", "clear", "delete", "disk", "space", "memory", "offline", "megabytes", "mb"),
        "updates" to listOf("updates", "version", "check", "download", "install", "github", "release", "upgrade", "daily", "weekly", "manual", "launch"),
        "developer" to listOf("developer", "debug", "leak", "leakcanary", "memory", "notifications", "diagnostics"),
        "about" to listOf("about", "licences", "licenses", "attribution", "credits", "openstreetmap", "osm", "openmaptiles", "openfreemap", "privacy", "legal", "copyright"),
    )

    fun matches(groupKey: String, rawQuery: String): Boolean {
        val q = PlaceIndex.normalize(rawQuery)
        if (q.isEmpty()) return true
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        val pool = KEYWORDS[groupKey].orEmpty()
        return tokens.all { qt ->
            pool.any { kw -> kw.startsWith(qt) || qt in kw || fuzzyTokenMatch(qt, kw) }
        }
    }

    private fun fuzzyTokenMatch(a: String, b: String): Boolean {
        if (a.length < 3 || b.length < 3) return false
        val target = if (b.length > a.length + 1) b.substring(0, a.length) else b
        if (kotlin.math.abs(a.length - target.length) > 1) return false
        var i = 0
        var j = 0
        var diffs = 0
        while (i < a.length && j < target.length) {
            if (a[i] == target[j]) {
                i++
                j++
            } else {
                if (++diffs > 1) return false
                if (a.length > target.length) i++ else if (target.length > a.length) j++ else {
                    i++
                    j++
                }
            }
        }
        return true
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
    navigationGroup: @Composable ColumnScope.() -> Unit = {},
    savedPlacesGroup: @Composable ColumnScope.() -> Unit = {},
    updatesGroup: @Composable ColumnScope.() -> Unit = {},
    developerGroup: (@Composable ColumnScope.() -> Unit)? = null,
    onIncludeTextures: () -> Unit = {},
    texturesExtra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = Mc.colors
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var submenuName by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val activeSubmenu = submenuName?.let { runCatching { SettingsSubmenu.valueOf(it) }.getOrNull() }
    val labelStyle = MaterialTheme.typography.labelLarge
    val vanillaSelected = settings.texturePack == TexturePack.VANILLA && vanillaAvailable

    BackHandler(enabled = activeSubmenu != null || searchQuery.isNotBlank()) {
        if (searchQuery.isNotBlank()) searchQuery = "" else submenuName = null
    }

    val texturesBlock: @Composable ColumnScope.() -> Unit = {
        Group("Textures") {
            McSegmented(2, if (vanillaSelected) 1 else 0, { if (it == 1 && !vanillaAvailable) onIncludeTextures() else onTexturePack(if (it == 1) TexturePack.VANILLA else TexturePack.ORIGINAL) }) { i, _ ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TexturePreview(atlasOf(if (i == 1 && vanillaAvailable) TexturePack.VANILLA else TexturePack.ORIGINAL), isMinecraftSlot = i == 1, vanillaAvailable = vanillaAvailable)
                    Text(if (i == 0) "MineMaps original" else "Minecraft textures", style = labelStyle, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            texturesExtra()
        }
    }
    val themeBlock: @Composable ColumnScope.() -> Unit = {
        Group("Theme") {
            val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
            McSegmented(3, modes.indexOfFirst { it.first == settings.theme }.coerceAtLeast(0), { onTheme(modes[it].first) }) { i, _ -> Text(modes[i].second, style = labelStyle) }
        }
    }
    val arrowBlock: @Composable ColumnScope.() -> Unit = {
        Group("Arrow colour") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, color) in ArrowColors.presets) {
                    val image = remember(activeAtlas, color) { atlasImage(activeAtlas, "player", color) }
                    McSlot(Modifier.semantics { contentDescription = "Arrow colour $label" }, size = 52.dp, selected = settings.arrowColor == color, onClick = { onArrowColor(color) }) { PixelIcon(image, 36.dp) }
                }
            }
        }
    }
    val labelsBlock: @Composable ColumnScope.() -> Unit = {
        Group("Label names") {
            McSegmented(2, if (settings.labelNames == LabelNames.ENGLISH) 0 else 1, { onLabelNames(if (it == 0) LabelNames.ENGLISH else LabelNames.LOCAL) }) { i, _ ->
                Text(if (i == 0) "English (Latin script)" else "Local language", style = labelStyle, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    val detailsBlock: @Composable ColumnScope.() -> Unit = {
        Group("Map details") {
            Row(Modifier.fillMaxWidth().clickable { onIcons(!settings.showIcons) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show item icons", style = MaterialTheme.typography.bodyLarge)
                McSwitch(settings.showIcons, onIcons)
            }
        }
    }
    val storageBlock: @Composable ColumnScope.() -> Unit = {
        Group("Storage") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Tile cache", style = MaterialTheme.typography.bodyLarge)
                    Text(formatBytes(cacheBytes), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                McButton({ confirmClear = true }, variant = McVariant.DANGER) { Text("Clear") }
            }
        }
    }
    val aboutBlock: @Composable ColumnScope.() -> Unit = {
        Group("About") {
            val book = remember(activeAtlas) { atlasImage(activeAtlas, "book") }
            McListRow(onClick = onLicences, leading = { McSlot(size = 44.dp) { PixelIcon(book, 28.dp) } }, title = "Licences & attribution")
            McDivider()
            MapDataCredits(Modifier.padding(top = 8.dp))
        }
    }

    val topTitle = if (activeSubmenu != null && searchQuery.isBlank()) activeSubmenu.title else "Settings"
    val handleTopBack: () -> Unit = {
        if (searchQuery.isNotBlank()) searchQuery = ""
        else if (activeSubmenu != null) submenuName = null
        else onBack()
    }

    ScreenFrame(topTitle, handleTopBack, maxWidth = 680.dp) {
        McTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            placeholder = "Search settings (e.g. dark, traffic, cache)",
            leading = { PixelGlyph(Glyph.SEARCH, c.textSecondary, size = 20.dp) },
            trailing = {
                if (searchQuery.isNotEmpty()) {
                    Box(Modifier.size(44.dp).clickable { searchQuery = "" }, contentAlignment = Alignment.Center) {
                        PixelGlyph(Glyph.CLOSE, c.text, size = 16.dp)
                    }
                }
            },
        )

        if (searchQuery.isNotBlank()) {
            var anyShown = false
            if (SettingsSearch.matches("textures", searchQuery)) { anyShown = true; texturesBlock() }
            if (SettingsSearch.matches("theme", searchQuery)) { anyShown = true; themeBlock() }
            if (SettingsSearch.matches("arrow", searchQuery)) { anyShown = true; arrowBlock() }
            if (SettingsSearch.matches("labels", searchQuery)) { anyShown = true; labelsBlock() }
            if (SettingsSearch.matches("details", searchQuery)) { anyShown = true; detailsBlock() }
            if (SettingsSearch.matches("navigation", searchQuery)) { anyShown = true; navigationGroup() }
            if (SettingsSearch.matches("saved_places", searchQuery)) { anyShown = true; savedPlacesGroup() }
            if (SettingsSearch.matches("storage", searchQuery)) { anyShown = true; storageBlock() }
            if (SettingsSearch.matches("updates", searchQuery)) { anyShown = true; updatesGroup() }
            if (developerGroup != null && SettingsSearch.matches("developer", searchQuery)) { anyShown = true; developerGroup() }
            if (SettingsSearch.matches("about", searchQuery)) { anyShown = true; aboutBlock() }
            if (!anyShown) {
                McPanel(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("No settings match \"$searchQuery\"", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            }
        } else if (activeSubmenu == null) {
            McPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentPadding = 6.dp) {
                val menus = SettingsSubmenu.entries
                menus.forEachIndexed { idx, item ->
                    val iconImg = remember(activeAtlas, item.iconName) { atlasImage(activeAtlas, item.iconName) }
                    McListRow(
                        onClick = { submenuName = item.name },
                        leading = { McSlot(size = 46.dp) { PixelIcon(iconImg, 30.dp) } },
                        title = item.title,
                        subtitle = item.subtitle,
                        trailing = { PixelGlyph(Glyph.CHEVRON_RIGHT, c.textSecondary, size = 18.dp) },
                    )
                    if (idx < menus.lastIndex) McDivider()
                }
            }
            Spacer(Modifier.height(8.dp))
            MapDataCredits(Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        } else {
            when (activeSubmenu) {
                SettingsSubmenu.TEXTURES_APPEARANCE -> {
                    texturesBlock()
                    themeBlock()
                    arrowBlock()
                }
                SettingsSubmenu.MAP_LABELS -> {
                    labelsBlock()
                    detailsBlock()
                }
                SettingsSubmenu.NAVIGATION -> {
                    navigationGroup()
                }
                SettingsSubmenu.PLACES_STORAGE -> {
                    savedPlacesGroup()
                    storageBlock()
                }
                SettingsSubmenu.UPDATES_ABOUT -> {
                    updatesGroup()
                    if (developerGroup != null) developerGroup()
                    aboutBlock()
                }
            }
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
