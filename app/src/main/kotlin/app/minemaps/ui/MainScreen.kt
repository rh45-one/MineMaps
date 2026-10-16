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
import kotlinx.coroutines.delay
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
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
import app.minemaps.engine.art.importer.MinecraftImporter
import app.minemaps.map.PackUi
import app.minemaps.update.UpdateUi
import app.minemaps.data.BackupFormatException
import app.minemaps.data.SavedPlacesBackup
import app.minemaps.BuildConfig
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import app.minemaps.ui.theme.windowHeightDp
import app.minemaps.ui.theme.rememberMcWindow
import app.minemaps.ui.theme.mcPanel
import app.minemaps.ui.theme.PixelGlyph
import app.minemaps.ui.theme.MineMapsTheme
import app.minemaps.ui.theme.McVariant
import app.minemaps.ui.theme.McTextField
import app.minemaps.ui.theme.McPanel
import app.minemaps.ui.theme.McListRow
import app.minemaps.ui.theme.McInsets
import app.minemaps.ui.theme.McIconButton
import app.minemaps.ui.theme.McGlyphButton
import app.minemaps.ui.theme.McDivider
import app.minemaps.ui.theme.McDialog
import app.minemaps.ui.theme.McButton
import app.minemaps.ui.theme.Mc
import app.minemaps.ui.theme.Glyph
import app.minemaps.engine.labels.NameResolver
import app.minemaps.data.SavedPlace
import app.minemaps.data.Settings
import app.minemaps.data.ThemeMode
import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TexturePack
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

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
    MineMapsTheme(dark, engine?.typeface) {
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
    val ctx = LocalContext.current
    val updateUi by container.updates.state.collectAsStateWithLifecycle()
    val offer by container.updates.offer.collectAsStateWithLifecycle()
    val offerVisible by container.updates.dialogVisible.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.updates.checkIfDue() }
    val packUi by container.textures.state.collectAsStateWithLifecycle()
    val packRevision by engine.packRevision.collectAsStateWithLifecycle()
    val vanillaAvail = remember(packRevision) { engine.isPackAvailable(TexturePack.VANILLA) }
    val storedSource = remember(packRevision) { engine.storedPack?.source?.lineSequence()?.firstOrNull()?.take(120) }
    var showInclude by rememberSaveable { mutableStateOf(false) }
    val packLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) container.textures.importFromFile(uri) }
    LaunchedEffect(Unit) { container.textures.messages.collect { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val text = container.savedPlaces.exportJson()
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                    "Saved places exported"
                }.getOrElse { "Export failed" }
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { input -> input.readBoundedBytes(SavedPlacesBackup.MAX_BYTES + 1) }
                    if (bytes.size > SavedPlacesBackup.MAX_BYTES) throw BackupFormatException("The file is too large to be a MineMaps backup")
                    val r = container.savedPlaces.importJson(String(bytes, Charsets.UTF_8))
                    "Imported ${r.imported}, skipped ${r.skipped}"
                } catch (e: BackupFormatException) {
                    e.message ?: "Import failed"
                } catch (e: Exception) {
                    "Could not read that file"
                }
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
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
                    settings, cacheSize, engine::atlas, engine.atlas(settings.texturePack),
                    vanillaAvailable = vanillaAvail,
                    onTexturePack = { vm.setTexturePack(it) },
                    onArrowColor = { vm.setArrowColor(it) },
                    onBack = { vm.screen.value = Screen.MAP },
                    onTheme = { vm.setTheme(it) },
                    onIcons = { vm.setShowIcons(it) },
                    onLabelNames = { vm.setLabelNames(it) },
                    onClearCache = { vm.clearCache() },
                    onLicences = { vm.screen.value = Screen.LICENCES },
                    extra = {
                        SavedPlacesGroup(
                            onExport = { exportLauncher.launch("minemaps-places.json") },
                            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        )
                        UpdatesGroup(settings, container.updates.enabled, updateUi, offer, vm::setUpdateFrequency, container.updates::checkNow, container.updates::install)
                        if (BuildConfig.DEBUG) DeveloperGroup(settings.debugMode) { vm.setDebugMode(it) }
                    },
                    onIncludeTextures = { showInclude = true },
                    texturesExtra = {
                        TexturesExtra(
                            available = vanillaAvail,
                            bundled = engine.bundledVanilla,
                            source = storedSource,
                            pending = settings.texturesPending,
                            onNow = { container.textures.importNow() },
                            onRemove = { container.textures.remove() },
                            onImportFile = { packLauncher.launch(arrayOf("application/java-archive", "application/zip", "application/octet-stream")) },
                        )
                    },
                )
            }
            Screen.LICENCES -> LicencesScreen(oflText, engine.resolve(settings.texturePack) == TexturePack.VANILLA, onBack = { vm.screen.value = Screen.SETTINGS })
        }
    }
    if (packUi !is PackUi.Idle) PackProgressDialog(packUi, container.textures::cancel, container.textures::dismissFailure)
    if (showInclude && packUi is PackUi.Idle) {
        IncludeTexturesDialog(
            sizeMb = MinecraftImporter.PINNED_SIZE / 1_000_000,
            metered = container.textures.isMetered(),
            onNow = { showInclude = false; container.textures.importNow() },
            onNextUpdate = { showInclude = false; container.textures.armPending() },
            onCancel = { showInclude = false },
        )
    }
    val o = offer
    if (o != null && (offerVisible || updateUi !is UpdateUi.Idle && updateUi !is UpdateUi.Checking)) {
        UpdateDialog(updateUi, o, container.updates::install, container.updates::later, container.updates::skip, container.updates::cancel, container.updates::dismissFailure)
    }
    scope.hashCode()
}

private fun nearPin(vp: Viewport, lat: Double, lon: Double, sx: Float, sy: Float, density: Float): Boolean {
    val pt = vp.worldToScreen(Mercator.lonToX(lon), Mercator.latToY(lat))
    val x = pt.x
    val y = pt.y - 16 * density
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
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val bearingState = vm.bearing.collectAsState()
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var creditShown by rememberSaveable { mutableStateOf(true) }
    var creditTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(creditShown, creditTick) {
        if (creditShown) {
            delay(5000)
            creditShown = false
        }
    }
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
    val packRev by engine.packRevision.collectAsStateWithLifecycle()
    LaunchedEffect(settings.texturePack, packRev) { renderer.pack = settings.texturePack }
    LaunchedEffect(settings.labelNames) { renderer.nameMode = if (settings.labelNames == LabelNames.LOCAL) NameResolver.Mode.LOCAL else NameResolver.Mode.ENGLISH }
    LaunchedEffect(settings.showStreetNames) { renderer.showStreetNames = settings.showStreetNames }

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
                v.onUserMoved = {
                    vm.onUserMoved()
                    creditShown = false
                }
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

        val win = rememberMcWindow()
        val sidePanel = win.wide
        val c = Mc.colors
        val density2 = androidx.compose.ui.platform.LocalDensity.current
        var bottomPanelPx by remember { mutableIntStateOf(0) }
        val rationaleVisible = !granted && !settings.rationaleDismissed && !settings.permissionAsked
        val hasBottomPanel = rationaleVisible || card != null
        LaunchedEffect(hasBottomPanel) {
            if (!hasBottomPanel) bottomPanelPx = 0
        }
        val bottomPad = if (!sidePanel && hasBottomPanel && bottomPanelPx > 0) with(density2) { bottomPanelPx.toDp() } + 24.dp else 16.dp
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

        val searchRow: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                McTextField(
                    value = text,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier.weight(1f).onFocusChanged { s: FocusState ->
                        if (s.isFocused) vm.onSearchFocus()
                        if (s.isFocused && text.isNotBlank()) vm.resultsVisible.value = true
                    },
                    placeholder = "Search places",
                    leading = { PixelGlyph(Glyph.SEARCH, c.textSecondary, size = 20.dp) },
                    trailing = {
                        if (text.isNotEmpty() || searchPin != null) {
                            Box(Modifier.size(48.dp).semantics { contentDescription = "Clear search" }.clickable { vm.clearSearch() }, contentAlignment = Alignment.Center) {
                                PixelGlyph(Glyph.CLOSE, c.text, size = 16.dp)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                val pinImage = remember(settings.texturePack) { atlasImage(engine.atlas(settings.texturePack), "pin") }
                McIconButton({ vm.screen.value = Screen.SAVED }, Modifier.semantics { contentDescription = "Saved places" }, size = 48.dp) { PixelIcon(pinImage, 28.dp) }
                McGlyphButton(Glyph.SETTINGS, { vm.screen.value = Screen.SETTINGS }, Modifier.semantics { contentDescription = "Settings" }, size = 48.dp)
            }
        }
        val banners: @Composable () -> Unit = {
            if (net == app.minemaps.engine.data.NetState.OFFLINE) McBanner("Offline \u2013 showing cached map", null, {})
            if (!granted && !rationaleVisible) {
                McBanner("Location permission needed", if (permanentlyDenied) "Settings" else "Allow", { if (permanentlyDenied) openAppSettings() else requestPermission() })
            } else if (granted && loc.status == LocationStatus.DISABLED) {
                McBanner("Location is off", "Turn on", { openLocationSettings() })
            }
        }
        val resultsPanel: @Composable () -> Unit = {
            SearchResultsPanel(searchState, onSelect = { r ->
                focus.clearFocus()
                vm.selectResult(r)
                val v = controller.view
                val ex = r.extent
                if (v != null) {
                    if (ex != null && (ex[2] - ex[0] > 1e-6 || ex[1] - ex[3] > 1e-6)) v.fitBounds(ex[3], ex[0], ex[1], ex[2]) else v.animateTo(r.lat, r.lon, r.targetZoom())
                }
            })
        }
        val bottomPanel: @Composable () -> Unit = {
            if (rationaleVisible) {
                RationaleCard(onAllow = { requestPermission() }, onDismiss = { vm.dismissRationale() })
            } else {
                card?.let { cc ->
                    InfoCardView(
                        cc,
                        onClose = { vm.dismissCard() },
                        onSaveInspect = { i -> vm.requestSave(i.lat, i.lon, i.title) },
                        onSaveResult = { r -> vm.requestSave(r.lat, r.lon, r.name) },
                        onRename = { vm.renameTarget.value = it },
                        onDelete = { confirmDelete = it.id },
                        onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                    )
                }
            }
        }

        if (sidePanel) {
            Column(
                Modifier.align(Alignment.TopStart).width(400.dp).fillMaxHeight()
                    .windowInsetsPadding(McInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.Bottom))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                searchRow()
                banners()
                Box(Modifier.weight(1f, fill = false).padding(bottom = 56.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (resultsVisible) resultsPanel() else if (hasBottomPanel) bottomPanel()
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(McInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                searchRow()
                if (resultsVisible) resultsPanel()
                banners()
            }
            Box(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(McInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(12.dp)
                    .onSizeChanged { bottomPanelPx = if (hasBottomPanel) it.height else 0 },
            ) { if (hasBottomPanel) bottomPanel() }
        }

        val compassIcon = remember(settings.texturePack) { atlasImage(engine.atlas(settings.texturePack), "compass") }
        val navControls: @Composable () -> Unit = {
            CompassButton(bearingState, compassIcon) { controller.view?.animateBearing(0.0, 300L) }
            StreetNamesButton(settings.showStreetNames) { vm.setShowStreetNames(!settings.showStreetNames) }
        }
        val locationButton: @Composable () -> Unit = {
            LocationButton(
                when {
                    !granted -> LocationIconState.NO_PERMISSION
                    loc.status == LocationStatus.DISABLED -> LocationIconState.GPS_OFF
                    following -> LocationIconState.FOLLOWING
                    else -> LocationIconState.NOT_FOLLOWING
                },
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
            )
        }
        val zoomControls: @Composable () -> Unit = {
            McGlyphButton(Glyph.PLUS, { controller.zoomIn() }, Modifier.semantics { contentDescription = "Zoom in" })
            McGlyphButton(Glyph.MINUS, { controller.zoomOut() }, Modifier.semantics { contentDescription = "Zoom out" })
        }
        Row(
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(McInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)).padding(end = 12.dp, bottom = bottomPad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (win.compactHeight) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) { navControls(); locationButton() }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) { zoomControls() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    navControls()
                    zoomControls()
                    locationButton()
                }
            }
        }

        val creditOrigin = TransformOrigin(0f, 1f)
        Box(
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(McInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start)).padding(start = 4.dp, bottom = bottomPad - 14.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            AnimatedVisibility(
                creditShown,
                enter = fadeIn() + scaleIn(initialScale = 0.85f, transformOrigin = creditOrigin),
                exit = fadeOut() + scaleOut(targetScale = 0.85f, transformOrigin = creditOrigin),
            ) {
                Column(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp).widthIn(max = 280.dp).mcPanel(c).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        buildAnnotatedString {
                            append("\u00a9 ")
                            withLink(LinkAnnotation.Url(OSM_COPYRIGHT_URL, TextLinkStyles(SpanStyle(color = c.greenHighlight, textDecoration = TextDecoration.Underline)))) { append("OpenStreetMap") }
                            append(" contributors \u00b7 \u00a9 OpenMapTiles \u00b7 OpenFreeMap")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = c.text,
                    )
                    Text("Licences", Modifier.clickable { vm.screen.value = Screen.LICENCES }.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = c.greenHighlight)
                }
            }
            AnimatedVisibility(!creditShown, enter = fadeIn(), exit = fadeOut()) { CreditsButton { creditShown = true; creditTick++ } }
        }
    }

    saveDialog?.let { d ->
        IconNameDialog("Save place", d.suggestedName, "pin", engine.atlas(settings.texturePack), "Save", onConfirm = { n, i -> vm.confirmSave(n, i) }, onDismiss = { vm.cancelSave() })
    }
    renameTarget?.let { p ->
        IconNameDialog("Edit place", p.name, p.icon, engine.atlas(settings.texturePack), "Save", onConfirm = { n, i -> vm.edit(p, n, i) }, onDismiss = { vm.renameTarget.value = null })
    }
    saved.firstOrNull { it.id == confirmDelete }?.let { p ->
        McDialog(
            "Delete place?",
            onDismiss = { confirmDelete = null },
            buttons = {
                McButton({ confirmDelete = null }) { Text("Cancel") }
                McButton({ confirmDelete = null; vm.delete(p) }, variant = McVariant.DANGER) { Text("Delete") }
            },
        ) { Text("\"${p.name}\" will be removed from your saved places.", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SearchResultsPanel(state: SearchState, onSelect: (SearchResult) -> Unit) {
    val c = Mc.colors
    val maxH = (windowHeightDp() * 0.55f).dp
    McPanel(Modifier.fillMaxWidth().heightIn(max = maxH), contentPadding = 4.dp) {
        when (state) {
            SearchState.Idle -> Text("Type at least 2 characters", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            SearchState.Loading -> Text("Searching\u2026", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            is SearchState.Error -> Text("Search unavailable: ${state.message}", Modifier.padding(12.dp), color = c.danger, style = MaterialTheme.typography.bodyMedium)
            is SearchState.Results -> if (state.items.isEmpty()) {
                Text(if (state.updating) "Searching\u2026" else "No results", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn {
                    items(state.items) { r ->
                        McListRow(
                            onClick = { onSelect(r) },
                            title = r.name,
                            subtitle = if (r.local) r.address.ifBlank { r.typeLabel.removeSuffix(" \u00b7 nearby") } else r.subtitle,
                            trailing = { if (r.local) McTag("nearby") },
                        )
                        McDivider()
                    }
                    if (state.updating) item { Text("Updating\u2026", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                }
            }
        }
    }
}

@Composable
private fun RationaleCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    McPanel(Modifier.fillMaxWidth()) {
        Text("Show your position on the map?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "MineMaps uses your location only while the app is open, to centre the map on you. You can use the map without it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Mc.colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            McButton(onDismiss) { Text("Not now") }
            McButton(onAllow, variant = McVariant.PRIMARY) { Text("Allow location") }
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
    onCopy: (String) -> Unit,
) {
    val c = Mc.colors
    McPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                when (card) {
                    is InfoCard.Inspect -> {
                        Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        CoordsRow(coords(card.lat, card.lon), onCopy)
                    }
                    is InfoCard.Saved -> {
                        Text(card.place.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Saved place", style = MaterialTheme.typography.bodyMedium, color = c.gold)
                        CoordsRow(coords(card.place.lat, card.place.lon), onCopy)
                    }
                    is InfoCard.Result -> {
                        Text(card.result.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (card.result.subtitle.isNotBlank()) Text(card.result.subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
            }
            McGlyphButton(Glyph.CLOSE, onClose, Modifier.semantics { contentDescription = "Close" }, size = 48.dp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (card) {
                is InfoCard.Inspect -> McButton({ onSaveInspect(card) }, variant = McVariant.PRIMARY) { Text("Save place") }
                is InfoCard.Result -> McButton({ onSaveResult(card.result) }, variant = McVariant.PRIMARY) { Text("Save place") }
                is InfoCard.Saved -> {
                    McButton({ onRename(card.place) }) { PixelGlyph(Glyph.EDIT, LocalContentColor.current, size = 18.dp); Text("Edit") }
                    McButton({ onDelete(card.place) }, variant = McVariant.DANGER) { PixelGlyph(Glyph.DELETE, LocalContentColor.current, size = 18.dp); Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun CoordsRow(text: String, onCopy: (String) -> Unit) {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Mc.colors.textSecondary)
        Box(Modifier.size(40.dp).semantics { contentDescription = "Copy coordinates" }.clickable { onCopy(text) }, contentAlignment = Alignment.Center) {
            PixelGlyph(Glyph.COPY, Mc.colors.textSecondary, size = 16.dp)
        }
    }
}

private fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

private fun java.io.InputStream.readBoundedBytes(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
