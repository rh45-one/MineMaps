#!/usr/bin/env bash
# Build MineMaps and install it on a USB-connected Android phone.
#
#   tools/phone.sh              build debug, install, launch
#   tools/phone.sh --release    build the R8 release build instead
#   tools/phone.sh --logcat     stream the app's logs after launching (Ctrl+C to stop)
#   tools/phone.sh --no-build   reinstall the last built APK
#   tools/phone.sh --no-launch  install only
#   tools/phone.sh --reinstall  uninstall first (needed when switching signing keys; deletes app data)
#   tools/phone.sh -s SERIAL    pick a phone when several are connected (or set ANDROID_SERIAL)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/android.sh"

VARIANT="debug"; LOGCAT=0; BUILD=1; LAUNCH=1; REINSTALL=0; SERIAL="${ANDROID_SERIAL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) VARIANT="release" ;;
    --logcat) LOGCAT=1 ;;
    --no-build) BUILD=0 ;;
    --no-launch) LAUNCH=0 ;;
    --reinstall) REINSTALL=1 ;;
    -s|--serial) SERIAL="${2:?missing serial}"; shift ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
  shift
done

[[ "$VARIANT" == "release" ]] && warn_release_key
select_phone
SDK="$(A shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$SDK" -ge 26 ]] || die "phone runs API $SDK; MineMaps needs Android 8.0 (API 26) or newer"

APK_DIR="$ROOT/app/build/outputs/apk/$VARIANT"
if [[ $BUILD -eq 1 ]]; then
  echo "Building $VARIANT..."
  (cd "$ROOT" && ./gradlew --quiet ":app:assemble${VARIANT^}")
fi
APK="$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -1 || true)"
[[ -n "$APK" ]] || die "no APK in $APK_DIR (run without --no-build)"

if [[ $REINSTALL -eq 1 ]]; then
  echo "Uninstalling $PKG (app data is deleted)..."
  A uninstall "$PKG" >/dev/null 2>&1 || true
fi
echo "Installing $(basename "$APK")..."
if ! OUT="$(A install -r "$APK" 2>&1)"; then
  echo "$OUT" >&2
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" <<<"$OUT"; then
    die "the installed MineMaps is signed with a different key. Rerun with --reinstall (this deletes saved places on the phone)."
  fi
  die "install failed"
fi

[[ $LAUNCH -eq 1 ]] || { echo "Installed."; exit 0; }
A shell am force-stop "$PKG" >/dev/null 2>&1 || true
A shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
echo "MineMaps launched on the phone."

if [[ $LOGCAT -eq 1 ]]; then
  PID=""
  for _ in $(seq 1 20); do PID="$(A shell pidof "$PKG" | tr -d '\r')"; [[ -n "$PID" ]] && break; sleep 0.5; done
  [[ -n "$PID" ]] || die "app process not found for logcat"
  echo "Streaming logs for pid $PID (Ctrl+C to stop)..."
  A logcat --pid="$PID" -v time
fi
