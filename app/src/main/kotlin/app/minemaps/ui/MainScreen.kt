package app.minemaps.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minemaps.AppContainer
import app.minemaps.data.Camera
import app.minemaps.data.LabelNames
import app.minemaps.data.SavedPlace
import app.minemaps.data.Settings
import app.minemaps.data.ThemeMode
import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.geo.LatLng
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.layout.RenderProfile
import app.minemaps.location.LocationStatus
import app.minemaps.map.MapEngine
import app.minemaps.map.Bookmark
import app.minemaps.map.MapOverlays
import app.minemaps.map.MapRenderer
import app.minemaps.map.MapView
import app.minemaps.map.PlayerMarker
import app.minemaps.search.SearchResult
import app.minemaps.search.SearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun MineMapsRoot(vm: MainViewModel, container: AppContainer, applyBars: (Boolean) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val initial by vm.initialCamera.collectAsStateWithLifecycle()
    val engine by produceState<MapEngine?>(null) { value = container.engine.await() }
    val dark = when (settings?.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    LaunchedEffect(dark) { applyBars(dark) }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val s = settings
        val cam = initial
        val eng = engine
        if (s != null && cam != null && eng != null) {
            MainContent(vm, container, eng, cam, s, dark)
        } else {
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF14171C) else Color(0xFFC9D6B6)))
        }
    }
}

@Composable
private fun MainContent(vm: MainViewModel, container: AppContainer, engine: MapEngine, initial: Camera, settings: Settings, dark: Boolean) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val saved by vm.savedPlaces.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val oflText by produceState("") {
        value = withContext(Dispatchers.IO) { EngineResources.open("fonts/OFL.txt").use { String(it.readBytes()) } }
    }
    BackHandler(screen != Screen.MAP) {
        vm.screen.value = if (screen == Screen.LICENCES) Screen.SETTINGS else Screen.MAP
    }
    val scope = rememberCoroutineScope()
    val mapController = remember { MapController() }
    Box(Modifier.fillMaxSize()) {
        MapScreen(vm, container, engine, initial, settings, dark, mapController)
        when (screen) {
            Screen.MAP -> {}
            Screen.SAVED -> SavedPlacesScreen(
                saved,
                rememberBookmarkImages(engine.atlas(settings.texturePack)),
                onEdit = { vm.renameTarget.value = it },
                onBack = { vm.screen.value = Screen.MAP },
                onOpen = { p ->
                    vm.screen.value = Screen.MAP
                    vm.following.value = false
                    vm.showSaved(p)
                    mapController.view?.animateTo(p.lat, p.lon, 17.0)
                },
                onDelete = { vm.delete(it) },
            )
            Screen.SETTINGS -> {
                LaunchedEffect(Unit) { vm.refreshCacheSize() }
                SettingsScreen(
                    settings, cacheSize, engine.atlas(settings.texturePack),
                    vanillaAvailable = engine.isPackAvailable(TexturePack.VANILLA),
                    onTexturePack = { vm.setTexturePack(it) },
                    onArrowColor = { vm.setArrowColor(it) },
                    onBack = { vm.screen.value = Screen.MAP },
                    onTheme = { vm.setTheme(it) },
                    onIcons = { vm.setShowIcons(it) },
                    onLabelNames = { vm.setLabelNames(it) },
                    onClearCache = { vm.clearCache() },
                    onLicences = { vm.screen.value = Screen.LICENCES },
                )
            }
            Screen.LICENCES -> LicencesScreen(oflText, engine.resolve(settings.texturePack) == TexturePack.VANILLA, onBack = { vm.screen.value = Screen.SETTINGS })
        }
    }
    scope.hashCode()
}

private fun nearPin(vp: Viewport, lat: Double, lon: Double, sx: Float, sy: Float, density: Float): Boolean {
    val x = vp.worldToScreenX(Mercator.lonToX(lon))
    val y = vp.worldToScreenY(Mercator.latToY(lat)) - 16 * density
    return Math.hypot(x - sx, y - sy) <= 28 * density
}

@Composable
private fun MapScreen(
    vm: MainViewModel,
    container: AppContainer,
    engine: MapEngine,
    initial: Camera,
    settings: Settings,
    dark: Boolean,
    controller: MapController,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val renderer = remember { MapRenderer(engine, RenderProfile.PHONE, density, null, settings.texturePack) { controller.view?.requestRedraw() } }
    DisposableEffect(renderer) {
        container.register(renderer)
        onDispose { renderer.close() }
    }
    controller.renderer = renderer

    val saved by vm.savedPlaces.collectAsStateWithLifecycle()
    val loc by vm.locationState.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val pending by vm.pendingFlyZoom.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    val searchState by vm.searchState.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val resultsVisible by vm.resultsVisible.collectAsStateWithLifecycle()
    val searchPin by vm.searchPin.collectAsStateWithLifecycle()
    val saveDialog by vm.saveDialog.collectAsStateWithLifecycle()
    val renameTarget by vm.renameTarget.collectAsStateWithLifecycle()
    val net by renderer.networkState.collectAsStateWithLifecycle()

    val activity = context.findActivity()
    val askedBefore by rememberUpdatedState(settings.permissionAsked)
    var granted by remember { mutableStateOf(container.location.hasPermission()) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    fun recomputePermission(asked: Boolean = askedBefore) {
        granted = container.location.hasPermission()
        permanentlyDenied = !granted && asked && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        recomputePermission(asked = true)
        vm.markPermissionAsked()
        container.location.refresh()
        if (granted) vm.startFollowing(16.0)
    }
    LaunchedEffect(settings.permissionAsked) { recomputePermission() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val was = granted
                recomputePermission()
                if (granted != was) container.location.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    fun requestPermission() = launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    fun openAppSettings() = context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    fun openLocationSettings() = context.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    LaunchedEffect(settings.showIcons) { renderer.showIcons = settings.showIcons }
    LaunchedEffect(settings.arrowColor) { renderer.arrowColor = settings.arrowColor }
    LaunchedEffect(settings.texturePack) { renderer.pack = settings.texturePack }
    LaunchedEffect(settings.labelNames) { renderer.labelNameField = if (settings.labelNames == LabelNames.LATIN) "name:latin" else "name" }

    val player = loc.location
    LaunchedEffect(player, following, pending) {
        val l = player ?: return@LaunchedEffect
        val view = controller.view ?: return@LaunchedEffect
        if (!following) return@LaunchedEffect
        val z = pending
        if (z != null) {
            view.animateTo(l.latitude, l.longitude, z)
            vm.pendingFlyZoom.value = null
        } else {
            view.follow(l.latitude, l.longitude)
        }
    }

    val overlays = remember(player, saved, searchPin) {
        MapOverlays(
            player = player?.let { PlayerMarker(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else 0f, it.bearing, if (it.hasSpeed()) it.speed else 0f) },
            bookmarks = saved.map { Bookmark(it.lat, it.lon, it.icon, it.name) },
            searchPin = searchPin,
        )
    }

    fun inspectAt(vp: Viewport, sx: Float, sy: Float, onResult: (app.minemaps.engine.inspect.InspectResult?, Double, Double) -> Unit) {
        val w = vp.screenToWorld(sx.toDouble(), sy.toDouble())
        val lat = Mercator.yToLat(w.y.coerceIn(0.0, 1.0))
        val lon = Mercator.xToLon(w.x - Math.floor(w.x))
        scope.launch {
            val r = try {
                renderer.inspect(w.x - Math.floor(w.x), w.y, vp.zoom)
            } catch (e: Exception) {
                android.util.Log.w("MineMaps", "inspect failed", e)
                null
            }
            onResult(r, lat, lon)
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }

    val bottomPad = if (card != null) 150.dp else 16.dp
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx, renderer).also {
                    it.setCamera(vm.camera ?: initial)
                    controller.view = it
                }
            },
            update = { v ->
                v.night = dark
                v.overlays = overlays
                v.onCameraChanged = vm::onCameraChanged
                v.onUserMoved = vm::onUserMoved
                v.onTap = { vp, sx, sy ->
                    focus.clearFocus()
                    vm.resultsVisible.value = false
                    val hit = saved.firstOrNull { nearPin(vp, it.lat, it.lon, sx, sy, density) }
                    if (hit != null) {
                        vm.showSaved(hit)
                    } else if (searchPin != null && nearPin(vp, searchPin!!.lat, searchPin!!.lng, sx, sy, density) && card is InfoCard.Result) {
                        // keep the search result card
                    } else {
                        inspectAt(vp, sx, sy) { r, lat, lon -> vm.showInspect(r, lat, lon) }
                    }
                }
                v.onLongPress = { vp, sx, sy ->
                    inspectAt(vp, sx, sy) { r, lat, lon -> vm.requestSave(lat, lon, r?.title ?: "Saved place") }
                }
            },
        )

        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Card(Modifier.weight(1f), shape = MaterialTheme.shapes.extraLarge, elevation = CardDefaults.cardElevation(4.dp)) {
                    TextField(
                        value = text,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { s: FocusState ->
                            if (s.isFocused && text.isNotBlank()) vm.resultsVisible.value = true
                        },
                        singleLine = true,
                        placeholder = { Text("Search places") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (text.isNotEmpty() || searchPin != null) {
                                IconButton(onClick = { vm.clearSearch() }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
                SmallFloatingActionButton(onClick = { vm.screen.value = Screen.SAVED }, shape = CircleShape) {
                    Icon(Icons.Default.Star, contentDescription = "Saved places")
                }
                SmallFloatingActionButton(onClick = { vm.screen.value = Screen.SETTINGS }, shape = CircleShape) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            if (resultsVisible) SearchResultsPanel(searchState, onSelect = { r ->
                focus.clearFocus()
                vm.selectResult(r)
                val v = controller.view
                val ex = r.extent
                if (v != null) {
                    if (ex != null && (ex[2] - ex[0] > 1e-6 || ex[1] - ex[3] > 1e-6)) v.fitBounds(ex[3], ex[0], ex[1], ex[2]) else v.animateTo(r.lat, r.lon, r.targetZoom())
                }
            })
            if (net == app.minemaps.engine.data.NetState.OFFLINE) Banner("Offline \u2013 showing cached map", null, {})
            val rationale = !granted && !settings.rationaleDismissed && !settings.permissionAsked
            if (!granted && !rationale) {
                Banner("Location permission needed", if (permanentlyDenied) "Settings" else "Allow", { if (permanentlyDenied) openAppSettings() else requestPermission() })
            } else if (granted && loc.status == LocationStatus.DISABLED) {
                Banner("Location is off", "Turn on", { openLocationSettings() })
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(end = 12.dp, bottom = bottomPad),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(onClick = { controller.zoomIn() }, shape = CircleShape) { Icon(Icons.Default.Add, contentDescription = "Zoom in") }
            SmallFloatingActionButton(onClick = { controller.zoomOut() }, shape = CircleShape) { Text("\u2212", style = MaterialTheme.typography.titleLarge) }
            SmallFloatingActionButton(
                modifier = Modifier.semantics { contentDescription = "My location" },
                onClick = {
                    when {
                        !granted -> if (permanentlyDenied) openAppSettings() else requestPermission()
                        loc.status == LocationStatus.DISABLED -> openLocationSettings()
                        else -> {
                            val z = controller.view?.zoom ?: 16.0
                            vm.startFollowing(if (z < 15.0) 16.0 else z)
                        }
                    }
                },
                shape = CircleShape,
            ) {
                LocationIcon(
                    when {
                        !granted -> LocationIconState.NO_PERMISSION
                        loc.status == LocationStatus.DISABLED -> LocationIconState.GPS_OFF
                        following -> LocationIconState.FOLLOWING
                        else -> LocationIconState.NOT_FOLLOWING
                    },
                )
            }
        }

        Surface(
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, bottom = bottomPad).clickable { vm.screen.value = Screen.LICENCES },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shadowElevation = 2.dp,
        ) {
            Text("\u00a9 OpenMapTiles \u00a9 OpenStreetMap", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
        }

        val rationaleVisible = !granted && !settings.rationaleDismissed && !settings.permissionAsked
        Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp)) {
            if (rationaleVisible) {
                RationaleCard(onAllow = { requestPermission() }, onDismiss = { vm.dismissRationale() })
            } else {
                card?.let { c ->
                    InfoCardView(
                        c,
                        onClose = { vm.dismissCard() },
                        onSaveInspect = { i -> vm.requestSave(i.lat, i.lon, i.title) },
                        onSaveResult = { r -> vm.requestSave(r.lat, r.lon, r.name) },
                        onRename = { vm.renameTarget.value = it },
                        onDelete = { vm.delete(it) },
                    )
                }
            }
        }
    }

    saveDialog?.let { d ->
        IconNameDialog("Save place", d.suggestedName, "pin", engine.atlas(settings.texturePack), "Save", onConfirm = { n, i -> vm.confirmSave(n, i) }, onDismiss = { vm.cancelSave() })
    }
    renameTarget?.let { p ->
        IconNameDialog("Edit place", p.name, p.icon, engine.atlas(settings.texturePack), "Save", onConfirm = { n, i -> vm.edit(p, n, i) }, onDismiss = { vm.renameTarget.value = null })
    }
}

@Composable
private fun SearchResultsPanel(state: SearchState, onSelect: (SearchResult) -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(max = 420.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        when (state) {
            SearchState.Idle -> Text("Type at least 3 characters", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            SearchState.Loading -> Text("Searching\u2026", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            is SearchState.Error -> Text("Search unavailable: ${state.message}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            is SearchState.Results -> if (state.items.isEmpty()) {
                Text("No results", Modifier.padding(16.dp))
            } else {
                LazyColumn {
                    items(state.items) { r ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(r) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.subtitle.isNotBlank()) {
                                Text(r.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RationaleCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show your position on the map?", style = MaterialTheme.typography.titleMedium)
            Text(
                "MineMaps uses your location only while the app is open, to centre the map on you. You can use the map without it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = onAllow) { Text("Allow location") }
            }
        }
    }
}

@Composable
private fun InfoCardView(
    card: InfoCard,
    onClose: () -> Unit,
    onSaveInspect: (InfoCard.Inspect) -> Unit,
    onSaveResult: (SearchResult) -> Unit,
    onRename: (SavedPlace) -> Unit,
    onDelete: (SavedPlace) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when (card) {
                    is InfoCard.Inspect -> {
                        Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(coords(card.lat, card.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { onSaveInspect(card) }, Modifier.padding(top = 6.dp)) { Text("Save place") }
                    }
                    is InfoCard.Saved -> {
                        Text(card.place.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Saved place", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(coords(card.place.lat, card.place.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { onRename(card.place) }) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Text("Edit", Modifier.padding(start = 6.dp))
                            }
                            TextButton(onClick = { onDelete(card.place) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Text("Delete", Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    is InfoCard.Result -> {
                        Text(card.result.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (card.result.subtitle.isNotBlank()) {
                            Text(card.result.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { onSaveResult(card.result) }, Modifier.padding(top = 6.dp)) { Text("Save place") }
                    }
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
    }
}

private fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
