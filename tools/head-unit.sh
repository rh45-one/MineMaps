#!/usr/bin/env bash
# Run the Android Auto Desktop Head Unit (DHU) on this laptop, driven by the phone plugged in over USB.
#
#   tools/head-unit.sh              16:10 head unit for a 1920x1200 screen, via ADB over USB
#   tools/head-unit.sh --install    build + install MineMaps on the phone first (tools/phone.sh)
#   tools/head-unit.sh --full       use the whole 1920x1080 video area (16:9) instead of 16:10
#   tools/head-unit.sh --usb        connect like a real car (USB accessory mode) instead of ADB
#   tools/head-unit.sh --dpi N      UI density reported to Android Auto (default 200)
#   tools/head-unit.sh --input M    touch | rotary | hybrid (default touch)
#   tools/head-unit.sh -s SERIAL    pick a phone when several are connected (or set ANDROID_SERIAL)
#
# DHU console commands (type in this terminal while it runs): day, night, restrict all, restrict none, help.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/android.sh"

MODE="16x10"; TRANSPORT="adb"; DPI=200; INPUT="touch"; INSTALL=0; SERIAL="${ANDROID_SERIAL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --install) INSTALL=1 ;;
    --full) MODE="full" ;;
    --usb) TRANSPORT="usb" ;;
    --dpi) DPI="${2:?missing dpi}"; shift ;;
    --input) INPUT="${2:?missing input mode}"; shift ;;
    -s|--serial) SERIAL="${2:?missing serial}"; shift ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
  shift
done
[[ "$DPI" =~ ^[0-9]+$ ]] || die "--dpi must be a number"
[[ "$INPUT" =~ ^(touch|rotary|hybrid)$ ]] || die "--input must be touch, rotary or hybrid"

DHU_DIR="$ANDROID_HOME/extras/google/auto"
DHU="$DHU_DIR/desktop-head-unit"
[[ -x "$DHU" ]] || die "DHU not installed: sdkmanager 'extras;google;auto'"
if ldd "$DHU" | grep -q "not found"; then
  die "DHU is missing libraries: $(ldd "$DHU" | awk '/not found/{print $1}' | xargs). Install libc++1 libc++abi1 libunwind, or put them on LD_LIBRARY_PATH (see README)."
fi

select_phone
export ANDROID_SERIAL="$SERIAL"
[[ $INSTALL -eq 1 ]] && "$ROOT/tools/phone.sh" -s "$SERIAL" --no-launch

A shell pm path com.google.android.projection.gearhead >/dev/null 2>&1 \
  || die "Android Auto is not installed on the phone. Install it from the Play Store."
A shell pm path "$PKG" >/dev/null 2>&1 \
  || echo "note: MineMaps is not installed on the phone yet (use --install)."

# Android Auto only streams fixed video sizes; 1920x1200 itself is rejected by the DHU. For a 1920x1200 screen we
# use 1920x1080 video with 96 px side margins, giving a 1728x1080 content area: exactly 16:10, and the window
# (1920x1080) still fits on the screen with its title bar.
CONFIG="$(mktemp --suffix=.ini)"
trap 'rm -f "$CONFIG"' EXIT
MARGIN=""
[[ "$MODE" == "16x10" ]] && MARGIN="marginwidth = 192"
cat > "$CONFIG" <<EOF
[general]
touch = $([[ "$INPUT" == "rotary" ]] && echo false || echo true)
touchpad = false
controller = $([[ "$INPUT" == "touch" ]] && echo false || echo true)
instrumentcluster = false
resolution = 1920x1080
$MARGIN
dpi = $DPI
framerate = 30

[sensors]
location = true
night_mode = true
driving_status = true
EOF

ARGS=(-c "$CONFIG" -i "$INPUT")
if [[ "$TRANSPORT" == "adb" ]]; then
  # Head unit server listening on 127.0.0.1:5277 (0x149D) on the phone?
  if ! A shell 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' | awk '$2 ~ /:149D$/ && $4=="0A" {f=1} END {exit !f}'; then
    cat <<'EOF'
The Android Auto head unit server is not running on the phone. On the phone:
  1. Android Auto settings (Settings > Connected devices > Connection preferences > Android Auto, or the app):
     scroll down and tap "Version" ~10 times, accept the developer-mode prompt.
  2. Three-dot menu > Developer settings > enable "Unknown sources" (MineMaps is not installed from Play).
  3. Three-dot menu > "Start head unit server". It stops when the phone reboots.
EOF
    read -r -p "Press Enter once the head unit server is started (Ctrl+C to quit)... "
  fi
  A forward tcp:5277 tcp:5277 >/dev/null
else
  echo "USB accessory mode: the phone will switch to Android Auto as if plugged into a car."
  echo "If the DHU cannot open the device, your user needs USB permissions for it (udev rule for the phone's vendor id)."
  ARGS+=(-u "$SERIAL")
fi

echo "Starting the head unit: $([[ "$MODE" == "16x10" ]] && echo "1728x1080 (16:10)" || echo "1920x1080 (16:9)") content, ${DPI} dpi, $INPUT input."
echo "Open MineMaps from the head unit's launcher. Type 'quit' here to stop cleanly."
[[ "$TRANSPORT" == "adb" ]] && echo "If it says 'connected' but the window stays blank, the phone's head unit server is stuck (often after
closing the DHU uncleanly): on the phone, Android Auto > three-dot menu > Stop head unit server, then Start head unit server."
cd "$DHU_DIR"
"$DHU" "${ARGS[@]}"
