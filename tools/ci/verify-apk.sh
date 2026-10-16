#!/usr/bin/env bash
# verify-apk.sh <apk> <versionName> <expected-cert-sha256-hex>
# Checks the signing certificate, the version name and that no Mojang texture pack is bundled.
set -euo pipefail
apk=$1
version=$2
expected=$(echo "$3" | tr -d ':' | tr 'A-F' 'a-f')
bt="${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools/${BUILD_TOOLS_VERSION:-37.0.0}"

actual=$("$bt/apksigner" verify --print-certs "$apk" | sed -n 's/^.*[Ss]igner[^:]*: certificate SHA-256 digest: //p;s/^.*[Ss]igner #[0-9]* certificate SHA-256 digest: //p' | tr 'A-F' 'a-f' | sort -u)
if [ "$actual" != "$expected" ]; then
  echo "Signing certificate mismatch: got '$actual', expected '$expected'" >&2
  exit 1
fi
echo "certificate sha256 OK: $actual"

name=$("$bt/aapt2" dump badging "$apk" | sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p")
if [ "$name" != "$version" ]; then
  echo "versionName mismatch: got '$name', expected '$version'" >&2
  exit 1
fi
echo "versionName OK: $name"

if unzip -l "$apk" | grep -q 'minemaps/packs/vanilla'; then
  echo "APK contains the vanilla (Mojang) texture pack" >&2
  exit 1
fi
echo "no vanilla pack entries OK"
