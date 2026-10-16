package app.minemaps.car

import android.Manifest
import android.location.Location
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.minemaps.AppContainer
import app.minemaps.R
import app.minemaps.data.AppLanguage
import app.minemaps.data.SavedPlace
import app.minemaps.engine.data.NetState
import app.minemaps.location.LocationState
import app.minemaps.location.LocationStatus
import app.minemaps.ui.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

interface ItemIcons {
    /** Changes (0 = not ready) whenever the icon set becomes available or the texture pack changes. */
    val version: Flow<Int>

    fun icon(name: String): CarIcon?
}

object NoItemIcons : ItemIcons {
    override val version: Flow<Int> = kotlinx.coroutines.flow.flowOf(0)

    override fun icon(name: String): CarIcon? = null
}

class AtlasItemIcons(
    private val engine: suspend () -> app.minemaps.map.MapEngine,
    private val pack: Flow<app.minemaps.engine.art.TexturePack>,
) : ItemIcons {
    private val version_ = kotlinx.coroutines.flow.MutableStateFlow(0)
    override val version: Flow<Int> = version_

    @Volatile
    private var atlas: app.minemaps.engine.art.TextureAtlas? = null
    private val cache = HashMap<String, CarIcon?>()

    suspend fun run() {
        val e = engine()
        pack.collect { p ->
            synchronized(this) {
                cache.clear()
                atlas = e.atlas(p)
            }
            version_.value = version_.value + 1
        }
    }

    @Synchronized
    override fun icon(name: String): CarIcon? = cache.getOrPut(name) {
        val a = atlas ?: return@getOrPut null
        val px = a.icon(name) ?: a.icon("pin") ?: return@getOrPut null
        val src = android.graphics.Bitmap.createBitmap(px, 16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, 64, 64, false)
        CarIcon.Builder(IconCompat.createWithBitmap(scaled)).build()
    }
}

fun interface InvalidateScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
}

class HandlerInvalidateScheduler : InvalidateScheduler {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun schedule(delayMs: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMs)
    }
}

class MapScreen(
    carContext: CarContext,
    private val actions: CarMapActions,
    private val savedPlacesFlow: Flow<List<SavedPlace>>,
    private val locationFlow: Flow<LocationState>,
    private val hasPermission: () -> Boolean,
    private val onPermissionGranted: () -> Unit,
    private val contentLimit: () -> Int,
    private val scheduler: InvalidateScheduler = HandlerInvalidateScheduler(),
    private val icons: ItemIcons = NoItemIcons,
    private val languageFlow: Flow<AppLanguage> = flowOf(AppLanguage.ENGLISH),
) : Screen(carContext) {
    data class PlaceRow(val place: SavedPlace, val distanceBucket: Int?)

    data class ContentState(
        val permission: Boolean,
        val statusText: String?,
        val rows: List<PlaceRow>,
        val iconsVersion: Int = 0,
        val lang: AppLanguage = AppLanguage.ENGLISH,
    )

    private var places: List<SavedPlace> = emptyList()
    private var location = LocationState(null, LocationStatus.SEARCHING)
    private var net: NetState = NetState.OK
    private var following = false
    private var iconsVersion = 0
    private var appLanguage = AppLanguage.ENGLISH
    private var state = compute()
    private var collectors: Job? = null
    private var scope: CoroutineScope? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                scope = s
                collectors = s.launch {
                    launch { savedPlacesFlow.collect { places = it; refresh() } }
                    launch { locationFlow.collect { location = it; refresh() } }
                    launch { actions.networkState.collect { net = it; refresh() } }
                    launch { icons.version.collect { iconsVersion = it; refresh() } }
                    launch { actions.following.collect { following = it; requestInvalidate() } }
                    launch { languageFlow.collect { appLanguage = it; refresh() } }
                }
                refresh()
            }

            override fun onStop(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
                collectors = null
            }
        })
    }

    private fun strings(): AppStrings = AppStrings.forLanguage(appLanguage)

    private fun compute(): ContentState {
        val permission = hasPermission()
        val str = strings()
        val statusText = when {
            net == NetState.OFFLINE -> str.offlineShowingCached
            !permission -> null
            location.status == LocationStatus.DISABLED -> str.locationIsOff
            location.status == LocationStatus.SEARCHING -> str.findingYourLocation
            else -> null
        }
        val loc = location.location
        val rows = places.map { p ->
            val bucket = loc?.let { (distanceMeters(it, p) / DISTANCE_STEP_M).toInt() }
            PlaceRow(p, bucket)
        }.let { list -> if (loc != null) list.sortedBy { it.distanceBucket } else list }
        return ContentState(permission, statusText, rows, iconsVersion, appLanguage)
    }

    private fun refresh() {
        val next = compute()
        if (next != state) {
            state = next
            requestInvalidate()
        }
    }

    private var invalidatePending = false

    private fun requestInvalidate() {
        if (invalidatePending) return
        invalidatePending = true
        scheduler.schedule(INVALIDATE_INTERVAL_MS) {
            invalidatePending = false
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        state = compute()
        val content: Template = if (!state.permission) permissionTemplate() else listTemplate()
        if (runCatching { carContext.carAppApiLevel }.getOrDefault(7) < 7) return content
        return MapWithContentTemplate.Builder()
            .setMapController(mapController())
            .setContentTemplate(content)
            .build()
    }

    private fun mapController(): MapController =
        MapController.Builder().setMapActionStrip(CarStrips.mapActionStrip(carContext, actions, following)).build()

    private fun permissionTemplate(): Template =
        MessageTemplate.Builder(strings().locationPermissionCarMessage)
            .setTitle(carContext.getString(R.string.app_name))
            .addAction(
                Action.Builder()
                    .setTitle(strings().grantOnPhone)
                    .setOnClickListener(
                        ParkedOnlyOnClickListener.create {
                            carContext.requestPermissions(
                                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            ) { granted, _ ->
                                if (granted.isNotEmpty()) onPermissionGranted()
                                refresh()
                                requestInvalidate()
                            }
                        },
                    )
                    .build(),
            )
            .build()

    private fun listTemplate(): Template {
        val list = ItemList.Builder()
        var budget = contentLimit()
        state.statusText?.let {
            if (budget > 0) {
                list.addItem(Row.Builder().setTitle(it).build())
                budget--
            }
        }
        for (r in state.rows) {
            if (budget <= 0) break
            val row = Row.Builder().setTitle(r.place.name)
            r.distanceBucket?.let { row.addText(formatDistance(it)) }
            icons.icon(r.place.icon)?.let { row.setImage(it, Row.IMAGE_TYPE_LARGE) }
            row.setOnClickListener {
                actions.flyTo(r.place.lat, r.place.lon)
            }
            list.addItem(row.build())
            budget--
        }
        list.setNoItemsMessage(strings().savePlacesOnPhoneCarHint)
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(if (appLanguage == AppLanguage.ENGLISH) "Places" else strings().savedPlaces)
            .setHeaderAction(Action.BACK)
            .build()
    }

    companion object {
        const val DISTANCE_STEP_M = 100f
        const val INVALIDATE_INTERVAL_MS = 500L

        fun create(carContext: CarContext, controller: CarMapController, container: AppContainer, icons: ItemIcons): MapScreen =
            MapScreen(
                carContext,
                controller,
                container.savedPlaces.places,
                container.location.state,
                { ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                { container.location.refresh(); controller.recenter() },
                { carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST) },
                icons = icons,
                languageFlow = container.settings.settings.map { it.appLanguage },
            )

        fun distanceMeters(from: Location, p: SavedPlace): Float {
            val out = FloatArray(1)
            Location.distanceBetween(from.latitude, from.longitude, p.lat, p.lon, out)
            return out[0]
        }

        fun formatDistance(bucket: Int): String {
            val m = bucket * DISTANCE_STEP_M.toInt()
            return when {
                m < 1000 -> "$m m"
                m < 10_000 -> String.format(Locale.US, "%.1f km", m / 1000.0)
                else -> String.format(Locale.US, "%d km", m / 1000)
            }
        }
    }
}
