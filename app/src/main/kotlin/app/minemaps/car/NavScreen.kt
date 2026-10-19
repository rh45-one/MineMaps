package app.minemaps.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.minemaps.R
import app.minemaps.data.AppLanguage
import app.minemaps.engine.data.NetState
import app.minemaps.location.LocationState
import app.minemaps.location.LocationStatus
import app.minemaps.ui.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object CarStrips {
    private const val GRID = 16
    private const val OUT_PX = 64
    private const val BORDER = 0xFF141517.toInt()
    private const val STONE_BG = 0xFF4A4D52.toInt()
    private const val STONE_HI = 0xFF72767D.toInt()
    private const val STONE_SH = 0xFF26282B.toInt()
    private const val GREEN_BG = 0xFF2E5C1E.toInt()
    private const val GREEN_HI = 0xFF52A535.toInt()
    private const val GREEN_SH = 0xFF1D3B12.toInt()
    private const val FG_WHITE = 0xFFF2F4F7.toInt()
    private const val FG_DIM = 0xFF9EA4AE.toInt()
    private const val FG_SHADOW = 0xFF141517.toInt()

    private val cache = HashMap<String, CarIcon>()

    enum class ButtonGlyph {
        PLACES,
        STREETS,
        TRAFFIC,
        ALERTS,
        ZOOM_IN,
        ZOOM_OUT,
        FOLLOW,
    }

    /**
     * Renders a crisp 64x64 (16x16 pixel-art scaled 4x nearest-neighbour) Minecraft 3D bevelled button icon
     * matching the mobile UI's McButton / QuickMapMenuBar style (stone grey when inactive, emerald green when active).
     */
    @Synchronized
    fun mcButtonIcon(glyph: ButtonGlyph, active: Boolean = false): CarIcon = cache.getOrPut("${glyph.name}|$active") {
        val px = IntArray(GRID * GRID)
        val bg = if (active) GREEN_BG else STONE_BG
        val hi = if (active) GREEN_HI else STONE_HI
        val sh = if (active) GREEN_SH else STONE_SH
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                px[y * GRID + x] = when {
                    x == 0 || y == 0 || x == GRID - 1 || y == GRID - 1 -> BORDER
                    y == 1 || x == 1 -> hi
                    y == GRID - 2 || x == GRID - 2 -> sh
                    else -> bg
                }
            }
        }
        fun set(x: Int, y: Int, c: Int) {
            if (x in 2..(GRID - 3) && y in 2..(GRID - 3)) px[y * GRID + x] = c
        }
        fun setWithShadow(x: Int, y: Int, c: Int) {
            set(x + 1, y + 1, FG_SHADOW)
            set(x, y, c)
        }
        val fg = if (active || glyph == ButtonGlyph.PLACES || glyph == ButtonGlyph.ZOOM_IN || glyph == ButtonGlyph.ZOOM_OUT) FG_WHITE else FG_DIM
        when (glyph) {
            ButtonGlyph.PLACES -> {
                // Pixel-art pin / banner matching mobile Places button
                val gold = 0xFFFFD84A.toInt()
                for (x in 6..9) setWithShadow(x, 3, gold)
                for (x in 5..10) for (y in 4..7) setWithShadow(x, y, gold)
                set(7, 5, BORDER); set(8, 5, BORDER); set(7, 6, BORDER); set(8, 6, BORDER)
                for (x in 6..9) setWithShadow(x, 8, gold)
                for (x in 7..8) for (y in 9..10) setWithShadow(x, y, gold)
                setWithShadow(7, 11, gold)
            }
            ButtonGlyph.STREETS -> {
                // Pixel-art "Aa" glyph matching mobile Streets button
                val pts = arrayOf(
                    4 to 5, 5 to 5, 3 to 6, 6 to 6, 3 to 7, 6 to 7, 3 to 8, 4 to 8, 5 to 8, 6 to 8, 3 to 9, 6 to 9, 3 to 10, 6 to 10,
                    9 to 7, 10 to 7, 11 to 8, 9 to 9, 10 to 9, 11 to 9, 8 to 10, 11 to 10, 9 to 11, 10 to 11, 11 to 11,
                )
                for ((x, y) in pts) set(x + 1, y + 1, FG_SHADOW)
                for ((x, y) in pts) set(x, y, fg)
            }
            ButtonGlyph.TRAFFIC -> {
                // Pixel-art 3-lamp traffic light matching mobile TrafficLightGlyph
                for (y in 3..12) for (x in 5..10) set(x, y, BORDER)
                set(4, 4, BORDER); set(11, 4, BORDER)
                set(4, 7, BORDER); set(11, 7, BORDER)
                set(4, 10, BORDER); set(11, 10, BORDER)
                val red = if (active) 0xFFE53935.toInt() else 0xFF7A3B3B.toInt()
                val amber = if (active) 0xFFFFB300.toInt() else 0xFF7A6329.toInt()
                val green = if (active) 0xFF38C764.toInt() else 0xFF346E46.toInt()
                for (x in 7..8) { set(x, 4, red); set(x, 5, red) }
                for (x in 7..8) { set(x, 7, amber); set(x, 8, amber) }
                for (x in 7..8) { set(x, 10, green); set(x, 11, green) }
            }
            ButtonGlyph.ALERTS -> {
                // Pixel-art speed camera / radar badge matching MapRenderer camera icon
                for (y in 5..10) for (x in 4..11) setWithShadow(x, y, FG_WHITE)
                set(6, 4, FG_WHITE); set(7, 4, FG_WHITE)
                val lens = if (active) 0xFF4DD0E1.toInt() else 0xFF5D767D.toInt()
                for (y in 6..9) for (x in 6..9) set(x, y, BORDER)
                set(7, 7, lens); set(8, 7, lens); set(7, 8, lens); set(8, 8, lens)
                set(10, 6, if (active) 0xFFFF5252.toInt() else FG_DIM)
            }
            ButtonGlyph.ZOOM_IN -> {
                for (i in 4..11) { set(i + 1, 8, FG_SHADOW); set(i + 1, 9, FG_SHADOW); set(8, i + 1, FG_SHADOW); set(9, i + 1, FG_SHADOW) }
                for (i in 4..11) { set(i, 7, fg); set(i, 8, fg); set(7, i, fg); set(8, i, fg) }
            }
            ButtonGlyph.ZOOM_OUT -> {
                for (i in 4..11) { set(i + 1, 8, FG_SHADOW); set(i + 1, 9, FG_SHADOW) }
                for (i in 4..11) { set(i, 7, fg); set(i, 8, fg) }
            }
            ButtonGlyph.FOLLOW -> {
                val ring = arrayOf(
                    6 to 4, 7 to 4, 8 to 4, 9 to 4,
                    6 to 11, 7 to 11, 8 to 11, 9 to 11,
                    4 to 6, 4 to 7, 4 to 8, 4 to 9,
                    11 to 6, 11 to 7, 11 to 8, 11 to 9,
                    5 to 5, 10 to 5, 5 to 10, 10 to 10,
                    7 to 3, 8 to 3, 7 to 12, 8 to 12, 3 to 7, 3 to 8, 12 to 7, 12 to 8,
                )
                for ((x, y) in ring) set(x + 1, y + 1, FG_SHADOW)
                for ((x, y) in ring) set(x, y, fg)
                if (active) {
                    val center = 0xFF7CFF5B.toInt()
                    set(7, 7, center); set(8, 7, center); set(7, 8, center); set(8, 8, center)
                }
            }
        }
        val src = android.graphics.Bitmap.createBitmap(px, GRID, GRID, android.graphics.Bitmap.Config.ARGB_8888)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, OUT_PX, OUT_PX, false)
        CarIcon.Builder(IconCompat.createWithBitmap(scaled)).build()
    }

    fun icon(context: CarContext, res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(context, res)).setTint(CarColor.DEFAULT).build()

    /** Pan, zoom and recenter buttons drawn over the map in Minecraft bevelled pixel-art style. */
    fun mapActionStrip(context: CarContext, actions: CarMapActions, following: Boolean): ActionStrip = ActionStrip.Builder()
        .addAction(Action.PAN)
        .addAction(Action.Builder().setIcon(mcButtonIcon(ButtonGlyph.ZOOM_IN)).setOnClickListener { actions.zoomIn() }.build())
        .addAction(Action.Builder().setIcon(mcButtonIcon(ButtonGlyph.ZOOM_OUT)).setOnClickListener { actions.zoomOut() }.build())
        .addAction(
            Action.Builder()
                .setIcon(mcButtonIcon(ButtonGlyph.FOLLOW, active = following))
                .setOnClickListener { actions.recenter() }
                .build(),
        )
        .build()
}

/**
 * Root screen: a map-first NavigationTemplate (the navigation category gets the host's map slot, full screen or split).
 * It never starts a navigation session and has no route information.
 */
class NavScreen(
    carContext: CarContext,
    private val actions: CarMapActions,
    private val locationFlow: Flow<LocationState>,
    private val hasPermission: () -> Boolean,
    private val onPermissionGranted: () -> Unit,
    private val onOpenPlaces: () -> Unit,
    private val scheduler: InvalidateScheduler = HandlerInvalidateScheduler(),
    private val navBannerFlow: Flow<app.minemaps.nav.ActiveBannerState?> = flowOf(null),
    private val languageFlow: Flow<AppLanguage> = flowOf(AppLanguage.ENGLISH),
) : Screen(carContext) {
    private var location = LocationState(null, LocationStatus.SEARCHING)
    private var net = NetState.OK
    private var following = false
    private var streets = true
    private var traffic = true
    private var roadAlerts = true
    private var navBanner: app.minemaps.nav.ActiveBannerState? = null
    private var appLanguage = AppLanguage.ENGLISH
    private var scope: CoroutineScope? = null
    private var invalidatePending = false
    private var shownKey: List<Any?>? = null

    companion object {}

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                scope = s
                s.launch { locationFlow.collect { location = it; requestInvalidate() } }
                s.launch { actions.networkState.collect { net = it; requestInvalidate() } }
                s.launch { actions.following.collect { following = it; requestInvalidate() } }
                s.launch { actions.streetNames.collect { streets = it; requestInvalidate() } }
                s.launch { actions.traffic.collect { traffic = it; requestInvalidate() } }
                s.launch { actions.roadAlertsEnabled.collect { roadAlerts = it; requestInvalidate() } }
                s.launch { navBannerFlow.collect { navBanner = it; requestInvalidate() } }
                s.launch { languageFlow.collect { appLanguage = it; requestInvalidate() } }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
            }
        })
    }

    private fun strings(): AppStrings = AppStrings.forLanguage(appLanguage)

    private fun message(permission: Boolean): Pair<String, String?>? {
        val str = strings()
        return when {
            !permission -> str.locationPermissionCarMessage to null
            net == NetState.OFFLINE -> str.offlineShowingCached to null
            location.status == LocationStatus.DISABLED -> str.locationIsOff to null
            navBanner != null -> {
                val b = navBanner!!
                val title = if (b.distanceText.isNotBlank()) "${b.distanceText} \u00b7 ${b.instruction}" else b.instruction
                val sub = listOf(b.etaText, b.providerLabel).filter { it.isNotBlank() }.joinToString(" \u00b7 ").ifBlank { null }
                title to sub
            }
            else -> null
        }
    }

    private fun key(): List<Any?> = hasPermission().let { listOf(it, message(it), following, streets, traffic, roadAlerts, appLanguage) }

    private fun requestInvalidate() {
        if (invalidatePending || key() == shownKey) return
        invalidatePending = true
        scheduler.schedule(MapScreen.INVALIDATE_INTERVAL_MS) {
            invalidatePending = false
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val permission = hasPermission()
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(CarStrips.mcButtonIcon(CarStrips.ButtonGlyph.PLACES, active = true))
                    .setOnClickListener { onOpenPlaces() }
                    .build(),
            )
        if (!permission) {
            strip.addAction(
                Action.Builder()
                    .setTitle(strings().grantOnPhone)
                    .setOnClickListener(
                        ParkedOnlyOnClickListener.create {
                            carContext.requestPermissions(
                                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            ) { granted, _ ->
                                if (granted.isNotEmpty()) onPermissionGranted()
                                invalidate()
                            }
                        },
                    )
                    .build(),
            )
        } else {
            strip.addAction(
                Action.Builder()
                    .setIcon(CarStrips.mcButtonIcon(CarStrips.ButtonGlyph.STREETS, active = streets))
                    .setOnClickListener { actions.toggleStreetNames() }
                    .build(),
            )
            strip.addAction(
                Action.Builder()
                    .setIcon(CarStrips.mcButtonIcon(CarStrips.ButtonGlyph.TRAFFIC, active = traffic))
                    .setOnClickListener { actions.toggleTraffic() }
                    .build(),
            )
            strip.addAction(
                Action.Builder()
                    .setIcon(CarStrips.mcButtonIcon(CarStrips.ButtonGlyph.ALERTS, active = roadAlerts))
                    .setOnClickListener { actions.toggleRoadAlerts() }
                    .build(),
            )
        }
        val template = NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(CarStrips.mapActionStrip(carContext, actions, following))
        shownKey = key()
        message(permission)?.let { (title, sub) ->
            val mb = MessageInfo.Builder(title)
            if (!sub.isNullOrBlank()) mb.setText(sub)
            template.setNavigationInfo(mb.build())
        }
        return template.build()
    }
}

fun NavScreen.Companion.create(carContext: CarContext, controller: CarMapController, container: app.minemaps.AppContainer, onOpenPlaces: () -> Unit): NavScreen =
    NavScreen(
        carContext,
        controller,
        container.location.state,
        { androidx.core.content.ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED || androidx.core.content.ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED },
        { container.location.refresh(); controller.recenter() },
        onOpenPlaces,
        navBannerFlow = container.navigation.bannerState,
        languageFlow = container.settings.settings.map { it.appLanguage },
    )
