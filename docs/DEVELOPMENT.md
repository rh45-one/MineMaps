# Developer Guide

This guide covers everything needed to build, run, test, and contribute to MineMaps.

For specific design and subsystem documentation, see:
- [`docs/ADR-001-architecture.md`](ADR-001-architecture.md) — Architecture decisions (why a custom block rasteriser instead of MapLibre, block grid math, OpenFreeMap, Photon, Android Auto `NAVIGATION` category).
- [`docs/ENGINE-SPEC.md`](ENGINE-SPEC.md) — Authoritative specification for `:mapengine` (coordinates, block classes, line widths, buildings, shading, decorative trees, label layout, rotation, on-device place index, texture importer).
- [`docs/ASSETS.md`](ASSETS.md) — Original 16×16 pixel art pipeline, palette, icon anchors, and POI mapping.
- [`docs/RELEASING.md`](RELEASING.md) — Tag-driven GitHub Actions releases, signing keystore, secrets, and verification.
- [`docs/STATUS.md`](STATUS.md) — Verification matrix, benchmark numbers, stability campaign results, and known limitations.
- [`docs/THIRD_PARTY.md`](THIRD_PARTY.md) — Third-party libraries, fonts, and data licences.
- [`LICENCE`](../LICENCE) — Project licence (CC BY-NC 4.0).

---

## Project layout

```
mapengine/        Pure Kotlin/JVM engine: geo, MVT decoder, classifier, rasteriser,
                  compositor, labels + layout, on-device place index, tile cache,
                  and the pure-JVM Minecraft texture importer
app/              Android app: map view/renderer, Compose UI, search, location,
                  settings, saved-places backup, in-app updater, and Android Auto
                  car app (app.minemaps.car)
tools/render/     Desktop CLI renderer and benchmark
tools/art/        make_atlas.py (original textures, icons, app icon),
                  make_android_icons.py (launcher icons), and
                  import_minecraft.py (local Minecraft texture importer)
tools/ci/         CI verification scripts (verify-apk.sh)
tools/            Helper scripts for physical phone (phone.sh), Android Auto DHU
                  (head-unit.sh), and one-command local Minecraft builds (build-minecraft.sh)
docs/             Architecture, engine spec, assets, releasing, status, licences
```

---

## Prerequisites

- **JDK 21** (`/usr/bin/java`), e.g. `sudo apt install openjdk-21-jdk` or [Eclipse Temurin 21](https://adoptium.net/).
- **Python 3 with Pillow** (`python3 -m pip install -r requirements.txt` or `sudo apt install python3-pil`) — only needed to regenerate the original art atlas or run the desktop Python texture importer.
- **Android SDK** in `~/Android/Sdk` (easiest via [Android Studio](https://developer.android.com/studio), or via `sdkmanager`):
  - `cmdline-tools;latest`, `platform-tools` 37.0.1, `emulator` 37.2.12
  - `platforms;android-37.0`, `build-tools;37.0.0`
  - `extras;google;auto` 2.0 (the Desktop Head Unit)
  - `system-images;android-37.0;google_apis_playstore;x86_64`
- **Toolchain:** Gradle 9.8.1 (wrapper), Android Gradle Plugin 9.4.1, Kotlin 2.4.21 (versions in `gradle/libs.versions.toml`).
- `local.properties` at the repo root needs `sdk.dir=/home/<you>/Android/Sdk` (git-ignored).
- `~/Android/env.sh` (optional helper outside the repo) exports `ANDROID_HOME`/`ANDROID_SDK_ROOT`, puts `cmdline-tools/latest/bin`, `platform-tools`, and `emulator` on `PATH`, and adds the extracted DHU shared libraries to `LD_LIBRARY_PATH`.

---

## Build, test, and lint

The standard verification gate before finishing any change is:

```bash
./gradlew :mapengine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease
```

Individual tasks:

```bash
./gradlew :mapengine:test                  # Pure-JVM engine unit & fuzz tests
./gradlew :app:testDebugUnitTest           # App unit tests (incl. Robolectric car tests)
./gradlew :app:assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease             # R8-minified release APK
./gradlew :app:lintRelease                 # Android lint (release variant)
```

### Configuration overrides (`local.properties`)

All keys in `local.properties` are optional:

| Key | Default | Description |
|---|---|---|
| `sdk.dir` | — | Path to Android SDK |
| `minemaps.tilejson` | `https://tiles.openfreemap.org/planet` | Any OpenMapTiles-schema TileJSON endpoint (`BuildConfig.TILEJSON_URL`) |
| `minemaps.photon` | `https://photon.komoot.io` | Photon geocoder base URL (`BuildConfig.PHOTON_URL`) |
| `minemaps.updateRepo` | `rh45-one/MineMaps` | GitHub `owner/repo` checked by the in-app updater |
| `minemaps.release.storeFile` | — | Release keystore path (if not using env vars or `~/.config/minemaps/keystore.properties`) |
| `minemaps.release.storePassword` | — | Keystore password |
| `minemaps.release.keyAlias` | — | Key alias |
| `minemaps.release.keyPassword` | — | Key password |

### Release signing and versioning

- Versions are derived from `minemaps.version` property → `MINEMAPS_VERSION` env var → latest `v*` git tag (`git describe`) → `0.0.0`. Commits after a tag get `-dev+<sha>` appended to `versionName`.
- Release signing checks `MINEMAPS_KEYSTORE_*` env vars → `~/.config/minemaps/keystore.properties` → `local.properties` → debug keystore fallback (with a warning).
- See [`docs/RELEASING.md`](RELEASING.md) for the full release pipeline and keystore management.

### Debug mode and LeakCanary

Debug builds include LeakCanary (`debugImplementation`), gated at runtime by **Settings → Developer → Debug mode (leak detection notifications)** (off by default). `DebugTools` has separate `src/debug` and `src/release` implementations so release builds contain zero LeakCanary references. The known transient `androidx.car.app.CarAppBinder#mService` retention after Android Auto disconnects is registered as a library leak matcher.

---

## Running on a physical phone

Enable **Settings → About phone → tap Build number 7 times**, then **Developer options → USB debugging**, plug the phone in, and accept the prompt:

```bash
tools/phone.sh              # build debug, install (-r, keeps data), launch
tools/phone.sh --logcat     # same, then stream the app's logs
tools/phone.sh --release    # R8 release build
tools/phone.sh --help       # all options (-s SERIAL, --no-build, --no-launch, --reinstall)
```

---

## Running on the Android Auto Desktop Head Unit (DHU)

### Driven by a physical phone over USB

One-time phone setup: open **Android Auto settings**, tap **Version** ~10 times to enable developer mode, open the three-dot menu → **Developer settings** → enable **Unknown sources**, and choose **Start head unit server** from the three-dot menu (repeat "Start head unit server" after every phone reboot).

```bash
tools/head-unit.sh            # 16:10 head unit (1728x1080 content in a 1920x1080 window)
tools/head-unit.sh --install  # build + install MineMaps on the phone first
tools/head-unit.sh --full     # full 1920x1080 (16:9)
tools/head-unit.sh --help     # --usb, --dpi, --input touch|rotary|hybrid, -s SERIAL
```

While the DHU runs, type `day`, `night`, `restrict all`, `restrict none`, or `quit` in the terminal. Always close with `quit` — killing the window abruptly can wedge the phone's head unit server (if it connects to a blank window, stop and restart the head unit server on the phone).

### Driven by the Android emulator

```bash
source ~/Android/env.sh
avdmanager create avd -n mm_phone -k "system-images;android-37.0;google_apis_playstore;x86_64" -d pixel_7
emulator -avd mm_phone -gpu host            # add -no-window -no-audio -gpu swiftshader_indirect for headless
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb emu geo fix 12.4922 41.8902             # lon lat (Colosseum, Rome)
```

Setting up Android Auto on the emulator:
1. **Sideload Android Auto:** The Play Store emulator image ships only a stub (`1.2.x-stub`) and Play marks Android Auto incompatible on emulators. Download an official Google-signed `.apkm` bundle for `com.google.android.projection.gearhead` (e.g. `x86_64` variant), verify that `apksigner verify --print-certs` on `base.apk` matches the preinstalled stub (`1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295`), and install the splits with `adb install-multiple base.apk split_config.x86_64.apk split_config.en.apk split_config.xxhdpi.apk`.
2. **Enable developer mode & head unit server:** Launch `adb shell am start -a com.google.android.projection.gearhead.SETTINGS`, tap **Version and permission info** 10 times, enable **Unknown sources** in Developer settings, and tap **Start head unit server** from the overflow menu (repeat after every cold boot).
3. **Grant permissions & notification listener:** Grant runtime permissions to `com.google.android.projection.gearhead` via `pm grant` and enable its notification listener (`cmd notification allow_listener com.google.android.projection.gearhead/com.google.android.gearhead.notifications.SharedNotificationListenerManager`).
4. **Start the DHU:** Run `adb forward tcp:5277 tcp:5277` and launch `~/Android/Sdk/extras/google/auto/desktop-head-unit` (optionally with `-c config/default.ini`, `default_720p.ini`, `default_1080p.ini`, etc.). The DHU binary requires `libc++.so.1`, `libc++abi.so.1`, and `libunwind.so.1` on `LD_LIBRARY_PATH`.

---

## Desktop render tool & benchmark

`:tools:render` runs the pure-JVM `:mapengine` on your machine, fetches real tiles (cached in `~/.cache/minemaps-dev`), composes the block mosaic, and draws labels and icons:

```bash
./gradlew :tools:render:run --args="--lat 41.8902 --lon 12.4922 --zoom 17 --width 1024 --height 768 --out /tmp/rome.png [--night] [--pack original|vanilla]"
./gradlew :tools:render:run --args="--bench"
```

---

## Art pipeline and local Minecraft texture builds

### Original MineMaps art

Edit `tools/art/make_atlas.py`, run it, and copy the generated files into the engine resources:

```bash
python3 tools/art/make_atlas.py
cp tools/art/out/atlas.png tools/art/out/atlas.json mapengine/src/main/resources/minemaps/
python3 tools/art/make_android_icons.py     # if the launcher icon changed
```

### Building an APK with bundled Minecraft textures (personal use only)

End users can import Minecraft textures directly inside the app (**Settings → Textures → Include Minecraft textures**), which uses the pure-JVM importer in `mapengine/src/main/kotlin/app/minemaps/engine/art/importer/`.

If you are building from source for your own devices, you can also bundle the pack at compile time into `mapengine/src/main/resources/minemaps/packs/vanilla/` (git-ignored):

```bash
export ANDROID_HOME=~/Android/Sdk
tools/build-minecraft.sh --install          # import textures, build debug APK, install on phone
```

Texture source options (usable with both `tools/build-minecraft.sh` and `python3 tools/art/import_minecraft.py`):

| Command | Source |
|---|---|
| `tools/build-minecraft.sh` | Downloads the pinned official Minecraft client jar (26.3) directly from Mojang's launcher servers, verifies its SHA-1, and caches it in `~/.cache/minemaps-mc`. Use `--version <ver>` for another release. |
| `tools/build-minecraft.sh --jar ~/.minecraft/versions/<v>/<v>.jar` | Uses a local client jar you already have installed. |
| `tools/build-minecraft.sh --resource-pack MyPack.zip [--offline]` | Layers a resource pack (`.zip` or folder) on top of the game textures (or standalone with `--offline`; missing entries keep MineMaps art). High-resolution packs are scaled to 16×16. |

**Important:** Mojang's textures are © Mojang Studios and subject to the [Minecraft EULA](https://www.minecraft.net/en-us/eula). Never commit `mapengine/src/main/resources/minemaps/packs/vanilla/` and never share or publish an APK built with bundled Minecraft textures. The CI release workflow (`tools/ci/verify-apk.sh`) refuses any APK that contains `minemaps/packs/vanilla`.
