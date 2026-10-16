package app.minemaps.nav

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.minemaps.engine.routing.TurnDirection
import kotlinx.coroutines.flow.MutableStateFlow

data class MirroredNavInfo(
    val instruction: String,
    val distanceText: String,
    val etaText: String,
    val direction: TurnDirection,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

object GoogleMapsMirror {
    const val GMAPS_PACKAGE = "com.google.android.apps.maps"
    val active = MutableStateFlow<MirroredNavInfo?>(null)
    @Volatile
    private var dismissedSession = false
    @Volatile
    private var exitAction: android.app.PendingIntent? = null

    /** Dismisses the mirrored Google Maps banner and triggers Google Maps' notification "Exit navigation" action if available. */
    fun dismissAndExitGoogleMaps() {
        dismissedSession = true
        active.value = null
        val pending = exitAction
        exitAction = null
        if (pending != null) {
            runCatching { pending.send() }
        }
    }

    /** Clears the dismissed-session flag when the user explicitly starts a new navigation from MineMaps. */
    fun resetDismissed() {
        dismissedSession = false
    }

    internal fun onNotificationUpdated(info: MirroredNavInfo?, exit: android.app.PendingIntent?) {
        exitAction = exit
        if (dismissedSession) return
        active.value = info
    }

    internal fun onNotificationCleared() {
        dismissedSession = false
        exitAction = null
        active.value = null
    }

    fun isListenerEnabled(context: Context): Boolean = runCatching {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val cn = ComponentName(context, GoogleMapsNotificationService::class.java).flattenToString()
        flat.split(':').any { it.equals(cn, ignoreCase = true) }
    }.getOrDefault(false)

    fun listenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Extracts navigation text from a Google Maps notification (title, text, subText) into a structured [MirroredNavInfo]. */
    fun parseFields(title: String?, text: String?, subText: String?, bigText: String? = null): MirroredNavInfo? {
        val rawTitle = title?.trim().orEmpty()
        val rawText = (bigText?.takeIf { it.isNotBlank() } ?: text)?.trim().orEmpty()
        val rawSub = subText?.trim().orEmpty()
        if (rawTitle.isBlank() && rawText.isBlank()) return null

        // Google Maps typically puts either the distance ("250 m") or the maneuver in title, and the street/instruction in text.
        val distRegex = Regex("""^\d+(?:[.,]\d+)?\s*(?:m|km|ft|mi|yd)\b""", RegexOption.IGNORE_CASE)
        val distance: String
        val instruction: String
        when {
            distRegex.containsMatchIn(rawTitle) && rawText.isNotBlank() -> {
                distance = rawTitle
                instruction = rawText.replace('\n', ' ')
            }
            distRegex.containsMatchIn(rawText) && rawTitle.isNotBlank() -> {
                val m = distRegex.find(rawText)!!
                distance = m.value
                val rest = rawText.removePrefix(m.value).trimStart(' ', '-', '\u00b7', '\u2022', ',')
                instruction = if (rest.isNotBlank()) "$rawTitle \u00b7 $rest" else rawTitle
            }
            rawTitle.isNotBlank() && rawText.isNotBlank() -> {
                distance = ""
                instruction = "$rawTitle \u2013 ${rawText.replace('\n', ' ')}"
            }
            else -> {
                distance = ""
                instruction = rawTitle.ifBlank { rawText }.replace('\n', ' ')
            }
        }
        val dir = inferDirection("$rawTitle $rawText")
        return MirroredNavInfo(
            instruction = instruction.take(140),
            distanceText = distance.take(32),
            etaText = rawSub.take(64),
            direction = dir,
        )
    }

    fun inferDirection(s: String): TurnDirection {
        val lower = s.lowercase()
        return when {
            "u-turn" in lower || "uturn" in lower || "make a u" in lower -> TurnDirection.U_TURN
            "roundabout" in lower || "rotary" in lower -> TurnDirection.ROUNDABOUT
            "sharp left" in lower -> TurnDirection.SHARP_LEFT
            "slight left" in lower || "keep left" in lower -> TurnDirection.SLIGHT_LEFT
            "left" in lower -> TurnDirection.LEFT
            "sharp right" in lower -> TurnDirection.SHARP_RIGHT
            "slight right" in lower || "keep right" in lower -> TurnDirection.SLIGHT_RIGHT
            "right" in lower -> TurnDirection.RIGHT
            "arrive" in lower || "destination" in lower -> TurnDirection.ARRIVE
            else -> TurnDirection.STRAIGHT
        }
    }
}

class GoogleMapsNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshFromActive()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != GoogleMapsMirror.GMAPS_PACKAGE) return
        updateFrom(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == GoogleMapsMirror.GMAPS_PACKAGE) {
            GoogleMapsMirror.onNotificationCleared()
        }
    }

    private fun refreshFromActive() {
        runCatching {
            val gmaps = activeNotifications?.firstOrNull { it.packageName == GoogleMapsMirror.GMAPS_PACKAGE }
            if (gmaps != null) updateFrom(gmaps) else GoogleMapsMirror.onNotificationCleared()
        }
    }

    private fun updateFrom(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val isNav = n.category == Notification.CATEGORY_NAVIGATION || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!isNav) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val exitIntent = n.actions?.firstOrNull { a ->
            val label = a.title?.toString()?.lowercase().orEmpty()
            "exit" in label || "stop" in label || "cancel" in label || "close" in label || "salir" in label || "cerrar" in label
        }?.actionIntent ?: n.actions?.firstOrNull()?.actionIntent
        GoogleMapsMirror.onNotificationUpdated(GoogleMapsMirror.parseFields(title, text, sub, big), exitIntent)
    }
}
