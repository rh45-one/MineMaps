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
import app.minemaps.data.Settings
import app.minemaps.data.UpdateFrequency
import app.minemaps.update.ReleaseInfo
import app.minemaps.update.UpdateUi
import app.minemaps.ui.theme.Mc
import app.minemaps.ui.theme.McButton
import app.minemaps.ui.theme.McDialog
import app.minemaps.ui.theme.McSegmented
import app.minemaps.ui.theme.McSwitch
import app.minemaps.ui.theme.McVariant
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
fun UpdateDialog(ui: UpdateUi, offer: ReleaseInfo, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit, onCancel: () -> Unit, onDismissFailure: () -> Unit) {
    val busy = ui is UpdateUi.Downloading || ui is UpdateUi.Verifying || ui is UpdateUi.NeedsPermission || ui is UpdateUi.Installing
    McDialog(
        title = if (ui is UpdateUi.Failed) "Update failed" else "Update available",
        onDismiss = { if (!busy) { if (ui is UpdateUi.Failed) onDismissFailure() else onLater() } },
        buttons = {
            when {
                busy -> McButton(onCancel) { Text("Cancel") }
                ui is UpdateUi.Failed -> McButton(onDismissFailure, variant = McVariant.PRIMARY) { Text("OK") }
                else -> {
                    McButton(onSkip) { Text("Skip this version") }
                    McButton(onLater) { Text("Later") }
                    McButton(onUpdate, variant = McVariant.PRIMARY) { Text("Update") }
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
