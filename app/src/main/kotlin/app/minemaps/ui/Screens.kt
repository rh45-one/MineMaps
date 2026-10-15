package app.minemaps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.minemaps.data.LabelNames
import app.minemaps.data.SavedPlace
import app.minemaps.data.Settings
import app.minemaps.data.ThemeMode
import java.util.Locale

@Composable
private fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlacesScreen(
    places: List<SavedPlace>,
    images: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
    onEdit: (SavedPlace) -> Unit,
    onBack: () -> Unit,
    onOpen: (SavedPlace) -> Unit,
    onDelete: (SavedPlace) -> Unit,
) {
    ScreenFrame("Saved places", onBack) {
        if (places.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved places yet.\nLong-press the map to save one.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(places, key = { it.id }) { place ->
                    val state = rememberSwipeToDismissBoxState(
                        confirmValueChange = {
                            if (it != SwipeToDismissBoxValue.Settled) onDelete(place)
                            it != SwipeToDismissBoxValue.Settled
                        },
                    )
                    SwipeToDismissBox(state = state, backgroundContent = {
                        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(place) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PixelIcon(images[place.icon] ?: images["pin"], 40.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(place.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        String.format(Locale.US, "%.5f, %.5f", place.lat, place.lon),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onEdit(place) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                                IconButton(onClick = { onDelete(place) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun <T> RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
fun SettingsScreen(
    settings: Settings,
    cacheBytes: Long,
    atlas: app.minemaps.engine.art.TextureAtlas,
    vanillaAvailable: Boolean,
    onTexturePack: (app.minemaps.engine.art.TexturePack) -> Unit,
    onArrowColor: (Int) -> Unit,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onIcons: (Boolean) -> Unit,
    onLabelNames: (LabelNames) -> Unit,
    onClearCache: () -> Unit,
    onLicences: () -> Unit,
) {
    ScreenFrame("Settings", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionTitle("Map style")
            RadioRow<Unit>("Minecraft", selected = true, onClick = {})
            SectionTitle("Theme")
            RadioRow<Unit>("System default", settings.theme == ThemeMode.SYSTEM) { onTheme(ThemeMode.SYSTEM) }
            RadioRow<Unit>("Light", settings.theme == ThemeMode.LIGHT) { onTheme(ThemeMode.LIGHT) }
            RadioRow<Unit>("Dark", settings.theme == ThemeMode.DARK) { onTheme(ThemeMode.DARK) }
            SectionTitle("Textures")
            val vanillaSelected = settings.texturePack == app.minemaps.engine.art.TexturePack.VANILLA && vanillaAvailable
            RadioRow<Unit>("MineMaps original", !vanillaSelected) { onTexturePack(app.minemaps.engine.art.TexturePack.ORIGINAL) }
            RadioRow<Unit>("Minecraft (from your game files)", vanillaSelected, enabled = vanillaAvailable) { onTexturePack(app.minemaps.engine.art.TexturePack.VANILLA) }
            if (!vanillaAvailable) {
                Text(
                    "Run tools/art/import_minecraft.py, then rebuild",
                    Modifier.padding(start = 64.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionTitle("Arrow colour")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                for ((label, color) in app.minemaps.data.ArrowColors.presets) {
                    val selected = settings.arrowColor == color
                    val image = remember(atlas, color) { atlasImage(atlas, "player", color) }
                    Box(
                        Modifier.padding(4.dp).size(52.dp)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .semantics { contentDescription = "Arrow colour $label" }
                            .clickable { onArrowColor(color) },
                        contentAlignment = Alignment.Center,
                    ) { PixelIcon(image, 40.dp) }
                }
            }
            SectionTitle("Map details")
            Row(
                Modifier.fillMaxWidth().clickable { onIcons(!settings.showIcons) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Show item icons")
                Switch(checked = settings.showIcons, onCheckedChange = onIcons)
            }
            SectionTitle("Label names")
            RadioRow<Unit>("English (Latin script)", settings.labelNames == LabelNames.ENGLISH) { onLabelNames(LabelNames.ENGLISH) }
            RadioRow<Unit>("Local language", settings.labelNames == LabelNames.LOCAL) { onLabelNames(LabelNames.LOCAL) }
            SectionTitle("Storage")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tile cache")
                    Text(formatBytes(cacheBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onClearCache) { Text("Clear") }
            }
            SectionTitle("About")
            Row(Modifier.fillMaxWidth().clickable(onClick = onLicences).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("Licences & attribution")
            }
            MapDataCredits(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
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
    LicenceEntry("OkHttp, AndroidX, Jetpack Compose, Kotlin", "Licensed under the Apache License 2.0. https://www.apache.org/licenses/LICENSE-2.0"),
    LicenceEntry("MineMaps artwork", "All block textures and item icons are original MineMaps artwork. No Minecraft assets are used. MineMaps is not affiliated with Mojang or Microsoft."),
)

@Composable
fun LicencesScreen(oflText: String, vanillaActive: Boolean, onBack: () -> Unit) {
    ScreenFrame("Licences & attribution", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            for (l in licences) {
                Text(l.title, style = MaterialTheme.typography.titleMedium)
                Text(l.body, Modifier.padding(top = 2.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (vanillaActive) {
                Text("Minecraft textures", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Minecraft textures \u00a9 Mojang Studios, imported from your own game files for personal use; not distributed.",
                    Modifier.padding(top = 2.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("Monocraft font (SIL Open Font License 1.1)", style = MaterialTheme.typography.titleMedium)
            Text(
                oflText.ifBlank { "Loading\u2026" },
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

@Composable
fun MapDataCredits(modifier: Modifier = Modifier) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(modifier) {
        Text("Map data", style = MaterialTheme.typography.labelLarge)
        Text(
            "\u00a9 OpenStreetMap contributors \u00b7 \u00a9 OpenMapTiles \u00b7 OpenFreeMap",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "OpenStreetMap copyright and license",
            Modifier.clickable { uri.openUri(OSM_COPYRIGHT_URL) }.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("Search by Photon (komoot)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
