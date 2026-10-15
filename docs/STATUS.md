# Status

## Stages

| stage | content | state |
|---|---|---|
| 1 | Architecture decision, engine spec, art generator | done |
| 2 | Gradle project, `:mapengine`, desktop render tool, minimal app | done |
| 3 | Phone app (map view, location, search, saved places, settings, inspect) | done |
| 4 | Android Auto (POI category, `MapWithContentTemplate`), tested on the DHU | done (DHU only) |
| 5 | Polish: launcher icon, refresh coalescing, R8 release build, perf re-measure, docs | done |
| 6 | Look v2: tinted textures, decorative trees, water depth, tintable arrow, bookmark icons | done |
| 8 | Name resolution (English/Latin labels), street-name toggle, credit fade + (i), vanilla arrow centring | done (batch 1 of the feature round) |
| 7 | Switchable texture pack (original / Minecraft from the user's own game files), icon anchors | done; vanilla verified on phone, car and desktop renders |

## Resuming work

- Read `README.md` (build, emulator, DHU), `docs/ADR-001-architecture.md`, `docs/ENGINE-SPEC.md`.
- Engine changes: `mapengine/` is pure JVM; run `./gradlew :mapengine:test` and look at `:tools:render:run` output. Keep `ENGINE-SPEC.md` in sync.
- Art changes: edit `tools/art/make_atlas.py`, regenerate, copy `atlas.png`/`atlas.json` into `mapengine/src/main/resources/minemaps/`, then run `tools/art/make_android_icons.py` if the app icon changed.
- Car work: `app/src/main/kotlin/app/minemaps/car/`. The surface is drawn with the software `lockCanvas` (hardware canvas crashed the process when the host destroyed the surface).
- Emulator `mm_phone` and the DHU are test fixtures only; follow the README procedure after any cold boot (head unit server must be restarted).
- Gate before finishing: `./gradlew :mapengine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease`.

## Test counts (last run)

- `:mapengine:test`: 90 tests, 0 failures (incl. MVT fuzzing: 20k random inputs, 5k fixture mutations, crafted malformed lengths/varints/gzip bombs; cache corruption; byte-bounded tile memory).
- `:app:testDebugUnitTest`: 30 tests, 0 failures (Photon parsing, search flow, saved-place codec and sanitising, stable-area math, car `MapScreen` with Robolectric, car host allowlist).
- `:app:lintRelease`: 0 errors, 18 warnings, none in the Security category.

## Stability campaign (emulator + DHU)

Evidence outside the repo in `~/mm-qa/SUMMARY.md`. On the final build, with 0 app crashes and 0 ANRs:
- **Monkey:** 7 runs × 15k events, and a release (R8) build with 10k events.
- **Lifecycle:** rotation of every screen and dialog, night/day switching, trim-memory levels, process death, force-stop during search, font scale 2.0.
- **Corrupted data:** DataStore files and 2,153 cached tiles corrupted.
- **Network:** airplane toggling, slow network, DNS failure, clearing the cache offline.
- **Permissions and location:** permission revoke and permanent denial, location off, extreme and 10 Hz GPS fixes.
- **Memory:** 10-minute and 20-minute runs; the Java heap plateaus at 50-68 MB out of a 192 MB limit. LeakCanary found no app leaks; the car service is briefly held by the library's `CarAppBinder` until the host drops it, which is not cumulative.
- **Car (DHU):** session start/stop, app switching, day/night spam, restriction toggling, phone process kill, permission revoke, airplane mode, both surface sizes.

Bugs found and fixed:
- **OOM under monkey:** the tile cache was bounded by tile count, and decodes ran without a limit.
- **Camera reset** on activity recreation.
- **Dialog text lost** on rotation.
- **Stale "permanently denied" banner.**

Not covered: real split-screen layout, the disk-cache cap on the device (it is unit-tested), a real low-memory killer, physical devices.

## Acceptance matrix

Sections A-E follow the acceptance criteria of the product brief (map rendering, location and search, Android Auto,
art and visual quality, engineering quality); individual rows are worded from what was built and tested.
Evidence paths are under `/tmp/mm/` on the build machine (screenshots are not committed).

### A. Map rendering

| criterion | state | evidence |
|---|---|---|
| Block-grid look, overview <= z16, textured z17-z20, no smooth vector rendering | Verified | `/tmp/mm/render/*.png`, `CompositorTest`, `RasterizerTest`, phone shots `/tmp/mm/shots/02..04`, `/tmp/mm/release_phone_map.png` |
| Classes/widths/zoom rules from the spec on real data | Verified | `FixtureTest` (Paris z14 fixture), `RasterizerTest` |
| Labels, POI icons, collision layout, phone/car caps | Verified | `LabelLayoutTest`, `/tmp/mm/shots/*`, `/tmp/mm/car/*.png` |
| Day/night tiles | Verified | `CompositorTest.nightIsDarker`, `/tmp/mm/shots/13_night_theme.png`, `/tmp/mm/car/20_night.png` |
| Seamless tile edges | Verified in unit tests | `CompositorTest.buildingSpanningTileEdgeIsSeamless`, `variationIsKeyedOnGlobalCoordinates` |
| Gestures: drag, fling, pinch, double-tap, long press, tap | Partially | drag/swipe, tap, long press, zoom buttons exercised by `adb input`; pinch and double-tap not exercised (scripted double-tap was not recognised) |
| Performance (rasterize <= 25 ms, compose <= 10 ms warm) | Partially | desktop bench: rasterize 4.4 ms, compose 1.6-2.6 ms (`:tools:render:run --args=--bench`); emulator only, no real device |
| Offline: cached tiles render, banner shown | Verified | `/tmp/mm/shots/11_offline_banner.png`, `/tmp/mm/car/30..32` |

Texture pack switch (verified with `/tmp/mm/pack/`): Settings option and disabled-state text (`shots/01_*`), vanilla map with items and banner pin (`shots/02*`), switch back with no stale-pack tiles (`shots/03*`), vanilla picker (`shots/04*`), car (`car/01_minemaps_vanilla.png`), desktop renders (`render/*_vanilla.png`); `TexturePackTest` covers both packs (vanilla test is skipped when the pack is not bundled). Not verified: the disabled state with the pack absent (UI only), release build with the pack.

Look v2 (verified with renders under `/tmp/mm/v2/render/` and screenshots): decorative trees and water depth (`TerrainTest`, `boulogne_z18.png`, `lausanne_z17.png`), tinted textures and icons (atlas regenerated, renders), arrow colour setting (`/tmp/mm/v2/shots/01_*`, `02_*`, car `01_*`), bookmark icons (`03_*`..`06c_*`, car `02_map_zoomed_out_bookmarks.png`).

### B. Location and search

| criterion | state | evidence |
|---|---|---|
| Permission flow (rationale, grant, deny, permanent deny link) | Partially | rationale, real grant dialog and revoked state verified (`shots/01,02,12`); the "permanently denied -> app settings" path was not exercised |
| Follow mode, player arrow, accuracy circle | Verified | `shots/02`, car drive sequence `/tmp/mm/car/17_drive_*.png` |
| Location off / no GPS handling | Not verified | status rows are unit-tested (`MapScreenTest`) but "location off" was not provoked on the emulator |
| Search (Photon): debounce, cancel, results, fly-to, pin | Verified | `SearchFlowTest`, `PhotonParserTest`, `shots/05,06`, `/tmp/mm/release_phone_search.png`; latency up to ~14 s on the public instance |
| Saved places add/rename/delete, list, fly-to | Partially | add, list and car fly-to verified (`shots/07,08`, car `23_flyto_saved.png`), serialization tested; rename and delete only compiled/tested at codec level |
| Inspect card | Verified | `shots/04_inspect_building.png`, `FixtureTest.inspectFindsSomethingNearTheEiffelTower` |
| Settings (theme, icons, label names, clear cache) and licences | Partially | theme and settings/licences screens verified (`shots/09,10,13`); icons toggle, Latin names and clear-cache not exercised |

### C. Android Auto

| criterion | state | evidence |
|---|---|---|
| POI category, `MapWithContentTemplate`, no navigation category | Verified | merged manifest, `pm query-services`, host accepted templates |
| Appears in DHU launcher and launches with map + list | Verified | `/tmp/mm/car/12_dhu_launcher.png`, `16_relaunch.png`, release build `/tmp/mm/release_car_map.png` |
| Following keeps target at stable-area centre across sizes | Verified | logs + shots for default, 720p, wide, 6in (`/tmp/mm/car/25_*`, `26_*`, `27_*`) |
| Zoom in/out, recenter, flyTo | Verified | `/tmp/mm/car/18_*`, `23_*`, `24_*` |
| PAN action | Partially | host shows the 4-action strip incl. PAN in the non-touch layout (`36_hybrid_map.png`); pan mode itself and drag panning not exercised |
| Night/day | Verified | `/tmp/mm/car/20_night.png` |
| Works with phone app not in foreground | Verified | `/tmp/mm/car/28_phone_app_not_foreground.png` |
| Driving restrictions | Partially | host blocks the parked-only permission action while `restrict all` is active; `restrict all` did not change list limits (limit stayed 6) |
| Offline and permission-denied states | Verified | `/tmp/mm/car/30..35` |
| Template refresh quota | Verified | 0 "too many refreshes" warnings after coalescing; `MapScreenTest.burstOfStateChangesYieldsAtMostTwoInvalidations` |
| Real vehicle / physical phone / production head unit | Not verified | only DHU + emulator with sideloaded Android Auto 17.6 |
| Play Car App quality review | Not verified | not done |

### D. Art

| criterion | state | evidence |
|---|---|---|
| Original, reproducible textures/icons/app icon | Verified | `tools/art/make_atlas.py`, `make_android_icons.py`, `docs/ASSETS.md` |
| Consistent pixel look at all zooms, nearest-neighbour scaling | Verified by review | render PNGs, screenshots |
| Launcher icon (legacy + adaptive) | Verified | `/tmp/mm/release_launcher_icon.png` |
| Attribution and licence screen | Verified | `shots/10_licences.png`, `docs/THIRD_PARTY.md` |

### E. Engineering

| criterion | state | evidence |
|---|---|---|
| Pure-JVM engine with unit tests; Android modules build | Verified | 57 + 19 tests, `assembleDebug`, `assembleRelease` |
| Release build with R8 runs | Verified | release APK ~1.9 MB; phone map, search and car map smoke-tested (`/tmp/mm/release_*.png`) |
| No crashes/ANRs in the test sessions | Verified for the final builds | logcat scans; earlier hardware-canvas crashes were fixed by the software canvas |
| Network etiquette (User-Agent, <= 4 concurrent, caching, negative-cache of failing tiles) | Verified | `ProviderFailureTest`, `OpenMapTilesSource` |
| Lint | Verified | 0 errors, 18 warnings |
| Release signing | Not done | debug keystore fallback only |

## Performance (emulator only)

Phone, 10-swipe scripted pan, debug build, `dumpsys gfxinfo`:

| | swiftshader (`-gpu swiftshader_indirect`) | `-gpu host` |
|---|---|---|
| frames | 265 | 501 |
| janky frames | 60.8% | 7.8% |
| p50 / p90 / p95 / p99 | 61 / 89 / 101 / 121 ms | 21 / 30 / 32 / 32 ms |
| main-thread draw (MineMapsPerf, mean) | ~8-12 ms (labels 6-10 ms) | 3.6 ms (tiles 0.9, labels 2.6) |
| rasterize / compose per tile (mean) | ~46 / ~25 ms warm | 76 / 9.5 ms (includes cold start of the run) |

Car draw (software canvas, 800x400 surface): about 2.5 ms per frame.

## Known limitations

- English label mode transliterates names that have no Latin-script tag with ICU, which needs Android 10+ (API 29); on Android 8-9 such names stay in their local script. ICU output is phonetic (e.g. Arabic without vowels), not an official romanisation.
- The car screen shows no OpenStreetMap/OpenMapTiles attribution; the phone shows it at start, behind an (i) button, in Settings and on the Licences screen.

- No routing or turn-by-turn, by design: the car app uses the POI category.
- Vector data is z14 and is overzoomed to the block grid, so building footprints are simplified; tunnels and trams are hidden.
- The public OpenFreeMap and Photon instances have no SLA; Photon latency of about 14 s was observed.
- Android Auto was verified only on the DHU with an emulator and a sideloaded Android Auto 17.6, not on a real vehicle or head unit, and not with a physical phone.
- Pan mode via drag or rotary was not exercised; mouse input from outside the DHU window is not delivered, and dpad navigation to the PAN button did not work.
- The DHU `restrict all` command did not change list limits.
- The car uses a software canvas (`lockCanvas`) instead of `lockHardwareCanvas`.
- Performance numbers are from the emulator only; there are no real-device measurements.
- No offline area download; only the tile cache (200 MB, 30 days) is available offline.
- Release signing is not configured (debug keystore fallback).
- No Play quality review has been done.
- Search results from Photon can contain odd entries (stops, platforms) as returned by the service.
