# Agent notes for MineMaps

## Working agreements
- Commit after every completed, verified piece of work (small, focused commits). Never commit
  `mapengine/src/main/resources/minemaps/packs/vanilla/` (Mojang textures), `local.properties`, keystores, build output,
  or external skill files.
- Do not push unless asked.
- Start with `docs/STATUS.md` (state, limitations), then `docs/DEVELOPMENT.md`, `docs/ADR-001-architecture.md`,
  `docs/ENGINE-SPEC.md`, and `docs/ASSETS.md`. Keep `ENGINE-SPEC.md`, `STATUS.md`, and `README.md` in sync with changes.
- Releases and versioning: see `docs/RELEASING.md`. Pushing an annotated tag `vX.Y.Z` builds, verifies
  (`tools/ci/verify-apk.sh`: cert SHA-256, `versionName`, no bundled Mojang pack), and publishes the signed APK to
  GitHub Releases. The release keystore lives in `~/.config/minemaps/` — never print, log, or commit its credentials.

## Commands
- Environment: `source ~/Android/env.sh` (`ANDROID_HOME`, `PATH`, DHU libraries).
- Gate: `./gradlew :mapengine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease`
- Desktop renders: `./gradlew :tools:render:run --args="--lat 51.5007 --lon -0.1246 --zoom 17 --width 1024 --height 768 --out /tmp/x.png [--night] [--pack original|vanilla]"`
- Python deps (`tools/art/*.py`): `python3 -m pip install -r requirements.txt` (Pillow).
- Original art & launcher icons: `python3 tools/art/make_atlas.py`, copy `tools/art/out/atlas.{png,json}` to
  `mapengine/src/main/resources/minemaps/`, and run `python3 tools/art/make_android_icons.py` if the app icon changed.
- Minecraft pack (local only): `python3 tools/art/import_minecraft.py [--jar|--version|--resource-pack]`.
  **Keep `tools/art/import_minecraft.py` and `mapengine/.../art/importer/MinecraftImporter.kt` pixel-identical**:
  `MinecraftImporterTest.goldenAtlasIsPixelIdenticalToThePythonImporterOutput` verifies that both produce the exact same
  `atlas.png` and `atlas.json` from `~/.cache/minemaps-mc/client-26.3.jar`. Bump `MinecraftImporter.IMPORTER_VERSION`
  when recipes change so on-device stored packs rebuild automatically on Wi-Fi.
- Phone: `tools/phone.sh [--logcat|--release]`; head unit: `tools/head-unit.sh [--full|--usb]`.
- Emulator `mm_phone` has a sideloaded Android Auto; restart its head unit server after cold boots, and quit the DHU
  with `quit` (killing it can wedge the server). Always shut down headless emulators (`adb emu kill`) when finished so
  they don't burn CPU in the background.
- Build output paths are git-ignored; copy images to `/tmp/mm/` when inspecting them.

## Website (`gh-pages` branch)
- The public one-page website (https://hrgsen.one/MineMaps/) lives on the standalone orphan branch `gh-pages` (with a
  working copy in `/tmp/mm/site/` during edits) so `main` stays pure application/engine code.
- Edit `gh-pages` via `git worktree add /tmp/mm/gh-pages gh-pages`, commit, push to `origin gh-pages`, and remove the
  worktree afterwards.
- Download buttons on the website query `https://api.github.com/repos/rh45-one/MineMaps/releases/latest` at runtime, so
  publishing a new `vX.Y.Z` release tag updates the site's download links and version label automatically.
