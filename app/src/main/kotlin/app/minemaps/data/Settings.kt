package app.minemaps.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.minemaps.engine.art.TexturePack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LabelNames { ENGLISH, LOCAL }

enum class UpdateFrequency { LAUNCH, DAILY, WEEKLY, MANUAL }

enum class NavigationMode { OFF, STANDALONE, GOOGLE_ASSISTED }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val showIcons: Boolean = true,
    val labelNames: LabelNames = LabelNames.ENGLISH,
    val showStreetNames: Boolean = true,
    val arrowColor: Int = ArrowColors.DEFAULT,
    val texturePack: TexturePack = TexturePack.ORIGINAL,
    val permissionAsked: Boolean = false,
    val rationaleDismissed: Boolean = false,
    val debugMode: Boolean = false,
    val updateFrequency: UpdateFrequency = UpdateFrequency.DAILY,
    val lastUpdateCheck: Long = 0L,
    val lastUpdateResult: String = "",
    val skippedVersionCode: Int = 0,
    val texturesPending: Boolean = false,
    val pendingSinceVersionCode: Int = 0,
    val navigationMode: NavigationMode = NavigationMode.STANDALONE,
    val mirrorGoogleMaps: Boolean = true,
    val googleApiKey: String = "",
    val showTraffic: Boolean = true,
    val showRoadAlerts: Boolean = true,
    val dynamicDrivingZoom: Boolean = true,
    val autoUpdatePromptHandled: Boolean = false,
)

object ArrowColors {
    const val DEFAULT = 0xFFFFFFFF.toInt()
    val presets: List<Pair<String, Int>> = listOf(
        "White" to 0xFFFFFFFF.toInt(),
        "Red" to 0xFFE53935.toInt(),
        "Orange" to 0xFFFB8C00.toInt(),
        "Yellow" to 0xFFFDD835.toInt(),
        "Lime" to 0xFF7CB342.toInt(),
        "Cyan" to 0xFF00ACC1.toInt(),
        "Blue" to 0xFF1E88E5.toInt(),
        "Purple" to 0xFF8E24AA.toInt(),
        "Pink" to 0xFFEC407A.toInt(),
    )
}

class SettingsRepository(private val store: DataStore<Preferences>) {
    private val themeKey = stringPreferencesKey("theme")
    private val iconsKey = booleanPreferencesKey("show_icons")
    private val namesKey = stringPreferencesKey("label_names")
    private val streetsKey = booleanPreferencesKey("show_street_names")
    private val packKey = stringPreferencesKey("texture_pack")
    private val arrowKey = intPreferencesKey("arrow_color")
    private val askedKey = booleanPreferencesKey("permission_asked")
    private val rationaleKey = booleanPreferencesKey("rationale_dismissed")
    private val debugKey = booleanPreferencesKey("debug_mode")
    private val freqKey = stringPreferencesKey("update_frequency")
    private val lastCheckKey = longPreferencesKey("last_update_check")
    private val lastResultKey = stringPreferencesKey("last_update_result")
    private val skippedKey = intPreferencesKey("skipped_version_code")
    private val pendingKey = booleanPreferencesKey("textures_pending")
    private val pendingSinceKey = intPreferencesKey("pending_since_version_code")
    private val navModeKey = stringPreferencesKey("navigation_mode")
    private val mirrorGmapsKey = booleanPreferencesKey("mirror_google_maps")
    private val googleKey = stringPreferencesKey("google_api_key")
    private val trafficKey = booleanPreferencesKey("show_traffic")
    private val alertsKey = booleanPreferencesKey("show_road_alerts")
    private val dynZoomKey = booleanPreferencesKey("dynamic_driving_zoom")
    private val autoUpdatePromptKey = booleanPreferencesKey("auto_update_prompt_handled")

    val settings: Flow<Settings> = store.safeData().map { p ->
        Settings(
            theme = p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            showIcons = p[iconsKey] ?: true,
            labelNames = if (p[namesKey] == LabelNames.LOCAL.name) LabelNames.LOCAL else LabelNames.ENGLISH,
            showStreetNames = p[streetsKey] ?: true,
            arrowColor = p[arrowKey] ?: ArrowColors.DEFAULT,
            texturePack = p[packKey]?.let { runCatching { TexturePack.valueOf(it) }.getOrNull() } ?: TexturePack.ORIGINAL,
            permissionAsked = p[askedKey] ?: false,
            rationaleDismissed = p[rationaleKey] ?: false,
            debugMode = p[debugKey] ?: false,
            updateFrequency = p[freqKey]?.let { runCatching { UpdateFrequency.valueOf(it) }.getOrNull() } ?: UpdateFrequency.DAILY,
            lastUpdateCheck = p[lastCheckKey] ?: 0L,
            lastUpdateResult = p[lastResultKey]?.take(200) ?: "",
            skippedVersionCode = p[skippedKey] ?: 0,
            texturesPending = p[pendingKey] ?: false,
            pendingSinceVersionCode = p[pendingSinceKey] ?: 0,
            navigationMode = p[navModeKey]?.let { runCatching { NavigationMode.valueOf(it) }.getOrNull() } ?: NavigationMode.STANDALONE,
            mirrorGoogleMaps = p[mirrorGmapsKey] ?: true,
            googleApiKey = p[googleKey]?.take(128)?.trim() ?: "",
            showTraffic = p[trafficKey] ?: true,
            showRoadAlerts = p[alertsKey] ?: true,
            dynamicDrivingZoom = p[dynZoomKey] ?: true,
            autoUpdatePromptHandled = p[autoUpdatePromptKey] ?: false,
        )
    }

    suspend fun setTheme(v: ThemeMode) = store.edit { it[themeKey] = v.name }

    suspend fun setShowIcons(v: Boolean) = store.edit { it[iconsKey] = v }

    suspend fun setShowStreetNames(v: Boolean) = store.edit { it[streetsKey] = v }

    suspend fun setLabelNames(v: LabelNames) = store.edit { it[namesKey] = v.name }

    suspend fun setTexturePack(v: TexturePack) = store.edit { it[packKey] = v.name }

    suspend fun setArrowColor(v: Int) = store.edit { it[arrowKey] = v }

    suspend fun setPermissionAsked() = store.edit { it[askedKey] = true }

    suspend fun setRationaleDismissed() = store.edit { it[rationaleKey] = true }

    suspend fun setDebugMode(v: Boolean) = store.edit { it[debugKey] = v }

    suspend fun setUpdateFrequency(v: UpdateFrequency) = store.edit { it[freqKey] = v.name }

    suspend fun setUpdateCheck(at: Long?, result: String) = store.edit {
        if (at != null) it[lastCheckKey] = at
        it[lastResultKey] = result.take(200)
    }

    suspend fun setSkippedVersion(code: Int) = store.edit { it[skippedKey] = code }

    suspend fun setTexturesPending(pending: Boolean, sinceVersionCode: Int) = store.edit {
        it[pendingKey] = pending
        it[pendingSinceKey] = sinceVersionCode
    }

    suspend fun setNavigationMode(v: NavigationMode) = store.edit { it[navModeKey] = v.name }

    suspend fun setMirrorGoogleMaps(v: Boolean) = store.edit { it[mirrorGmapsKey] = v }

    suspend fun setGoogleApiKey(v: String) = store.edit { it[googleKey] = v.trim().take(128) }

    suspend fun setShowTraffic(v: Boolean) = store.edit { it[trafficKey] = v }

    suspend fun setShowRoadAlerts(v: Boolean) = store.edit { it[alertsKey] = v }

    suspend fun setDynamicDrivingZoom(v: Boolean) = store.edit { it[dynZoomKey] = v }

    suspend fun setAutoUpdatePromptHandled(v: Boolean = true) = store.edit { it[autoUpdatePromptKey] = v }
}
