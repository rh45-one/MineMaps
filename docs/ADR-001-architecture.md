# ADR-001: Architecture for MineMaps (Minecraft-style real-world map, phone + Android Auto)

Status: Accepted (2026-10-15). Working name "MineMaps" (branding lives in `strings.xml` / `BuildConfig`, not code).

## Context

We need real-world geography (OSM) rendered as a coherent block world, on a phone and through
Android Auto's car map surface. The look must be a block grid (every cell is a block with a texture),
not a smooth vector map with a pixel filter.

## Decisions

### 1. Rendering: custom block rasterizer, not MapLibre
- **Chosen:** our own pure-Kotlin engine that rasterizes OSM vector tiles onto a global **block grid**,
  composes textured 256 px raster tiles into `IntArray`s, and blits them with nearest-neighbour
  filtering onto an `android.graphics.Canvas`.
- **Rejected – MapLibre Native styling:** `fill-pattern`/`line-pattern` keep anti-aliased vector edges,
  so buildings and roads never snap to blocks; per-block shading/shadows/variants are impossible.
  It would also force a GL `MapView` into the car surface via `VirtualDisplay`+`Presentation`,
  adding weight for a renderer whose look we cannot control.
- **Rejected – MapLibre + generated raster tiles via HTTP interception:** workable, but MapLibre would
  only be a pan/zoom shell; we would still write the whole rasterizer and carry a big GL dependency
  in both the phone and car paths.
- **Why custom works:** the same `MapRenderer.draw(canvas, viewport)` serves the phone `View` and the
  car `Surface` (`lockHardwareCanvas`). The engine core has no Android deps, so it is unit-tested on
  the JVM and can render real areas to PNG on the desktop for visual review.

### 2. Block grid and levels of detail
- Web Mercator. **One block = one pixel of a 256 px tile at zoom 16** → block coordinates are integers
  in `[0, 2^24)`; ≈2.4 m at the equator, ≈1.7 m at 45° latitude.
- Render tiles are always 256×256 px, keyed by `(z, x, y, night)`:
  - `z ≤ 16`: 1 px = 1 cell of `2^(16-z)` blocks, coloured by the class's average texture colour plus
    deterministic per-cell variation and shading (Xaero-style overview). Rasterized straight from the
    vector tile at `vz = min(z, 14)`.
  - `z = 17..20`: `2^(z-16)` px per block (2, 4, 8, 16), sampled from texture mips of that size, built
    from the cached z16 class grid. Display zoom goes up to 21.5 (tiles scaled ≤2.8×).
- While a tile is missing, its nearest cached ancestor is drawn scaled up, so there are no holes during pans.

### 3. Data: OpenMapTiles-schema vector tiles from OpenFreeMap
- Default source: `https://tiles.openfreemap.org/planet` (TileJSON; the tile URL is versioned and resolved at
  runtime). Free, commercial use allowed, no key; requires attribution
  "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" and a unique User-Agent. Bulk/offline downloads are capped
  (≤3,000 tiles / 50 MB per download, ≤4 parallel requests), so we only prefetch what is around the viewport.
- The TileJSON URL is configurable (`local.properties` → `BuildConfig`), so MapTiler or a self-hosted
  OpenMapTiles/Planetiler source can be swapped in without code changes.
- Own minimal MVT (protobuf) decoder, so we don't pull in JTS or protobuf-java.
- Disk cache of raw `.pbf` (LRU, 200 MB cap, 30-day TTL) + memory LRUs (decoded tiles, class grids, bitmaps).

### 4. Search & Routing: OpenStreetMap (Photon + OSRM) with optional Google Maps Assisted mode
- Default search: Photon (komoot public instance), debounced, camera-biased, merged with the on-device `PlaceIndex`.
- Three user-selectable navigation modes (`Settings → Navigation`):
  1. `OFF` — Map only (no route lines or navigation banners).
  2. `STANDALONE` (default) — Queries the public OSRM driving router (`router.project-osrm.org`), draws a pixel-art route line on the map, tracks progress along the polyline (`RouteTracker`), and shows the next turn/ETA banner on both the phone and Android Auto (`NavigationTemplate` `MessageInfo`).
  3. `GOOGLE_ASSISTED` — Combines two optional capabilities:
     - **Companion notification mirroring (`GoogleMapsNotificationService`):** Reads ongoing turn-by-turn notifications from `com.google.android.apps.maps` (when the user grants Notification Access) so Google Maps' live turn instructions and traffic ETA appear in MineMaps' banner alongside the block-map route; also accepts shared Google Maps links/places via `ACTION_SEND` and `geo:` intents and offers a one-tap *Google Maps* handoff button.
     - **Bring-your-own Google API key:** When a user supplies their own Google Maps Platform API key in Settings, search queries Google Places API v1 (`places:searchText`) and routing queries Google Routes API v2 (`computeRoutes` with `TRAFFIC_AWARE` and `TRAFFIC_ON_POLYLINE`), colouring slow/jammed route segments amber and red.

### 5. Android Auto: NAVIGATION category (no routing) + `NavigationTemplate` + `ACCESS_SURFACE`
- Android Auto gives the host's **map slot** (full screen, or the map pane of the split dashboard next to media apps)
  only to apps in `androidx.car.app.category.NAVIGATION`. A POI-category app is always second to Google Maps and can't take
  that slot, so MineMaps declares `NAVIGATION` with `androidx.car.app.NAVIGATION_TEMPLATES` (plus `MAP_TEMPLATES`, kept for
  the `MapWithContentTemplate` places list) and `ACCESS_SURFACE`, `minCarApiLevel=7`.
- There is still **no routing or turn-by-turn**: the app never calls `NavigationManager.navigationStarted`, so other apps'
  guidance is never suppressed. This is deliberate and means the app does not meet the Play quality rules for navigation
  apps (e.g. NF-1/4/7); it is sideload-only ("Unknown sources" in Android Auto developer settings), not Play-compliant.
- Root screen: a map-first `NavigationTemplate` (no navigation info or travel estimate; `MessageInfo` only for status such
  as missing permission, offline, location off). Action strip: Places (pushes a `MapWithContentTemplate` list of saved
  places), street-names toggle. Map action strip: PAN, zoom in/out, recenter.
- The `androidx.car.app.action.NAVIGATE` intent (`geo:` scheme) is handled in `Session.onCreateScreen/onNewIntent`:
  `geo:lat,lon` and `geo:0,0?q=lat,lon(label)` show the place with the search pin and a "doesn't give directions" toast;
  `geo:0,0?q=text` runs a Photon search and lists the results.
- `SurfaceCallback` handles
  `onSurfaceAvailable/Destroyed`, `onVisibleAreaChanged/onStableAreaChanged`, `onScroll/onScale/onFling/onClick`.
- Dark mode comes from `CarContext.isDarkMode()` and `Session.onCarConfigurationChanged`. The night palette is applied
  when tiles are composed.
- Car library 1.7.0 (latest stable as of 2026-10-07). The car UI shows fewer decorations: no item sprites below z18,
  larger labels.

### 6. App structure (Gradle modules)
- `:mapengine` – pure Kotlin/JVM: projection, MVT decoding, classification, rasterizer, tile compositor,
  labels/POIs, viewport math, tile fetching (OkHttp) and the disk cache. It also includes a desktop render tool.
- `:app` – Android: Compose phone UI, the Canvas `MapRenderer` glue, location, search, settings, saved places,
  and the car service.
- Location: platform `LocationManager` (fused provider when present, otherwise GPS+network), so we don't depend on GMS.
- Persistence: DataStore (settings and saved places as JSON).

### 7. Assets
- Original 16×16 pixel art, generated reproducibly by `tools/art/make_atlas.py` (palette + hand-designed
  patterns and icons). It outputs `atlas.png` + `atlas.json`, and the engine builds the mips. No Minecraft files are used.
- Label font: an OFL-licensed pixel font, with system fallback for scripts it doesn't cover.

## Highest-risk assumptions (to be proven)
1. Render throughput: composing a 256² tile plus rasterizing a z14 vector tile stays below ~30 ms on mid-range
   hardware → measure on the emulator with the JVM benchmark.
2. Android Auto testing: the DHU needs the Android Auto app on the device, and an emulator needs a Play image
   plus sign-in to get it → this may need the user's account or a physical phone.
3. Overzooming z14 OpenMapTiles data to a 2.4 m grid gives acceptable building footprints (the data is
   simplified at z14) → check visually on a real city.
4. Nearest-neighbour scaling at non-integer ratios looks acceptable.
