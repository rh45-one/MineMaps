#!/usr/bin/env bash
# One-command personal build of MineMaps with Minecraft's own textures.
#
#   tools/build-minecraft.sh                         import from the official client jar (downloaded from Mojang), build APK
#   tools/build-minecraft.sh --install               ...and install it on the USB-connected phone (tools/phone.sh)
#   tools/build-minecraft.sh --jar PATH.jar          use a Minecraft client jar you already have
#   tools/build-minecraft.sh --version 1.21.5        a specific Minecraft release
#   tools/build-minecraft.sh --resource-pack P.zip   layer a resource pack (zip or folder) on top; repeatable
#   tools/build-minecraft.sh --offline               never download the jar (resource pack / --jar only)
#   tools/build-minecraft.sh --release               optimised (R8) build instead of debug
#
# The textures stay on your machine (git-ignored) and the APK is for your own use: do not distribute it.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
die() { echo "error: $*" >&2; exit 1; }

IMPORT_ARGS=(); VARIANT="debug"; INSTALL=0; PHONE_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar|--version|--resource-pack) IMPORT_ARGS+=("$1" "${2:?missing value for $1}"); shift ;;
    --offline) IMPORT_ARGS+=("$1") ;;
    --release) VARIANT="release" ;;
    --install) INSTALL=1 ;;
    -s|--serial) PHONE_ARGS+=(-s "${2:?missing serial}"); shift ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
  shift
done

echo "== Checking prerequisites"
command -v python3 >/dev/null || die "python3 not found (install Python 3)"
python3 -c 'import PIL' 2>/dev/null || die "Pillow missing: python3 -m pip install --user pillow   (Debian/Ubuntu: sudo apt install python3-pil)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA" >/dev/null || die "Java not found: install JDK 21 (e.g. sudo apt install openjdk-21-jdk, or Temurin 21) or set JAVA_HOME"
JV="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[[ "${JV:-0}" -ge 21 ]] || die "JDK 21 or newer required (found ${JV:-unknown}); set JAVA_HOME to a JDK 21"
[[ -f "$HOME/Android/env.sh" && -z "${ANDROID_HOME:-}" ]] && source "$HOME/Android/env.sh"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -z "$SDK" && -f "$ROOT/local.properties" ]] && SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties")"
[[ -n "$SDK" && -d "$SDK" ]] || die "Android SDK not found. Install Android Studio (it sets up the SDK) or the command-line tools,
then either export ANDROID_HOME=/path/to/sdk or put sdk.dir=/path/to/sdk in local.properties. See README > Prerequisites."
grep -q '^sdk\.dir=' "$ROOT/local.properties" 2>/dev/null || echo "sdk.dir=$SDK" >> "$ROOT/local.properties"

echo "== Importing Minecraft textures"
python3 "$ROOT/tools/art/import_minecraft.py" "${IMPORT_ARGS[@]}"

echo "== Building the $VARIANT APK"
(cd "$ROOT" && ./gradlew --quiet ":app:assemble${VARIANT^}")
APK="$(ls -t "$ROOT/app/build/outputs/apk/$VARIANT"/*.apk | head -1)"
echo "APK: $APK"
echo "Personal use only: this APK contains Mojang's textures. Do not share it."

if [[ $INSTALL -eq 1 ]]; then
  echo "== Installing on the phone"
  "$ROOT/tools/phone.sh" --no-build "${PHONE_ARGS[@]}" $([[ "$VARIANT" == "release" ]] && echo --release)
fi
echo "Then in the app: Settings > Textures > Minecraft."
