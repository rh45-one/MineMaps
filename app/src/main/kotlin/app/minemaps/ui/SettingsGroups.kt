package app.minemaps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.minemaps.BuildConfig
import app.minemaps.data.NavigationMode
import app.minemaps.data.Settings
import app.minemaps.data.UpdateFrequency
import app.minemaps.engine.routing.TurnDirection
import app.minemaps.nav.ActiveBannerState
import app.minemaps.update.ReleaseInfo
import app.minemaps.update.UpdateUi
import app.minemaps.ui.theme.Glyph
import app.minemaps.ui.theme.Mc
import app.minemaps.ui.theme.McButton
import app.minemaps.ui.theme.McDialog
import app.minemaps.ui.theme.McGlyphButton
import app.minemaps.ui.theme.McSegmented
import app.minemaps.ui.theme.McSwitch
import app.minemaps.ui.theme.McTextField
import app.minemaps.ui.theme.McVariant
import app.minemaps.ui.theme.PixelGlyph
import app.minemaps.ui.theme.mcPanel
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedPlacesGroup(onExport: () -> Unit, onImport: () -> Unit) {
    Group("Saved places") {
        Text("Back up your places to a file, or merge a backup into this install.", style = MaterialTheme.typography.bodySmall, color = Mc.colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McButton(onExport) { Text("Export") }
            McButton(onImport) { Text("Import") }
        }
    }
}

@Composable
fun NavigationGroup(
    settings: Settings,
    listenerGranted: Boolean,
    onMode: (NavigationMode) -> Unit,
    onMirrorChange: (Boolean) -> Unit,
    onOpenListenerSettings: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onTrafficChange: (Boolean) -> Unit = {},
    onRoadAlertsChange: (Boolean) -> Unit = {},
    onDynamicZoomChange: (Boolean) -> Unit = {},
) {
    val c = Mc.colors
    Group("Navigation & Driving") {
        val modes = listOf(
            NavigationMode.OFF to "Off",
            NavigationMode.STANDALONE to "Standalone",
            NavigationMode.GOOGLE_ASSISTED to "Google Assisted",
        )
        McSegmented(modes.size, modes.indexOfFirst { it.first == settings.navigationMode }.coerceAtLeast(0), { onMode(modes[it].first) }) { i, _ ->
            Text(modes[i].second, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        when (settings.navigationMode) {
            NavigationMode.OFF -> Text(
                "Map only: hides Navigate buttons and route overlays.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            NavigationMode.STANDALONE -> Text(
                "Calculates driving routes with OpenStreetMap (OSRM) and draws the block-world route path and turn banner with no API keys.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            NavigationMode.GOOGLE_ASSISTED -> {
                Text(
                    "Combines block-map routing with Google Maps: mirror live Google Maps app turn notifications and/or supply a Google API key for Google Places search and traffic-coloured routes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onMirrorChange(!settings.mirrorGoogleMaps) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Mirror Google Maps app directions", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (listenerGranted) "Notification access granted \u2013 active Google Maps turns appear on the map"
                            else "Requires Notification Access so MineMaps can read Google Maps' ongoing turn banner",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (listenerGranted) c.greenHighlight else c.textSecondary,
                        )
                    }
                    McSwitch(settings.mirrorGoogleMaps, onMirrorChange)
                }
                if (settings.mirrorGoogleMaps && !listenerGranted) {
                    Spacer(Modifier.height(6.dp))
                    McButton(onOpenListenerSettings, variant = McVariant.PRIMARY) { Text("Grant notification access") }
                }
                Spacer(Modifier.height(12.dp))
                Text("Google Maps API Key (optional)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Enables Google Places business search and Google Routes with live traffic congestion colours (green / amber / red). Leave blank to use OpenStreetMap routing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                McTextField(
                    value = settings.googleApiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "AIza... (optional)",
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        app.minemaps.ui.theme.McDivider()
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clickable { onTrafficChange(!settings.showTraffic) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show traffic congestion colours", style = MaterialTheme.typography.bodyLarge)
                Text("Colours slow route segments amber and traffic jams red", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            McSwitch(settings.showTraffic, onTrafficChange)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clickable { onRoadAlertsChange(!settings.showRoadAlerts) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show road alerts & speed cameras", style = MaterialTheme.typography.bodyLarge)
                Text("Displays flashing speed-camera radars, roadworks, and hazards", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            McSwitch(settings.showRoadAlerts, onRoadAlertsChange)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clickable { onDynamicZoomChange(!settings.dynamicDrivingZoom) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Dynamic driving zoom", style = MaterialTheme.typography.bodyLarge)
                Text("Automatically adjusts zoom level based on speed and road type (motorway vs street)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            McSwitch(settings.dynamicDrivingZoom, onDynamicZoomChange)
        }
    }
}

@Composable
fun ActiveNavBanner(banner: ActiveBannerState, onStop: () -> Unit, onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    val c = Mc.colors
    val glyph = when (banner.direction) {
        TurnDirection.LEFT, TurnDirection.SLIGHT_LEFT, TurnDirection.SHARP_LEFT, TurnDirection.U_TURN -> Glyph.NAV_LEFT
        TurnDirection.RIGHT, TurnDirection.SLIGHT_RIGHT, TurnDirection.SHARP_RIGHT -> Glyph.NAV_RIGHT
        else -> Glyph.NAV_UP
    }
    Row(
        modifier.fillMaxWidth().mcPanel(c).clickable(onClick = onClick).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.background(c.greenLedge).padding(8.dp), contentAlignment = Alignment.Center) {
            PixelGlyph(glyph, c.onGreen, size = 24.dp)
        }
        Column(Modifier.weight(1f)) {
            val head = if (banner.distanceText.isNotBlank()) "${banner.distanceText} \u00b7 ${banner.instruction}" else banner.instruction
            Text(head, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 2)
            val sub = listOf(banner.etaText, banner.providerLabel, "Tap for steps").filter { it.isNotBlank() }.joinToString(" \u00b7 ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.greenHighlight, maxLines = 1)
        }
        McGlyphButton(Glyph.CLOSE, onStop, size = 44.dp)
    }
}

@Composable
fun DirectionsDialog(
    banner: ActiveBannerState,
    plan: app.minemaps.engine.routing.RoutePlan?,
    onSelectStep: (app.minemaps.engine.routing.RouteStep) -> Unit,
    onStopNavigation: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Mc.colors
    val steps = plan?.steps.orEmpty()
    McDialog(
        title = banner.destinationName.ifBlank { "Directions" },
        onDismiss = onDismiss,
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McButton(onStopNavigation, Modifier.weight(1f), variant = McVariant.DANGER, fullWidth = true) { Text("End route", maxLines = 1) }
                McButton(onDismiss, Modifier.weight(1f), variant = McVariant.PRIMARY, fullWidth = true) { Text("Close", maxLines = 1) }
            }
        },
    ) {
        if (banner.etaText.isNotBlank()) {
            Text("${banner.etaText} \u00b7 ${banner.providerLabel}", style = MaterialTheme.typography.bodyMedium, color = c.greenHighlight)
            Spacer(Modifier.height(8.dp))
        }
        if (steps.isEmpty()) {
            Text(banner.instruction, style = MaterialTheme.typography.bodyLarge, color = c.text)
            if (banner.distanceText.isNotBlank()) {
                Text(banner.distanceText, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        } else {
            steps.forEachIndexed { idx, s ->
                val stepGlyph = when (s.direction) {
                    TurnDirection.LEFT, TurnDirection.SLIGHT_LEFT, TurnDirection.SHARP_LEFT, TurnDirection.U_TURN -> Glyph.NAV_LEFT
                    TurnDirection.RIGHT, TurnDirection.SLIGHT_RIGHT, TurnDirection.SHARP_RIGHT -> Glyph.NAV_RIGHT
                    else -> Glyph.NAV_UP
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectStep(s) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.background(c.raised).padding(6.dp), contentAlignment = Alignment.Center) {
                        PixelGlyph(stepGlyph, c.text, size = 18.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${idx + 1}. ${s.instruction}", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        if (s.distanceMeters > 0) {
                            Text(app.minemaps.engine.routing.RouteTracker.formatDistance(s.distanceMeters), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                }
                if (idx < steps.lastIndex) app.minemaps.ui.theme.McDivider()
            }
        }
    }
}

@Composable
fun UpdatesGroup(
    settings: Settings,
    enabled: Boolean,
    ui: UpdateUi,
    offer: ReleaseInfo?,
    onFrequency: (UpdateFrequency) -> Unit,
    onCheckNow: () -> Unit,
    onInstall: () -> Unit,
) {
    val c = Mc.colors
    Group("Updates") {
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        if (!enabled) {
            Text("Updates are disabled in debug builds", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            return@Group
        }
        Spacer(Modifier.height(8.dp))
        val freqs = listOf(UpdateFrequency.LAUNCH to "Every launch", UpdateFrequency.DAILY to "Daily", UpdateFrequency.WEEKLY to "Weekly", UpdateFrequency.MANUAL to "Manual only")
        McSegmented(freqs.size, freqs.indexOfFirst { it.first == settings.updateFrequency }.coerceAtLeast(0), { onFrequency(freqs[it].first) }) { i, _ ->
            Text(freqs[i].second, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        val last = if (settings.lastUpdateCheck > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastUpdateCheck)) else "never"
        Text("Last check: $last", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (settings.lastUpdateResult.isNotBlank()) Text(settings.lastUpdateResult, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (settings.updateFrequency == UpdateFrequency.MANUAL) Text("Automatic checks are off; nothing contacts GitHub until you press Check now.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            McButton(onCheckNow, enabled = ui !is UpdateUi.Checking) { Text(if (ui is UpdateUi.Checking) "Checking\u2026" else "Check now") }
            if (offer != null) McButton(onInstall, variant = McVariant.PRIMARY) { Text("Install ${offer.tag}") }
        }
        if (offer != null) Text("Update available: ${offer.tag}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = c.greenHighlight)
        if (ui is UpdateUi.Failed) Text(ui.message, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.danger)
    }
}

@Composable
fun DeveloperGroup(debugMode: Boolean, onChange: (Boolean) -> Unit) {
    Group("Developer") {
        Row(Modifier.fillMaxWidth().clickable { onChange(!debugMode) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Debug mode (leak detection notifications)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            McSwitch(debugMode, onChange)
        }
    }
}

@Composable
fun FirstBootAutoUpdateDialog(
    explainingPermission: Boolean,
    onSelectYes: () -> Unit,
    onSelectNo: () -> Unit,
    onBackToChoice: () -> Unit,
    onOpenInstallPermission: () -> Unit,
) {
    val c = Mc.colors
    if (!explainingPermission) {
        McDialog(
            title = "Automatic updates?",
            onDismiss = onSelectNo,
            buttons = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McButton(onSelectNo, Modifier.weight(1f), fullWidth = true) { Text("No", maxLines = 1) }
                    McButton(onSelectYes, Modifier.weight(1f), variant = McVariant.PRIMARY, fullWidth = true) { Text("Yes", maxLines = 1) }
                }
            },
        ) {
            Text(
                "Would you like MineMaps to check for and install new versions automatically?",
                style = MaterialTheme.typography.bodyLarge,
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Updates are downloaded from GitHub Releases and verified against their SHA-256 checksum and signing key. You can change this anytime in Settings > Updates & About.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    } else {
        McDialog(
            title = "Allow app installs",
            onDismiss = onBackToChoice,
            buttons = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        McButton(onBackToChoice, Modifier.weight(1f), fullWidth = true) { Text("Back", maxLines = 1) }
                        McButton(onSelectNo, Modifier.weight(1f), fullWidth = true) { Text("No, disable", maxLines = 1) }
                    }
                    McButton(onOpenInstallPermission, variant = McVariant.PRIMARY, fullWidth = true) { Text("Allow in Settings", maxLines = 1) }
                }
            },
        ) {
            Text(
                "Because MineMaps is installed outside the Google Play Store, Android requires your permission (\"Install unknown apps\") so MineMaps can install downloaded updates for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap \"Allow in Settings\" to turn on the switch for MineMaps, or tap \"Back\" / \"No, disable\" if you prefer to update manually.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    }
}

@Composable
fun UpdateDialog(ui: UpdateUi, offer: ReleaseInfo, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit, onCancel: () -> Unit, onDismissFailure: () -> Unit) {
    val busy = ui is UpdateUi.Downloading || ui is UpdateUi.Verifying || ui is UpdateUi.NeedsPermission || ui is UpdateUi.Installing
    McDialog(
        title = if (ui is UpdateUi.Failed) "Update failed" else "Update available",
        onDismiss = { if (!busy) { if (ui is UpdateUi.Failed) onDismissFailure() else onLater() } },
        buttons = {
            when {
                busy -> McButton(onCancel) { Text("Cancel") }
                ui is UpdateUi.Failed -> McButton(onDismissFailure, variant = McVariant.PRIMARY) { Text("OK") }
                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        McButton(onSkip, Modifier.weight(1f), fullWidth = true) { Text("Skip version", maxLines = 1) }
                        McButton(onLater, Modifier.weight(1f), fullWidth = true) { Text("Later", maxLines = 1) }
                    }
                    McButton(onUpdate, variant = McVariant.PRIMARY, fullWidth = true) { Text("Update now", maxLines = 1) }
                }
            }
        },
    ) {
        val c = Mc.colors
        if (ui is UpdateUi.Failed) {
            Text(ui.message, style = MaterialTheme.typography.bodyMedium, color = c.danger)
            return@McDialog
        }
        Text("MineMaps ${offer.tag} is available", style = MaterialTheme.typography.bodyLarge)
        if (offer.notes.isNotBlank() && !busy) Text(offer.notes, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (busy) {
            val (label, fraction) = when (ui) {
                is UpdateUi.Downloading -> "Downloading\u2026 ${ui.bytes / 1024 / 1024} / ${offer.apkSize / 1024 / 1024} MB" to (ui.bytes.toFloat() / offer.apkSize).coerceIn(0f, 1f)
                is UpdateUi.Verifying -> "Verifying checksum and signature\u2026" to 1f
                is UpdateUi.NeedsPermission -> "Allow MineMaps to install apps, then come back here." to 1f
                else -> "Installing\u2026" to 1f
            }
            Text(label, Modifier.padding(top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth().height(14.dp).background(c.outline).padding(2.dp)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.green))
            }
        }
    }
}

@Composable
fun TexturesExtra(available: Boolean, bundled: Boolean, source: String?, pending: Boolean, onNow: () -> Unit, onRemove: () -> Unit, onImportFile: () -> Unit) {
    val c = Mc.colors
    Spacer(Modifier.height(8.dp))
    when {
        available && bundled -> Text("Minecraft textures are bundled in this build.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        available -> {
            if (!source.isNullOrBlank()) Text(source, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            McButton(onRemove, variant = McVariant.DANGER) { Text("Remove") }
        }
        pending -> {
            Text("Will download after the next update (on Wi-Fi)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            McButton(onNow) { Text("Download now") }
        }
    }
    if (!(available && bundled)) {
        Spacer(Modifier.height(8.dp))
        McButton(onImportFile) { Text("Import from file\u2026") }
    }
}

@Composable
fun IncludeTexturesDialog(sizeMb: Long, metered: Boolean, onNow: () -> Unit, onNextUpdate: () -> Unit, onCancel: () -> Unit) {
    McDialog(
        title = "Minecraft textures",
        onDismiss = onCancel,
        buttons = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McButton(onCancel, Modifier.weight(1f), fullWidth = true) { Text("Cancel", maxLines = 1) }
                    McButton(onNextUpdate, Modifier.weight(1f), fullWidth = true) { Text("Next update", maxLines = 1) }
                }
                McButton(onNow, variant = McVariant.PRIMARY, fullWidth = true) { Text("Download now", maxLines = 1) }
            }
        },
    ) {
        Text(
            "Minecraft's textures (\u00a9 Mojang) are downloaded from Mojang's servers (Minecraft ${app.minemaps.engine.art.importer.MinecraftImporter.PINNED_VERSION}, about $sizeMb MB) and kept on this phone for your personal use. You should own Minecraft Java Edition.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (metered) Text("You are on a metered connection: downloading now uses mobile data.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Mc.colors.gold)
        Text("\"Next update\" downloads them automatically on Wi-Fi after the app is next updated.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Mc.colors.textSecondary)
    }
}

@Composable
fun PackProgressDialog(ui: app.minemaps.map.PackUi, onCancel: () -> Unit, onDismissFailure: () -> Unit) {
    val c = Mc.colors
    val failed = ui is app.minemaps.map.PackUi.Failed
    McDialog(
        title = if (failed) "Textures failed" else "Minecraft textures",
        onDismiss = { if (failed) onDismissFailure() },
        buttons = {
            if (failed) McButton(onDismissFailure, variant = McVariant.PRIMARY) { Text("OK") } else McButton(onCancel) { Text("Cancel") }
        },
    ) {
        if (ui is app.minemaps.map.PackUi.Failed) {
            Text(ui.message, style = MaterialTheme.typography.bodyMedium, color = c.danger)
            return@McDialog
        }
        val (label, fraction) = when (ui) {
            is app.minemaps.map.PackUi.Downloading -> (if (ui.total > 0) "Downloading\u2026 ${ui.bytes / 1_000_000} / ${ui.total / 1_000_000} MB" else "Reading file\u2026 ${ui.bytes / 1_000_000} MB") to (if (ui.total > 0) ui.bytes.toFloat() / ui.total else 0f)
            is app.minemaps.map.PackUi.Importing -> "Building the texture pack\u2026 ${ui.percent}%" to ui.percent / 100f
            else -> "" to 0f
        }
        Text(label, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().height(14.dp).background(c.outline).padding(2.dp)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.green))
        }
    }
}
