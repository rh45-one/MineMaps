# Shared helpers for tools/*.sh (sourced, not executed).
# Provides: ROOT, PKG, die, ANDROID_HOME/ADB setup, select_phone (sets SERIAL), A (adb -s SERIAL ...).

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG="app.minemaps"

die() { echo "error: $*" >&2; exit 1; }

# SDK location: env, then ~/Android/env.sh (also sets the DHU library path), then local.properties.
if [[ -f "$HOME/Android/env.sh" && ( -z "${ANDROID_HOME:-}" || -z "${LD_LIBRARY_PATH:-}" ) ]]; then
  source "$HOME/Android/env.sh"
fi
if [[ -z "${ANDROID_HOME:-}" && -f "$ROOT/local.properties" ]]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties")"; export ANDROID_HOME
fi
ADB="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
command -v "$ADB" >/dev/null || die "adb not found; set ANDROID_HOME or install platform-tools"

A() { "$ADB" -s "$SERIAL" "$@"; }

# Picks the USB phone into SERIAL (emulators ignored unless SERIAL is preset) and checks it is authorized.
select_phone() {
  if [[ -z "${SERIAL:-}" ]]; then
    local phones
    mapfile -t phones < <("$ADB" devices | awk 'NR>1 && $1!="" && $1 !~ /^emulator-/ {print $1}')
    [[ ${#phones[@]} -eq 0 ]] && die "no phone found. Plug it in via USB, enable Developer options > USB debugging, and accept the prompt on the phone."
    [[ ${#phones[@]} -gt 1 ]] && die "several phones connected: ${phones[*]} - choose one with -s SERIAL"
    SERIAL="${phones[0]}"
  fi
  local state
  state="$("$ADB" -s "$SERIAL" get-state 2>&1 || true)"
  case "$state" in
    device) ;;
    *unauthorized*) die "$SERIAL is unauthorized: unlock the phone and accept the 'Allow USB debugging?' prompt, then rerun." ;;
    *) die "$SERIAL is not ready ($state)" ;;
  esac
  echo "Phone: $(A shell getprop ro.product.model | tr -d '\r') ($SERIAL), Android API $(A shell getprop ro.build.version.sdk | tr -d '\r')"
}

# Warn when a release build would be signed with the debug key (it then cannot be updated by or from official releases).
warn_release_key() {
  if [[ -n "${MINEMAPS_KEYSTORE_PATH:-}" || -f "$HOME/.config/minemaps/keystore.properties" ]]; then return 0; fi
  echo "warning: no release keystore (MINEMAPS_KEYSTORE_PATH or ~/.config/minemaps/keystore.properties): this APK is debug-signed and cannot be updated by or from official GitHub releases." >&2
}
