#!/usr/bin/env bash
# verify-apk.sh <apk> <versionName> <expected-cert-sha256-hex>
# Checks the signing certificate, the version name and that no Mojang texture pack is bundled.
set -euo pipefail
export LC_ALL=C LANG=C

if [ "$#" -ne 3 ] || [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
  echo "Usage: $0 <apk> <versionName> <expected-cert-sha256-hex>" >&2
  exit 1
fi

apk=$1
if [[ "$apk" == -* ]]; then
  apk="./$apk"
fi
if [ ! -f "$apk" ] || [ ! -r "$apk" ]; then
  echo "APK file not found or unreadable: '$1'" >&2
  exit 1
fi

version=$2
if [ -z "$version" ] || [[ "$version" == *$'\n'* ]] || [[ "$version" == *$'\r'* ]]; then
  echo "Invalid expected versionName" >&2
  exit 1
fi

expected=$(printf '%s' "$3" | tr -d '[:space:]:' | tr 'A-F' 'a-f')
if ! [[ "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid expected cert SHA-256 digest: '$3'" >&2
  exit 1
fi

bt="${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools/${BUILD_TOOLS_VERSION:-37.0.0}"
if [ ! -x "$bt/apksigner" ]; then
  echo "apksigner not found or not executable: '$bt/apksigner'" >&2
  exit 1
fi
if [ ! -x "$bt/aapt2" ]; then
  echo "aapt2 not found or not executable: '$bt/aapt2'" >&2
  exit 1
fi

safe_apk=$apk
safe_tmpdir=""
cleanup_safe_tmpdir() {
  if [ -n "$safe_tmpdir" ]; then
    rm -rf -- "$safe_tmpdir"
    safe_tmpdir=""
  fi
}

pwd_phys=$(pwd -P)
if [[ "$apk" == *[*?\[]* ]] || [[ "$apk" == *[![:print:]]* ]] || [[ "$pwd_phys" == *[![:print:]]* ]]; then
  trap cleanup_safe_tmpdir EXIT
  trap 'cleanup_safe_tmpdir; exit 130' INT
  trap 'cleanup_safe_tmpdir; exit 143' TERM
  safe_tmpdir=$(mktemp -d /tmp/verify-apk.XXXXXX)
  if [[ "$apk" == */* ]]; then
    apk_dir=${apk%/*}
    apk_base=${apk##*/}
    [ -n "$apk_dir" ] || apk_dir="/"
  else
    apk_dir="."
    apk_base=$apk
  fi
  apk_abs_dir=$(CDPATH= cd -- "$apk_dir" && pwd -P)
  case "$apk_abs_dir" in
    /) apk_abs="/$apk_base" ;;
    *) apk_abs="$apk_abs_dir/$apk_base" ;;
  esac
  bt_abs=$(CDPATH= cd -- "$bt" && pwd -P)
  safe_apk="$safe_tmpdir/target.apk"
  ln -s -- "$apk_abs" "$safe_apk"
fi

run_apksigner() {
  if [ -n "$safe_tmpdir" ]; then
    (CDPATH= cd -- "$safe_tmpdir" && "$bt_abs/apksigner" "$@")
  else
    "$bt/apksigner" "$@"
  fi
}

actual=$(run_apksigner verify --print-certs "$safe_apk" | tr -d '\r' | sed -n 's/^.*[Ss]igner[^:]*: certificate SHA-256 digest: //p;s/^.*[Ss]igner #[0-9]* certificate SHA-256 digest: //p' | tr 'A-F' 'a-f' | sort -u)
if [ -z "$actual" ] || [ "$actual" != "$expected" ]; then
  echo "Signing certificate mismatch: got '$actual', expected '$expected'" >&2
  exit 1
fi
echo "certificate sha256 OK: $actual"

name=$("$bt/aapt2" dump badging "$safe_apk" | tr -d '\r' | sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p")
if [ -z "$name" ] || [ "$name" != "$version" ]; then
  echo "versionName mismatch: got '$name', expected '$version'" >&2
  exit 1
fi
echo "versionName OK: $name"

rc=0
entries=$(unzip -Z1 "$safe_apk") || rc=$?
cleanup_safe_tmpdir
if [ "$rc" -ne 0 ]; then
  echo "Failed to list APK entries (unzip -Z1 exited $rc)" >&2
  exit "$rc"
fi

found_rc=0
printf '%s\n' "$entries" | awk '{ s = tolower($0); gsub(/\\/, "/", s); if (index(s, "minemaps/packs/vanilla") > 0) found = 1 } END { exit (found ? 0 : 1) }' || found_rc=$?
if [ "$found_rc" -eq 0 ]; then
  echo "APK contains the vanilla (Mojang) texture pack" >&2
  exit 1
elif [ "$found_rc" -ne 1 ]; then
  echo "Failed to inspect APK entries (awk exited $found_rc)" >&2
  exit "$found_rc"
fi
echo "no vanilla pack entries OK"
