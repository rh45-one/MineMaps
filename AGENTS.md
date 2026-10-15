# Agent notes for MineMaps

## Working agreements
- Commit after every completed, verified piece of work (small, focused commits). Never commit
  `mapengine/src/main/resources/minemaps/packs/vanilla/` (Mojang textures), `local.properties`, keystores or build output.
- Do not push unless asked.
- Start with `docs/STATUS.md` (state, limitations), then `docs/ADR-001-architecture.md` and `docs/ENGINE-SPEC.md`.
  Keep ENGINE-SPEC.md and STATUS.md in sync with changes.

## Commands
- Environment: `source ~/Android/env.sh` (ANDROID_HOME, PATH, DHU libraries).
- Gate: `./gradlew :mapengine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease`
- Desktop renders: `./gradlew :tools:render:run --args="--lat 48.8584 --lon 2.2945 --zoom 16 --width 1024 --height 768 --out /tmp/x.png [--night] [--pack original|vanilla]"`
- Art: `python3 tools/art/make_atlas.py`, then copy `tools/art/out/atlas.{png,json}` to `mapengine/src/main/resources/minemaps/`.
- Minecraft pack (local only): `python3 tools/art/import_minecraft.py [--jar|--version|--resource-pack]`.
- Phone: `tools/phone.sh [--logcat|--release]`; head unit: `tools/head-unit.sh [--full|--usb]`.
- Emulator `mm_phone` has a sideloaded Android Auto; restart its head unit server after cold boots, and quit the DHU
  with `quit` (killing it can wedge the server).
- Build output paths are git-ignored, and agent file tools cannot read them; copy images to `/tmp/mm/` to view them.
