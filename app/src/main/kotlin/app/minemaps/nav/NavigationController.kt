package app.minemaps.nav

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.minemaps.data.LabelNames
import app.minemaps.data.NavigationMode
import app.minemaps.data.SettingsRepository
import app.minemaps.engine.geo.LatLng
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.routing.RoutePlan
import app.minemaps.engine.routing.RouteProgress
import app.minemaps.engine.routing.RouteTracker
import app.minemaps.engine.routing.RoutingClient
import app.minemaps.engine.routing.TurnDirection
import app.minemaps.location.LocationRepository
import app.minemaps.map.MapEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActiveBannerState(
    val instruction: String,
    val distanceText: String,
    val etaText: String,
    val direction: TurnDirection,
    val destinationName: String,
    val providerLabel: String,
    val mirroredFromGoogle: Boolean,
)

class NavigationController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val location: LocationRepository,
    private val routing: RoutingClient,
    private val engine: suspend () -> MapEngine,
    private val fallbackOrigin: () -> Pair<Double, Double>?,
) {
    val activePlan = MutableStateFlow<RoutePlan?>(null)
    val destinationName = MutableStateFlow("")
    val destination = MutableStateFlow<LatLng?>(null)
    val loading = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val progress = MutableStateFlow<RouteProgress?>(null)
    @Volatile
    private var latestLocation: android.location.Location? = null
    private var routeJob: Job? = null
    private var lastRerouteMs = 0L

    val bannerState: StateFlow<ActiveBannerState?> = combine(
        settings.settings,
        activePlan,
        progress,
        destinationName,
        GoogleMapsMirror.active,
    ) { s, plan, prog, destName, mirrored ->
        if (s.navigationMode == NavigationMode.OFF) return@combine null
        if (s.navigationMode == NavigationMode.GOOGLE_ASSISTED && s.mirrorGoogleMaps && mirrored != null) {
            return@combine ActiveBannerState(
                instruction = mirrored.instruction,
                distanceText = mirrored.distanceText,
                etaText = mirrored.etaText.ifBlank {
                    prog?.let { "${RouteTracker.formatDuration(it.remainingDurationSeconds)} \u00b7 ${RouteTracker.formatDistance(it.remainingDistanceMeters)}" }.orEmpty()
                },
                direction = mirrored.direction,
                destinationName = destName,
                providerLabel = "Google Maps Live",
                mirroredFromGoogle = true,
            )
        }
        if (plan != null && prog != null) {
            val step = prog.currentStep
            return@combine ActiveBannerState(
                instruction = step?.instruction ?: if (destName.isNotBlank()) "Toward $destName" else "Follow route",
                distanceText = if (prog.arrived) "" else RouteTracker.formatDistance(prog.distanceToNextStepMeters),
                etaText = if (prog.arrived) "Arrived" else "${RouteTracker.formatDuration(prog.remainingDurationSeconds)} \u00b7 ${RouteTracker.formatDistance(prog.remainingDistanceMeters)}",
                direction = step?.direction ?: TurnDirection.STRAIGHT,
                destinationName = destName,
                providerLabel = plan.providerLabel,
                mirroredFromGoogle = false,
            )
        }
        null
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            location.state.collect { locState ->
                val loc = locState.location ?: return@collect
                latestLocation = loc
                val plan = activePlan.value ?: return@collect
                val p = RouteTracker.evaluate(plan, loc.latitude, loc.longitude)
                progress.value = p
                if (p.arrived) {
                    messages.tryEmit("Arrived at ${destinationName.value.ifBlank { "destination" }}")
                    stop()
                } else if (p.offRouteMeters > RouteTracker.OFF_ROUTE_THRESHOLD_M) {
                    val now = System.currentTimeMillis()
                    val dest = destination.value
                    if (dest != null && now - lastRerouteMs > 20_000L && routeJob?.isActive != true) {
                        lastRerouteMs = now
                        startNavigation(dest.lat, dest.lng, destinationName.value, silentReroute = true)
                    }
                }
            }
        }
    }

    fun startNavigation(toLat: Double, toLon: Double, title: String, silentReroute: Boolean = false) {
        if (!toLat.isFinite() || !toLon.isFinite() || toLat !in -90.0..90.0 || toLon !in -180.0..180.0) return
        if (!silentReroute) GoogleMapsMirror.resetDismissed()
        routeJob?.cancel()
        routeJob = scope.launch {
            val s = settings.settings.first()
            if (s.navigationMode == NavigationMode.OFF) return@launch
            val loc = latestLocation ?: location.lastKnown()
            val origin = if (loc != null) loc.latitude to loc.longitude else fallbackOrigin()
            if (origin == null) {
                if (!silentReroute) messages.tryEmit("Current location unavailable for routing")
                return@launch
            }
            destination.value = LatLng(toLat, toLon)
            destinationName.value = title
            if (!silentReroute) loading.value = true
            try {
                val latinize: (String) -> String = if (s.labelNames == LabelNames.ENGLISH) {
                    val resolver = engine().nameResolver(NameResolver.Mode.ENGLISH)
                    ({ resolver.latinize(it) ?: it })
                } else {
                    { it }
                }
                val googleKey = if (s.navigationMode == NavigationMode.GOOGLE_ASSISTED) s.googleApiKey.takeIf { it.isNotBlank() } else null
                val plan = routing.route(origin.first, origin.second, toLat, toLon, googleKey, latinize)
                activePlan.value = plan
                progress.value = RouteTracker.evaluate(plan, origin.first, origin.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silentReroute) messages.tryEmit(e.message?.takeIf { it.isNotBlank() } ?: "Could not calculate route")
            } finally {
                loading.value = false
            }
        }
    }

    fun stop() {
        routeJob?.cancel()
        routeJob = null
        loading.value = false
        activePlan.value = null
        progress.value = null
        destination.value = null
        destinationName.value = ""
        GoogleMapsMirror.dismissAndExitGoogleMaps()
    }

    /** Launches Google Maps turn-by-turn navigation on the phone (and simultaneously calculates the in-app block route). */
    fun launchInGoogleMaps(context: Context, lat: Double, lon: Double, title: String) {
        startNavigation(lat, lon, title)
        val uri = "google.navigation:q=$lat,$lon".toUri()
        val gmapsIntent = Intent(Intent.ACTION_VIEW, uri).setPackage(GoogleMapsMirror.GMAPS_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackIntent = Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?q=$lat,$lon(${android.net.Uri.encode(title)})".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(gmapsIntent) }.onFailure {
            runCatching { context.startActivity(fallbackIntent) }.onFailure {
                messages.tryEmit("Google Maps is not installed on this device")
            }
        }
    }
}
