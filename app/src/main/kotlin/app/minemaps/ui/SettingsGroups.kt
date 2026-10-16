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
    val s = S
    Group(s.savedPlaces) {
        Text(s.savedPlacesBackupSub, style = MaterialTheme.typography.bodySmall, color = Mc.colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McButton(onExport) { Text(s.exportBtn) }
            McButton(onImport) { Text(s.importBtn) }
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
    val s = S
    Group(s.submenuNavigationTitle) {
        val modes = listOf(
            NavigationMode.OFF to s.navModeOff,
            NavigationMode.STANDALONE to s.navModeStandalone,
            NavigationMode.GOOGLE_ASSISTED to s.navModeGoogleAssisted,
        )
        McSegmented(modes.size, modes.indexOfFirst { it.first == settings.navigationMode }.coerceAtLeast(0), { onMode(modes[it].first) }) { i, _ ->
            Text(modes[i].second, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        when (settings.navigationMode) {
            NavigationMode.OFF -> Text(
                s.navDescOff,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            NavigationMode.STANDALONE -> Text(
                s.navDescStandalone,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            NavigationMode.GOOGLE_ASSISTED -> {
                Text(
                    s.navDescGoogleAssisted,
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
                        Text(s.mirrorGoogleMapsTitle, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (listenerGranted) s.mirrorGrantedSub else s.mirrorNeededSub,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (listenerGranted) c.greenHighlight else c.textSecondary,
                        )
                    }
                    McSwitch(settings.mirrorGoogleMaps, onMirrorChange)
                }
                if (settings.mirrorGoogleMaps && !listenerGranted) {
                    Spacer(Modifier.height(6.dp))
                    McButton(onOpenListenerSettings, variant = McVariant.PRIMARY) { Text(s.grantNotificationAccess) }
                }
                Spacer(Modifier.height(12.dp))
                Text(s.googleApiKeyTitle, style = MaterialTheme.typography.bodyMedium)
                Text(
                    s.googleApiKeySub,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                McTextField(
                    value = settings.googleApiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = s.googleApiKeyPlaceholder,
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
                Text(s.showTrafficTitle, style = MaterialTheme.typography.bodyLarge)
                Text(s.showTrafficSub, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
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
                Text(s.showRoadAlertsTitle, style = MaterialTheme.typography.bodyLarge)
                Text(s.showRoadAlertsSub, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
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
                Text(s.dynamicZoomTitle, style = MaterialTheme.typography.bodyLarge)
                Text(s.dynamicZoomSub, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            McSwitch(settings.dynamicDrivingZoom, onDynamicZoomChange)
        }
    }
}

@Composable
fun ActiveNavBanner(banner: ActiveBannerState, onStop: () -> Unit, onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    val c = Mc.colors
    val s = S
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
            val sub = listOf(banner.etaText, banner.providerLabel, s.tapForSteps).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
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
    val str = S
    val steps = plan?.steps.orEmpty()
    McDialog(
        title = banner.destinationName.ifBlank { str.directionsTitle },
        onDismiss = onDismiss,
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McButton(onStopNavigation, Modifier.weight(1f), variant = McVariant.DANGER, fullWidth = true) { Text(str.endRoute, maxLines = 1) }
                McButton(onDismiss, Modifier.weight(1f), variant = McVariant.PRIMARY, fullWidth = true) { Text(str.close, maxLines = 1) }
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
    val s = S
    Group(s.updatesHeader) {
        Text(s.versionPrefix(BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
        if (!enabled) {
            Text(s.updatesDisabledDebug, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            return@Group
        }
        Spacer(Modifier.height(8.dp))
        val freqs = listOf(UpdateFrequency.LAUNCH to s.freqLaunch, UpdateFrequency.DAILY to s.freqDaily, UpdateFrequency.WEEKLY to s.freqWeekly, UpdateFrequency.MANUAL to s.freqManual)
        McSegmented(freqs.size, freqs.indexOfFirst { it.first == settings.updateFrequency }.coerceAtLeast(0), { onFrequency(freqs[it].first) }) { i, _ ->
            Text(freqs[i].second, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        val last = if (settings.lastUpdateCheck > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastUpdateCheck)) else s.neverWord
        Text(s.lastCheckPrefix(last), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (settings.lastUpdateResult.isNotBlank()) Text(settings.lastUpdateResult, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (settings.updateFrequency == UpdateFrequency.MANUAL) Text(s.manualChecksNote, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            McButton(onCheckNow, enabled = ui !is UpdateUi.Checking) { Text(if (ui is UpdateUi.Checking) s.checking else s.checkNow) }
            if (offer != null) McButton(onInstall, variant = McVariant.PRIMARY) { Text(s.installTag(offer.tag)) }
        }
        if (offer != null) Text(s.updateAvailableTag(offer.tag), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = c.greenHighlight)
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
    val s = S
    if (!explainingPermission) {
        McDialog(
            title = s.autoUpdatesPromptTitle,
            onDismiss = onSelectNo,
            buttons = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McButton(onSelectNo, Modifier.weight(1f), fullWidth = true) { Text(s.no, maxLines = 1) }
                    McButton(onSelectYes, Modifier.weight(1f), variant = McVariant.PRIMARY, fullWidth = true) { Text(s.yes, maxLines = 1) }
                }
            },
        ) {
            Text(
                s.autoUpdatesPromptBody,
                style = MaterialTheme.typography.bodyLarge,
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                s.autoUpdatesPromptNote,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    } else {
        McDialog(
            title = s.allowAppInstallsTitle,
            onDismiss = onBackToChoice,
            buttons = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        McButton(onBackToChoice, Modifier.weight(1f), fullWidth = true) { Text(s.back, maxLines = 1) }
                        McButton(onSelectNo, Modifier.weight(1f), fullWidth = true) { Text(s.noDisable, maxLines = 1) }
                    }
                    McButton(onOpenInstallPermission, variant = McVariant.PRIMARY, fullWidth = true) { Text(s.allowInSettings, maxLines = 1) }
                }
            },
        ) {
            Text(
                s.allowAppInstallsBody,
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                s.allowAppInstallsNote,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    }
}

@Composable
fun UpdateDialog(ui: UpdateUi, offer: ReleaseInfo, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit, onCancel: () -> Unit, onDismissFailure: () -> Unit) {
    val s = S
    val busy = ui is UpdateUi.Downloading || ui is UpdateUi.Verifying || ui is UpdateUi.NeedsPermission || ui is UpdateUi.Installing
    McDialog(
        title = if (ui is UpdateUi.Failed) s.updateFailedTitle else s.updateAvailableTitle,
        onDismiss = { if (!busy) { if (ui is UpdateUi.Failed) onDismissFailure() else onLater() } },
        buttons = {
            when {
                busy -> McButton(onCancel) { Text(s.cancel) }
                ui is UpdateUi.Failed -> McButton(onDismissFailure, variant = McVariant.PRIMARY) { Text(s.ok) }
                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        McButton(onSkip, Modifier.weight(1f), fullWidth = true) { Text(s.skipVersion, maxLines = 1) }
                        McButton(onLater, Modifier.weight(1f), fullWidth = true) { Text(s.later, maxLines = 1) }
                    }
                    McButton(onUpdate, variant = McVariant.PRIMARY, fullWidth = true) { Text(s.updateNow, maxLines = 1) }
                }
            }
        },
    ) {
        val c = Mc.colors
        if (ui is UpdateUi.Failed) {
            Text(ui.message, style = MaterialTheme.typography.bodyMedium, color = c.danger)
            return@McDialog
        }
        Text(s.minemapsVersionAvailable(offer.tag), style = MaterialTheme.typography.bodyLarge)
        if (offer.notes.isNotBlank() && !busy) Text(offer.notes, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (busy) {
            val (label, fraction) = when (ui) {
                is UpdateUi.Downloading -> "Downloading\u2026 ${ui.bytes / 1024 / 1024} / ${offer.apkSize / 1024 / 1024} MB" to (ui.bytes.toFloat() / offer.apkSize).coerceIn(0f, 1f)
                is UpdateUi.Verifying -> s.verifyingChecksum to 1f
                is UpdateUi.NeedsPermission -> s.allowInstallAppsHint to 1f
                else -> s.installing to 1f
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
    val s = S
    Spacer(Modifier.height(8.dp))
    when {
        available && bundled -> Text(s.texturesBundledNote, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        available -> {
            if (!source.isNullOrBlank()) Text(source, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            McButton(onRemove, variant = McVariant.DANGER) { Text(s.remove) }
        }
        pending -> {
            Text(s.pendingWifiDownloadNote, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            McButton(onNow) { Text(s.downloadNow) }
        }
    }
    if (!(available && bundled)) {
        Spacer(Modifier.height(8.dp))
        McButton(onImportFile) { Text(s.importFromFile) }
    }
}

@Composable
fun IncludeTexturesDialog(sizeMb: Long, metered: Boolean, onNow: () -> Unit, onNextUpdate: () -> Unit, onCancel: () -> Unit) {
    val s = S
    McDialog(
        title = s.minecraftTextures,
        onDismiss = onCancel,
        buttons = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McButton(onCancel, Modifier.weight(1f), fullWidth = true) { Text(s.cancel, maxLines = 1) }
                    McButton(onNextUpdate, Modifier.weight(1f), fullWidth = true) { Text(s.nextUpdate, maxLines = 1) }
                }
                McButton(onNow, variant = McVariant.PRIMARY, fullWidth = true) { Text(s.downloadNow, maxLines = 1) }
            }
        },
    ) {
        Text(
            s.includeTexturesBody(sizeMb),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (metered) Text(s.meteredConnectionWarning, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Mc.colors.gold)
        Text(s.nextUpdateWifiNote, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Mc.colors.textSecondary)
    }
}

@Composable
fun PackProgressDialog(ui: app.minemaps.map.PackUi, onCancel: () -> Unit, onDismissFailure: () -> Unit) {
    val c = Mc.colors
    val s = S
    val failed = ui is app.minemaps.map.PackUi.Failed
    McDialog(
        title = if (failed) s.texturesFailedTitle else s.minecraftTextures,
        onDismiss = { if (failed) onDismissFailure() },
        buttons = {
            if (failed) McButton(onDismissFailure, variant = McVariant.PRIMARY) { Text(s.ok) } else McButton(onCancel) { Text(s.cancel) }
        },
    ) {
        if (ui is app.minemaps.map.PackUi.Failed) {
            Text(ui.message, style = MaterialTheme.typography.bodyMedium, color = c.danger)
            return@McDialog
        }
        val (label, fraction) = when (ui) {
            is app.minemaps.map.PackUi.Downloading -> (if (ui.total > 0) "Downloading\u2026 ${ui.bytes / 1_000_000} / ${ui.total / 1_000_000} MB" else "Reading file\u2026 ${ui.bytes / 1_000_000} MB") to (if (ui.total > 0) ui.bytes.toFloat() / ui.total else 0f)
            is app.minemaps.map.PackUi.Importing -> s.buildingTexturePack(ui.percent) to ui.percent / 100f
            else -> "" to 0f
        }
        Text(label, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().height(14.dp).background(c.outline).padding(2.dp)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.green))
        }
    }
}
