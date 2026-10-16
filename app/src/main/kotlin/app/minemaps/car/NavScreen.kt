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
    fun icon(context: CarContext, res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(context, res)).setTint(CarColor.DEFAULT).build()

    /** Pan, zoom and recenter buttons drawn over the map. */
    fun mapActionStrip(context: CarContext, actions: CarMapActions, following: Boolean): ActionStrip = ActionStrip.Builder()
        .addAction(Action.PAN)
        .addAction(Action.Builder().setIcon(icon(context, R.drawable.ic_car_zoom_in)).setOnClickListener { actions.zoomIn() }.build())
        .addAction(Action.Builder().setIcon(icon(context, R.drawable.ic_car_zoom_out)).setOnClickListener { actions.zoomOut() }.build())
        .addAction(
            Action.Builder()
                .setIcon(icon(context, if (following) R.drawable.ic_car_following else R.drawable.ic_car_not_following))
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

    private fun key(): List<Any?> = hasPermission().let { listOf(it, message(it), following, streets, appLanguage) }

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
            .addAction(Action.Builder().setIcon(CarStrips.icon(carContext, R.drawable.ic_car_places)).setOnClickListener { onOpenPlaces() }.build())
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
                    .setIcon(CarStrips.icon(carContext, if (streets) R.drawable.ic_car_streets else R.drawable.ic_car_streets_off))
                    .setOnClickListener { actions.toggleStreetNames() }
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
