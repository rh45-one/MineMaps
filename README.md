# MineMaps

A real-world map drawn as a Minecraft-style block world, for the phone and for Android Auto.
OpenStreetMap data (OpenMapTiles vector tiles) is rasterised by our own engine onto a global block grid and
textured with original pixel art. There is no MapLibre and no routing: the Android Auto side uses the POI category.

Design decisions are in `docs/ADR-001-architecture.md`, the renderer rules in `docs/ENGINE-SPEC.md`,
the art pipeline in `docs/ASSETS.md`, licences in `docs/THIRD_PARTY.md`, and what is verified in `docs/STATUS.md`.

## Install and updates
- Download `MineMaps-vX.Y.Z.apk` from [GitHub Releases](https://github.com/rh45-one/MineMaps/releases) (a `.sha256` file is attached) and open it on your phone; allow installs from your browser or file manager when asked. Android may show a Play Protect warning for apps from an unknown developer.
- Release builds check GitHub for new versions (Settings > Updates: every launch / daily / weekly / manual only). An update is downloaded, checked against its SHA-256 and the app's signing certificate, and installed with the system installer. "Manual only" stops all automatic checks.
- Moving from a debug or self-built install: the signing keys differ, so Android will not update in place. In the old app use Settings > Saved places > Export, uninstall it, install the release APK, then Settings > Saved places > Import.
- Local self-builds are update-compatible only when signed with the release keystore in `~/.config/minemaps` (see `docs/RELEASING.md`).

## Features

Phone (`:app`)
- Block map: overview colours up to z16, textured blocks at z17-z20 (display zoom 3..21.5), day and night tiles.
- Labels (places, roads, water) in the Monocraft pixel font, item icons for points of interest, greedy collision layout.
- Location: follow mode, accuracy circle, Minecraft-map-style player arrow with a selectable colour (platform `LocationManager`, no Google Play services dependency).
- Bookmarks: saved places with a choice of 34 item icons, drawn on the map with names; decorative trees in forests and parks, depth-shaded water.
- Search: instant on-device results (places, streets, POIs from visited and cached tiles) merged with Photon; saved places (long-press to save); tap-to-inspect card (POI, building, road, water, land).
- Two-finger map rotation with a compass button (phone; the car stays north-up). Street-name quick toggle.
- Minecraft-style UI (bevelled blocks, Monocraft titles) that adapts to phones and tablets, portrait and landscape.
- Settings: theme (system/light/dark), item icons, label names (English/Latin or local language; transliteration needs Android 10+), tile cache size and clearing; licences screen.
- Offline behaviour: cached tiles keep rendering, a banner shows when the network is down.

Android Auto (POI category, `MapWithContentTemplate`, car API level 7)
- Draws the same map on the car surface (software canvas), follow mode centred in the stable area, zoom in/out, recenter, PAN action.
- Content pane: saved places sorted by distance (tap flies there), status rows (offline, location off, finding location),
  permission message with a "Grant on phone" action.

## Prerequisites

- JDK 21 (`/usr/bin/java`), Python 3 with Pillow (only to regenerate the art or import Minecraft textures).
- Android SDK in `~/Android/Sdk`, installed with `sdkmanager`:
  `cmdline-tools;latest`, `platform-tools` 37.0.1, `emulator` 37.2.12, `platforms;android-37.0`, `build-tools;37.0.0`,
  `extras;google;auto` 2.0 (the Desktop Head Unit), `system-images;android-37.0;google_apis_playstore;x86_64`.
- Toolchain: Gradle 9.8.1 (wrapper), Android Gradle Plugin 9.4.1, Kotlin 2.4.21 (versions in `gradle/libs.versions.toml`).
- `local.properties` needs `sdk.dir=/home/<you>/Android/Sdk` (git-ignored).
- `~/Android/env.sh` (not part of the repo) is a small helper that exports `ANDROID_HOME`/`ANDROID_SDK_ROOT`, puts
  `cmdline-tools/latest/bin`, `platform-tools` and `emulator` on `PATH`, and adds the extracted DHU shared libraries
  (see below) to `LD_LIBRARY_PATH`. Source it in every shell that uses `adb`, `emulator` or the DHU.

## Minecraft textures (optional)

MineMaps releases contain only the app's own original art. Minecraft's textures belong to Mojang Studios, so they are never in the repository or in a release APK. You can add them on your phone:

- **In the app:** Settings > Textures > *Include Minecraft textures*. Choose *Download now* (downloads the official Minecraft 26.3 client jar, about 41 MB, directly from Mojang, checks its SHA-1, builds the texture pack on the phone and deletes the jar) or *At next update* (the same download happens automatically on Wi-Fi after the app is next updated). You should own Minecraft Java Edition.
- **From a file:** Settings > Textures > *Import from file...* takes a client jar or a resource-pack zip (HD packs are scaled to 16x16); works offline.
- The imported pack lives in the app's private storage, survives app updates, and can be removed in Settings. If the stored pack is damaged or made by an older importer, the original art is used until it is re-imported (automatically on Wi-Fi).

## Optional: build it yourself with Minecraft's textures

If you build the app from source you can instead bundle the textures into your own APK (a bundled pack takes precedence over the imported one):

MineMaps ships only its own original art. It can also show Minecraft's real block textures, item icons, map arrow and
banner markers (Settings > Textures > Minecraft), but those belong to Mojang Studios and are **not included in this
repository or in any MineMaps release**. If you have access to Minecraft Java Edition, you can import them on your own
computer and compile a personal copy of the app. It takes one script and some patience.

### What you need

- A computer with Linux or macOS (Windows works through WSL).
- JDK 21: e.g. `sudo apt install openjdk-21-jdk`, or [Eclipse Temurin 21](https://adoptium.net/).
- The Android SDK: the easiest route is to install [Android Studio](https://developer.android.com/studio) and open it once
  (it downloads the SDK to `~/Android/Sdk`). Command-line alternative: see [Prerequisites](#prerequisites).
- Python 3 with Pillow: `python3 -m pip install --user pillow` (Debian/Ubuntu: `sudo apt install python3-pil`).
- Minecraft Java Edition (recommended: you should own it; see the note below). The game does not need to be installed.
- To put the app on your phone: a USB cable, and on the phone Settings > About phone > tap "Build number" 7 times,
  then Developer options > USB debugging.

### Build and install

```
git clone <this repository> MineMaps && cd MineMaps
export ANDROID_HOME=~/Android/Sdk           # or wherever your SDK is
tools/build-minecraft.sh --install          # import textures, build the app, install it on the plugged-in phone
```

Then open MineMaps and pick Settings > Textures > Minecraft. Leave out `--install` to just get the APK
(`app/build/outputs/apk/debug/app-debug.apk`).

Where the textures come from, in order of preference:

| Command | Source |
|---|---|
| `tools/build-minecraft.sh --jar ~/.minecraft/versions/1.21.5/1.21.5.jar` | The game you already have installed (path differs per launcher). |
| `tools/build-minecraft.sh` | The official client jar for the pinned release (26.3), downloaded directly from Mojang's own launcher servers (the same file the Minecraft launcher fetches), SHA-1 verified, cached in `~/.cache/minemaps-mc`. The default is the version the app pins (26.3); `--version 1.21.5` picks another release. |
| `tools/build-minecraft.sh --resource-pack MyPack.zip` | Any Minecraft resource pack (zip or folder) layered on top of the game textures. HD packs are scaled to 16x16. Add `--offline` to use only the pack; whatever it lacks keeps the MineMaps art. |

The importer (`tools/art/import_minecraft.py`, also usable on its own with the same options) never downloads from
third-party sites. It writes `mapengine/src/main/resources/minemaps/packs/vanilla/`, which is git-ignored, and
`SOURCE.txt` in there records exactly what was used. Bed and chest keep the MineMaps drawings because the game has no
flat item texture for them. Re-run the script after updating Minecraft or changing packs. Delete that folder and rebuild
to go back to an APK without Mojang's textures.

### Copyright and fair play

- The repository and official MineMaps builds contain no Mojang assets; the import happens on your machine.
- Minecraft's textures are © Mojang Studios and subject to the [Minecraft EULA](https://www.minecraft.net/en-us/eula).
  Import them only if you have the right to use them (owning Minecraft Java Edition is the safe assumption).
- An APK built with the Minecraft textures is for your own devices only. **Do not share, upload or publish it**, and do not
  commit the `packs/vanilla` folder. This applies to `--release` builds too.
- This is not legal advice. If in doubt, stick with the original MineMaps textures.

The desktop render tool can use the pack too: `--pack original|vanilla`.

## Build and test

```
./gradlew :mapengine:test :app:testDebugUnitTest      # unit tests (JVM, Robolectric for the car screen)
./gradlew :app:assembleDebug                          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                        # R8-minified release APK
./gradlew :app:lintDebug
```

Release signing: if `minemaps.release.storeFile` (plus `minemaps.release.storePassword`, `minemaps.release.keyAlias`,
`minemaps.release.keyPassword`) are set in `local.properties`, the release build uses them. Otherwise it is signed with
the debug keystore, which is only good for local testing. Real signing is not configured.

`local.properties` keys (all optional):

| key | default |
|---|---|
| `minemaps.tilejson` | `https://tiles.openfreemap.org/planet` |
| `minemaps.photon` | `https://photon.komoot.io` |

They become `BuildConfig.TILEJSON_URL` / `PHOTON_URL`; any OpenMapTiles-schema TileJSON source works.

## Run on your phone

Enable Developer options > USB debugging on the phone, plug it in, accept the prompt, then:

```
tools/phone.sh              # build debug, install, launch
tools/phone.sh --logcat     # same, then stream the app's logs
tools/phone.sh --release    # R8 release build
tools/phone.sh --help       # all options (-s SERIAL, --no-build, --no-launch, --reinstall)
```

Android Auto head unit on the laptop, driven by the USB phone:

```
tools/head-unit.sh            # 16:10 head unit (1728x1080 content in a 1920x1080 window), for a 1920x1200 screen
tools/head-unit.sh --install  # build + install MineMaps on the phone first
tools/head-unit.sh --full     # full 1920x1080 (16:9)
tools/head-unit.sh --help     # --usb (car-like USB accessory mode), --dpi, --input touch|rotary|hybrid, -s SERIAL
```

Android Auto only streams fixed video sizes and the DHU rejects 1920x1200, hence the 16:10 margins. One-time phone
setup: Android Auto settings > tap "Version" ~10 times > three-dot menu > enable "Unknown sources", then
"Start head unit server" (again after every phone reboot; the script checks and prints these steps). Type `day`,
`night`, `restrict all`, `restrict none` or `quit` in the terminal while it runs. If it connects but stays blank,
stop and start the head unit server on the phone.

Emulators are ignored by both scripts unless selected with `-s emulator-5554`.

## Run on the emulator

```
source ~/Android/env.sh
avdmanager create avd -n mm_phone -k "system-images;android-37.0;google_apis_playstore;x86_64" -d pixel_7
emulator -avd mm_phone -gpu host            # add -no-window -no-audio -gpu swiftshader_indirect for headless
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb emu geo fix 2.2945 48.8584              # lon lat; fused location may need GPS fixes to be repeated
```

`-gpu host` is much smoother than software rendering (see `docs/STATUS.md` for measured numbers).

## Android Auto testing on the DHU

This is the procedure that was used. Android Auto is only verified this way (emulator + DHU), not on a vehicle.

1. **Android Auto on the emulator.** The Google Play system image ships only a stub (`1.2.x-stub`) and Play reports
   "isn't compatible with your device" for Android Auto on emulators, so it cannot be updated from Play.
   Sideload the official Google-signed bundle instead: download an `.apkm` for `com.google.android.projection.gearhead`
   from APKMirror (used: 17.6.663458, the x86_64 variant; arm64 variants also exist), unzip it, and install the splits:
   `adb install-multiple base.apk split_config.x86_64.apk split_config.en.apk split_config.xxhdpi.apk`.
2. **Check the signer before installing.** Pull the stub (`adb pull /product/priv-app/AndroidAutoStubPrebuilt/AndroidAutoStubPrebuilt.apk`)
   and compare `apksigner verify --print-certs` of the stub and of `base.apk`. The signer SHA-256 digests must match
   (here `1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295`). Do not use modified builds.
3. **Developer mode.** Open Android Auto settings (`adb shell am start -a com.google.android.projection.gearhead.SETTINGS`),
   scroll to the bottom and tap "Version and permission info" about 10 times, accept the dialog. In the overflow menu
   open "Developer settings" and tick "Unknown sources", then choose "Start head unit server" from the overflow menu.
   The head unit server must be started again after every emulator cold boot.
4. **Preflight workaround.** Android Auto's welcome flow wants notification access and its runtime permissions. Grant
   them from the shell (`pm grant com.google.android.projection.gearhead android.permission.<PERM>` for location,
   contacts, calendar, call log, phone, SMS, microphone, Bluetooth, notifications) and make sure
   `cmd notification allow_listener com.google.android.projection.gearhead/com.google.android.gearhead.notifications.SharedNotificationListenerManager`
   is enabled. If the car screen still says "turn on notification access", disallow and re-allow that listener, then reconnect the DHU.
5. **Forward the port and start the DHU.**
   `adb forward tcp:5277 tcp:5277`, then run `~/Android/Sdk/extras/google/auto/desktop-head-unit` (optionally `-c config/default_720p.ini`,
   `default_wide.ini`, `default_6in.ini`, `hybrid.ini` for a non-touch layout with the PAN button) from an interactive terminal
   with `DISPLAY` set. The DHU binary needs `libc++.so.1`, `libc++abi.so.1` and `libunwind.so.1`; without root, download
   `libc++1-19`, `libc++abi1-19`, `libunwind-19` with `apt-get download`, extract with `dpkg -x` and add the lib directories to `LD_LIBRARY_PATH`.
6. **Console commands** (type in the DHU terminal): `tap X Y` (touch; mouse input from outside the window is not delivered), `night`/`day`,
   `restrict all`/`restrict none`, `keycode home`, `screenshot`, `help`.
7. Grant MineMaps location permission on the phone (or tap "Grant on phone" in the car), then open MineMaps from the DHU launcher.
   Drive with `adb emu geo fix <lon> <lat>` repeated once a second.

Physical phone alternative: enable Android Auto developer mode on the phone, connect it with USB, start the head unit server
and run `desktop-head-unit --usb`.

## Desktop render tool

```
./gradlew :tools:render:run --args="--lat 48.8584 --lon 2.2945 --zoom 16 --width 1024 --height 768 --out /tmp/paris_z16.png [--night] [--pack original|vanilla]"
./gradlew :tools:render:run --args="--bench"       # single-threaded timings on the Paris fixture
```

It fetches real tiles (cache in `~/.cache/minemaps-dev`), composes the mosaic with the real engine and overlays labels and icons.

## Project layout

```
mapengine/        pure Kotlin/JVM engine: geo, mvt decoder, style/classifier, rasteriser, compositor, labels + layout, data (tiles, cache)
app/              Android app: map view/renderer, Compose UI, search, location, settings, car app (app.minemaps.car)
tools/render/     desktop renderer and benchmark
tools/art/        make_atlas.py (textures, icons, app icon) and make_android_icons.py (launcher icons)
docs/             ADR, engine spec, assets, third-party licences, status
```
