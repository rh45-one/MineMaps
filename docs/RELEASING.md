# Releasing MineMaps

Releases are built and published by GitHub Actions when a version tag is pushed. The APK is signed with the release keystore stored in repository secrets, so installs from GitHub Releases can update each other (and be updated by the in-app updater).

## Versions come from tags
- `versionName` = the tag without the `v` (`v1.2.0` -> `1.2.0`).
- `versionCode` = `major*1_000_000 + minor*1_000 + patch` (`1.2.0` -> `1002000`). A `-rc.N` suffix does not change the code.
- Gradle resolves the version from, in order: property `minemaps.version`, env `MINEMAPS_VERSION`, the latest `v*` tag (`git describe`), else `0.0.0`. Local builds after a tag get `-dev+<sha>` appended to `versionName` only.
- The in-app updater only offers strictly higher `versionCode`s from the *latest non-prerelease* GitHub release; release candidates are ignored by it.

## Making a release
```bash
# on main, gate green
./gradlew :mapengine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease
git tag -a v1.2.0 -m "MineMaps 1.2.0"
git push origin v1.2.0
```
Release candidate: `git tag -a v1.2.0-rc.1 -m "rc 1" && git push origin v1.2.0-rc.1` (published as a GitHub *pre-release*; users on 1.1.x are not offered it).

## What `.github/workflows/release.yml` does
1. Validates the tag (`vX.Y.Z` or `vX.Y.Z-rc.N`).
2. Decodes the keystore secret into `$RUNNER_TEMP` and exports the signing env vars.
3. Runs the engine and app unit tests and builds the R8 release APK with `MINEMAPS_VERSION` set from the tag.
4. `tools/ci/verify-apk.sh`: the signing certificate SHA-256 must equal `32:72:0A:0B:...:86:65` (hard-coded in the workflow), `versionName` must equal the tag, and the APK must not contain `minemaps/packs/vanilla` (Mojang textures).
5. Publishes `MineMaps-vX.Y.Z.apk` and `MineMaps-vX.Y.Z.apk.sha256` with `gh release create --generate-notes` (`--prerelease` for `-rc`).
6. Always deletes the decoded keystore.

`ci.yml` runs the gate (tests, debug build, lint) on every push to `main` and every pull request. Third-party actions are pinned to commit SHAs (checkout v7.0.1, setup-java v6.0.1, setup-gradle v6.4.0, upload-artifact v7.0.2); update the SHA and the version comment together.

## Keystore and secrets
- The keystore is `~/.config/minemaps/release.jks`, credentials in `~/.config/minemaps/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`, mode 600). **Back both up somewhere safe and offline. If the key is lost, no installed copy can ever be updated again** (Android refuses updates signed with a different key); users would have to uninstall and reinstall.
- Repository secrets: `MINEMAPS_KEYSTORE_BASE64`, `MINEMAPS_KEYSTORE_PASSWORD`, `MINEMAPS_KEY_ALIAS`, `MINEMAPS_KEY_PASSWORD`.
- Local signing order: env (`MINEMAPS_KEYSTORE_PATH`, `MINEMAPS_KEYSTORE_PASSWORD`, `MINEMAPS_KEY_ALIAS`, `MINEMAPS_KEY_PASSWORD`), then `~/.config/minemaps/keystore.properties`, then `minemaps.release.*` in `local.properties`, then the debug key (Gradle prints a warning: such an APK cannot be updated by or from official releases).
- Never commit or print the keystore or passwords.

## Rotating or recovering
- There is no supported rotation: Android only allows a key change through APK signature lineage (`apksigner rotate`), which this setup does not use. Treat the key as permanent.
- Recreate the secrets from the backup: `base64 -w0 release.jks | gh secret set MINEMAPS_KEYSTORE_BASE64`, then `gh secret set` the three other values.
- If the key leaked: generate a new key, update the secrets and `EXPECTED_CERT_SHA256` in `release.yml`, and tell users to reinstall (export saved places first, see README).

## If a release fails
- Fix the problem, then delete the tag and re-tag: `git push origin :refs/tags/v1.2.0 && git tag -d v1.2.0`, then tag again and push. If a GitHub release was already created, delete it first: `gh release delete v1.2.0 --yes`.
- A failed verification step means nothing was published.
- Do not re-use a version number once users may have installed it; bump the patch instead.

## Self-built APKs
A local build is update-compatible with official releases only when signed with the same key (`~/.config/minemaps/keystore.properties`). `tools/phone.sh --release` and `tools/build-minecraft.sh --release` warn when the key is missing. A build that includes the Minecraft texture pack must never be distributed.
