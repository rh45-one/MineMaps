package app.minemaps.ui

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.minemaps.AppContainer
import app.minemaps.data.Camera
import app.minemaps.data.SavedPlace
import app.minemaps.data.Settings
import app.minemaps.engine.geo.LatLng
import app.minemaps.engine.inspect.InspectResult
import app.minemaps.location.LocationState
import app.minemaps.location.LocationStatus
import app.minemaps.search.PhotonClient
import app.minemaps.search.ResponseCache
import app.minemaps.engine.search.PlaceKind
import app.minemaps.engine.geo.Mercator
import app.minemaps.search.SearchResult
import app.minemaps.search.SearchState
import app.minemaps.search.searchStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

enum class Screen { MAP, SAVED, SETTINGS, LICENCES }

sealed interface InfoCard {
    data class Inspect(val title: String, val subtitle: String, val lat: Double, val lon: Double) : InfoCard
    data class Saved(val place: SavedPlace) : InfoCard
    data class Result(val result: SearchResult) : InfoCard
}

data class SaveDialog(val lat: Double, val lon: Double, val suggestedName: String)

@OptIn(FlowPreview::class)
class MainViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val savedPlaces: StateFlow<List<SavedPlace>> = container.savedPlaces.places
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val locationState: StateFlow<LocationState> = container.location.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocationState(null, LocationStatus.SEARCHING))

    val initialCamera = MutableStateFlow<Camera?>(null)
    val following = MutableStateFlow(false)
    val pendingFlyZoom = MutableStateFlow<Double?>(null)
    val screen = MutableStateFlow(Screen.MAP)
    val card = MutableStateFlow<InfoCard?>(null)
    val saveDialog = MutableStateFlow<SaveDialog?>(null)
    val renameTarget = MutableStateFlow<SavedPlace?>(null)
    val searchPin = MutableStateFlow<LatLng?>(null)
    val text = MutableStateFlow("")
    val resultsVisible = MutableStateFlow(false)
    val cacheSize = MutableStateFlow(0L)
    val messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val typed = MutableStateFlow("")
    private val cameraEvents = MutableSharedFlow<Camera>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    var camera: Camera? = null
        private set

    @Volatile
    private var englishNames = true
    private val photonCache = ResponseCache<List<SearchResult>>()

    private fun lang(): String? = if (englishNames) "en" else Locale.getDefault().language

    private fun cacheKey(q: String) = ResponseCache.key(q, camera?.lat, camera?.lon, lang())

    private fun localHits(q: String): List<SearchResult> {
        val c = camera
        val wx = c?.let { Mercator.lonToX(it.lon) } ?: 0.5
        val wy = c?.let { Mercator.latToY(it.lat) } ?: 0.5
        val hits = container.placeIndex.query(q, wx, wy, localNames = !englishNames)
        runCatching { android.util.Log.d(PhotonClient.TAG, "local results n=${hits.size} at +${android.os.SystemClock.elapsedRealtime() - sessionStartAt}ms since first key") }
        return hits.map { h ->
            val ll = LatLng(Mercator.yToLat(h.wy), Mercator.xToLon(h.wx))
            val type = when (h.kind) {
                PlaceKind.COUNTRY -> "country"
                PlaceKind.STATE -> "state"
                PlaceKind.CITY, PlaceKind.TOWN, PlaceKind.ISLAND -> "city"
                PlaceKind.VILLAGE, PlaceKind.HAMLET, PlaceKind.SUBURB, PlaceKind.QUARTER -> "district"
                PlaceKind.STREET -> "street"
                else -> "house"
            }
            SearchResult(h.name, "", "${h.kindLabel} \u00b7 nearby", ll.lat, ll.lng, type, null, null, local = true)
        }
    }

    val searchState: StateFlow<SearchState> = searchStates(
        typed,
        local = { q -> localHits(q) },
        cached = { q -> photonCache.get(cacheKey(q)) },
    ) { q ->
        val c = camera
        runCatching { android.util.Log.d(PhotonClient.TAG, "typed->request ${android.os.SystemClock.elapsedRealtime() - typedAt}ms") }
        val r = if (englishNames) {
            val names = container.engine.await().nameResolver(app.minemaps.engine.labels.NameResolver.Mode.ENGLISH)
            container.photon.search(q, c?.lat, c?.lon, "en") { names.latinize(it) ?: it }
        } else {
            container.photon.search(q, c?.lat, c?.lon, Locale.getDefault().language)
        }
        photonCache.put(cacheKey(q), r)
        runCatching { android.util.Log.d(PhotonClient.TAG, "network results at +${android.os.SystemClock.elapsedRealtime() - sessionStartAt}ms since first key") }
        r
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchState.Idle)

    fun onSearchFocus() {
        viewModelScope.launch { container.photon.prewarm() }
    }

    init {
        viewModelScope.launch { container.settings.settings.collect { englishNames = it.labelNames == app.minemaps.data.LabelNames.ENGLISH } }
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = container.mapState.load()
            if (persisted != null) {
                camera = persisted
                initialCamera.value = persisted
                if (container.location.hasPermission()) {
                    following.value = true
                    if (persisted.zoom < 14.0) pendingFlyZoom.value = 16.0
                }
            } else {
                val last = container.location.lastKnown()
                val cam = if (last != null) Camera(last.latitude, last.longitude, 16.0) else Camera.WORLD
                camera = cam
                initialCamera.value = cam
                if (last != null) following.value = true
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            cameraEvents.debounce(800).collect { container.mapState.persist(it) }
        }
    }

    val bearing = MutableStateFlow(0.0)

    fun onCameraChanged(c: Camera) {
        camera = c
        if (bearing.value != c.bearing) bearing.value = c.bearing
        container.mapState.update(c)
        cameraEvents.tryEmit(c)
    }

    fun onUserMoved() {
        following.value = false
    }

    fun startFollowing(zoom: Double?) {
        following.value = true
        pendingFlyZoom.value = zoom
    }

    @Volatile
    private var typedAt = 0L

    @Volatile
    private var sessionStartAt = 0L

    fun onQueryChange(s: String) {
        typedAt = android.os.SystemClock.elapsedRealtime()
        if (text.value.isEmpty()) sessionStartAt = typedAt
        text.value = s
        typed.value = s
        resultsVisible.value = s.isNotBlank()
    }

    fun clearSearch() {
        text.value = ""
        typed.value = ""
        resultsVisible.value = false
        searchPin.value = null
        if (card.value is InfoCard.Result) card.value = null
    }

    fun selectResult(r: SearchResult) {
        text.value = r.name
        typed.value = ""
        resultsVisible.value = false
        searchPin.value = LatLng(r.lat, r.lon)
        card.value = InfoCard.Result(r)
        following.value = false
    }

    fun showInspect(result: InspectResult?, lat: Double, lon: Double) {
        card.value = result?.let { InfoCard.Inspect(it.title, it.subtitle, lat, lon) }
    }

    fun showSaved(place: SavedPlace) {
        card.value = InfoCard.Saved(place)
    }

    fun dismissCard() {
        card.value = null
    }

    fun requestSave(lat: Double, lon: Double, suggested: String) {
        saveDialog.value = SaveDialog(lat, lon, suggested)
    }

    fun confirmSave(name: String, icon: String) {
        val d = saveDialog.value ?: return
        saveDialog.value = null
        viewModelScope.launch {
            try {
                val place = container.savedPlaces.add(name, d.lat, d.lon, icon)
                card.value = InfoCard.Saved(place)
            } catch (e: app.minemaps.data.SavedPlacesFullException) {
                messages.tryEmit("Saved places limit reached (${app.minemaps.data.SavedPlacesRepository.MAX_PLACES}). Delete one first.")
            }
        }
    }

    fun cancelSave() {
        saveDialog.value = null
    }

    fun edit(place: SavedPlace, name: String, icon: String) {
        renameTarget.value = null
        viewModelScope.launch {
            container.savedPlaces.update(place.id, name, icon)
            if ((card.value as? InfoCard.Saved)?.place?.id == place.id) {
                card.value = InfoCard.Saved(place.copy(name = name.ifBlank { place.name }, icon = icon))
            }
        }
    }

    fun delete(place: SavedPlace) {
        viewModelScope.launch {
            container.savedPlaces.delete(place.id)
            if ((card.value as? InfoCard.Saved)?.place?.id == place.id) card.value = null
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) { cacheSize.value = container.engine.await().cache.sizeBytes() }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            container.engine.await().clearCache()
            cacheSize.value = 0L
        }
    }

    fun setTheme(v: app.minemaps.data.ThemeMode) = viewModelScope.launch { container.settings.setTheme(v) }

    fun setTexturePack(v: app.minemaps.engine.art.TexturePack) = viewModelScope.launch { container.settings.setTexturePack(v) }

    fun setArrowColor(v: Int) = viewModelScope.launch { container.settings.setArrowColor(v) }

    fun setShowIcons(v: Boolean) = viewModelScope.launch { container.settings.setShowIcons(v) }

    fun setShowStreetNames(v: Boolean) = viewModelScope.launch { container.settings.setShowStreetNames(v) }

    fun setLabelNames(v: app.minemaps.data.LabelNames) = viewModelScope.launch { container.settings.setLabelNames(v) }

    fun markPermissionAsked() = viewModelScope.launch { container.settings.setPermissionAsked() }

    fun dismissRationale() = viewModelScope.launch { container.settings.setRationaleDismissed() }

    fun lastLocation(): Location? = locationState.value.location

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}
