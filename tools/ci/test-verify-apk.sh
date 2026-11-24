#!/usr/bin/env bash
# Repeatable verification matrix for tools/ci/verify-apk.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_APK="$SCRIPT_DIR/verify-apk.sh"

if [ ! -x "$VERIFY_APK" ]; then
  echo "Missing executable script: $VERIFY_APK" >&2
  exit 1
fi
for req in python3 unzip awk sed tr sort; do
  if ! command -v "$req" >/dev/null 2>&1; then
    echo "Required host command missing: $req" >&2
    exit 1
  fi
done

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT INT TERM

SYS_PATH="$PATH"
DEFAULT_CERT="32720a0b30a3856a966ada931c917d1465184bde92a5aeebd01f5e36792b8665"
DEFAULT_CERT_COLON_UPPER="32:72:0A:0B:30:A3:85:6A:96:6A:DA:93:1C:91:7D:14:65:18:4B:DE:92:A5:AE:EB:D0:1F:5E:36:79:2B:86:65"
WRONG_CERT="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
DEFAULT_VER="1.2.14"

# Mock Android SDK build-tools
MOCK_SDK="$WORK_DIR/sdk"
MOCK_BT="$MOCK_SDK/build-tools/37.0.0"
mkdir -p "$MOCK_BT"

cat > "$MOCK_BT/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" != "verify" ] || [ "${2:-}" != "--print-certs" ] || [ -z "${3:-}" ] || [ ! -f "${3:-}" ]; then
  echo "mock apksigner: invalid args: $*" >&2
  exit 2
fi
rc="${MOCK_APKSIGNER_RC:-0}"
if [ "$rc" -ne 0 ]; then
  echo "mock apksigner: failing with rc=$rc" >&2
  exit "$rc"
fi
cert="${MOCK_CERT_SHA256:-32720a0b30a3856a966ada931c917d1465184bde92a5aeebd01f5e36792b8665}"
fmt="${MOCK_APKSIGNER_FORMAT:-v2}"
case "$fmt" in
  v2)
    printf 'V2 Signer: certificate DN: C=US, O=Android, CN=Mock\n'
    printf 'V2 Signer: certificate SHA-256 digest: %s\n' "$cert"
    ;;
  v1)
    printf 'Signer #1 certificate DN: C=US, O=Android, CN=Mock\n'
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "$cert"
    ;;
  crlf)
    printf 'V2 Signer: certificate SHA-256 digest: %s\r\n' "$cert"
    ;;
  empty)
    printf 'Verified using v2 scheme (APK Signature Scheme v2): true\n'
    ;;
  *)
    echo "mock apksigner: unknown format $fmt" >&2
    exit 3
    ;;
esac
EOF
chmod +x "$MOCK_BT/apksigner"

cat > "$MOCK_BT/aapt2" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" != "dump" ] || [ "${2:-}" != "badging" ] || [ -z "${3:-}" ] || [ ! -f "${3:-}" ]; then
  echo "mock aapt2: invalid args: $*" >&2
  exit 2
fi
rc="${MOCK_AAPT2_RC:-0}"
if [ "$rc" -ne 0 ]; then
  echo "mock aapt2: failing with rc=$rc" >&2
  exit "$rc"
fi
ver="${MOCK_VERSION_NAME:-1.2.14}"
fmt="${MOCK_AAPT2_FORMAT:-normal}"
case "$fmt" in
  normal)
    printf "package: name='app.minemaps' versionCode='1002014' versionName='%s' compileSdkVersion='37'\n" "$ver"
    ;;
  crlf)
    printf "package: name='app.minemaps' versionCode='1002014' versionName='%s' compileSdkVersion='37'\r\n" "$ver"
    ;;
  empty)
    printf "application-label:'MineMaps'\n"
    ;;
  *)
    echo "mock aapt2: unknown format $fmt" >&2
    exit 3
    ;;
esac
EOF
chmod +x "$MOCK_BT/aapt2"

# Mock unzip wrapper
MOCK_UNZIP_BIN="$WORK_DIR/mock-unzip-bin"
mkdir -p "$MOCK_UNZIP_BIN"
cat > "$MOCK_UNZIP_BIN/unzip" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ -n "${MOCK_UNZIP_STDOUT:-}" ]; then
  printf '%b' "$MOCK_UNZIP_STDOUT"
fi
if [ -n "${MOCK_UNZIP_STDERR:-}" ]; then
  printf '%b' "$MOCK_UNZIP_STDERR" >&2
fi
exit "${MOCK_UNZIP_RC:-0}"
EOF
chmod +x "$MOCK_UNZIP_BIN/unzip"

# Build isolated bin directories without unzip or without awk
build_filtered_bin() {
  local dest="$1"
  shift
  local skip_list=" $* "
  mkdir -p "$dest"
  local cmds=(bash sh env sed tr sort awk grep printf cat head tail cut wc dirname basename readlink realpath mktemp rm mkdir cp mv ls unzip)
  for cmd in "${cmds[@]}"; do
    if [[ "$skip_list" == *" $cmd "* ]]; then
      continue
    fi
    if p="$(type -P "$cmd" 2>/dev/null)"; then
      ln -sf "$p" "$dest/$cmd"
    fi
  done
}

NO_UNZIP_BIN="$WORK_DIR/no-unzip-bin"
build_filtered_bin "$NO_UNZIP_BIN" unzip zipinfo
if PATH="$NO_UNZIP_BIN" command -v unzip >/dev/null 2>&1; then
  echo "Setup failed: unzip still present in NO_UNZIP_BIN" >&2
  exit 1
fi

NO_AWK_BIN="$WORK_DIR/no-awk-bin"
build_filtered_bin "$NO_AWK_BIN" awk gawk mawk nawk
if PATH="$NO_AWK_BIN" command -v awk >/dev/null 2>&1; then
  echo "Setup failed: awk still present in NO_AWK_BIN" >&2
  exit 1
fi

# Generate deterministic ZIP/APK fixtures
FIXTURES="$WORK_DIR/fixtures"
mkdir -p "$FIXTURES" "$FIXTURES/dir with spaces" "$FIXTURES/dash_dir"
python3 - "$FIXTURES" <<'PY'
import os
import sys
import zipfile

root = sys.argv[1]

def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as zf:
        for name in entries:
            zf.writestr(name, b"data")

# 1. Clean APK
write_zip(os.path.join(root, "clean.apk"), [
    "AndroidManifest.xml",
    "minemaps/atlas.png",
    "minemaps/atlas.json",
    "assets/minemaps/packs/original/atlas.png",
])

# 2. Clean APK in directory with spaces + 2,500 entries (>64 KiB pipe buffer)
many_clean = ["AndroidManifest.xml"] + [f"res/raw/entry_{i:04d}_padding_name.bin" for i in range(2500)]
write_zip(os.path.join(root, "dir with spaces", "clean app.apk"), many_clean)

# 3. Clean APK with leading dash filename
write_zip(os.path.join(root, "dash_dir", "-dash.apk"), [
    "AndroidManifest.xml",
    "minemaps/atlas.png",
])

# 4. Clean APK with special characters (spaces, UTF-8, regex metachars)
write_zip(os.path.join(root, "special_clean.apk"), [
    "AndroidManifest.xml",
    "assets/my file [a+b]*(1).txt",
    "assets/utf8_café_🗺️.txt",
    "minemaps/packs/original/atlas.png",
])

# 5. Vanilla under assets/
write_zip(os.path.join(root, "vanilla_assets.apk"), [
    "AndroidManifest.xml",
    "assets/minemaps/packs/vanilla/atlas.png",
])

# 6. Vanilla at minemaps/packs/vanilla on line 1 + 2,500 entries (SIGPIPE regression guard)
many_vanilla = ["minemaps/packs/vanilla/atlas.png"] + [f"res/raw/entry_{i:04d}_padding_name.bin" for i in range(2500)]
write_zip(os.path.join(root, "vanilla_resources_many.apk"), many_vanilla)

# 7. Vanilla with backslashes
write_zip(os.path.join(root, "vanilla_backslash.apk"), [
    "AndroidManifest.xml",
    r"minemaps\packs\vanilla\atlas.png",
])

# 8. Vanilla with mixed case
write_zip(os.path.join(root, "vanilla_mixed_case.apk"), [
    "AndroidManifest.xml",
    "minemaps/packs/Vanilla/atlas.png",
])

# 9. Prepended 32 junk bytes + vanilla zip
with open(os.path.join(root, "vanilla_assets.apk"), "rb") as src:
    vanilla_bytes = src.read()
with open(os.path.join(root, "prepended_vanilla.apk"), "wb") as dst:
    dst.write(b"JUNK" * 8 + vanilla_bytes)

# 10. Prepended 32 junk bytes + clean zip
with open(os.path.join(root, "clean.apk"), "rb") as src:
    clean_bytes = src.read()
with open(os.path.join(root, "prepended_clean.apk"), "wb") as dst:
    dst.write(b"JUNK" * 8 + clean_bytes)

# 11. Empty zip (0 entries)
write_zip(os.path.join(root, "empty.apk"), [])

# 12. Corrupt non-zip file
with open(os.path.join(root, "corrupt.apk"), "wb") as dst:
    dst.write(b"not a zip file at all")
PY

PASS_COUNT=0
FAIL_COUNT=0

run_case() {
  local mode="$1"      # pass | fail
  local expected_rc="$2" # "" for any non-zero, or specific integer
  local name="$3"
  local work_cwd="$4"
  shift 4

  local out=""
  local rc=0
  set +e
  if [ -n "$work_cwd" ]; then
    out=$(cd "$work_cwd" && env -i "$@" 2>&1)
    rc=$?
  else
    out=$(env -i "$@" 2>&1)
    rc=$?
  fi
  set -e

  if [ "$mode" = "pass" ]; then
    if [ "$rc" -eq 0 ] && printf '%s\n' "$out" | grep -Fq "no vanilla pack entries OK"; then
      PASS_COUNT=$((PASS_COUNT + 1))
      printf '[PASS] %s\n' "$name"
      return 0
    fi
    FAIL_COUNT=$((FAIL_COUNT + 1))
    printf '[FAIL] %s (expected rc=0 + OK marker, got rc=%d)\n%s\n' "$name" "$rc" "$out" >&2
    return 1
  else
    local rc_ok=0
    if [ -n "$expected_rc" ]; then
      [ "$rc" -eq "$expected_rc" ] && rc_ok=1
    else
      [ "$rc" -ne 0 ] && rc_ok=1
    fi
    if [ "$rc_ok" -eq 1 ] && ! printf '%s\n' "$out" | grep -Fq "no vanilla pack entries OK"; then
      PASS_COUNT=$((PASS_COUNT + 1))
      printf '[PASS] %s (rc=%d)\n' "$name" "$rc"
      return 0
    fi
    FAIL_COUNT=$((FAIL_COUNT + 1))
    printf '[FAIL] %s (expected rc=%s without OK marker, got rc=%d)\n%s\n' "$name" "${expected_rc:-!=0}" "$rc" "$out" >&2
    return 1
  fi
}

# Keep running all cases even if one fails so the full matrix is reported
set +e

# --- Passing Cases ---
run_case pass 0 "pass_clean_apk_lowercase_cert" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case pass 0 "pass_clean_apk_colon_uppercase_cert_v1_crlf" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" MOCK_APKSIGNER_FORMAT=v1 MOCK_AAPT2_FORMAT=crlf \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT_COLON_UPPER"

run_case pass 0 "pass_clean_apk_spaces_and_2500_entries" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/dir with spaces/clean app.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case pass 0 "pass_clean_apk_leading_dash" "$FIXTURES/dash_dir" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" -dash.apk "$DEFAULT_VER" "$DEFAULT_CERT"

run_case pass 0 "pass_clean_apk_special_filenames" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/special_clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

# --- Failing Vanilla Pack Cases (Real unzip) ---
run_case fail 1 "fail_vanilla_assets_real_unzip" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/vanilla_assets.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 1 "fail_vanilla_resources_2500_entries_sigpipe_guard" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/vanilla_resources_many.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 1 "fail_vanilla_backslash_real_unzip" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/vanilla_backslash.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 1 "fail_vanilla_mixed_case_real_unzip" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/vanilla_mixed_case.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

# --- Prepended / Empty / Corrupt Archive Cases (Real unzip) ---
run_case fail "" "fail_prepended_bytes_vanilla_real_unzip" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/prepended_vanilla.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail "" "fail_prepended_bytes_clean_real_unzip" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/prepended_clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail "" "fail_empty_zip_real_unzip" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/empty.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail "" "fail_corrupt_non_zip_real_unzip" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/corrupt.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

# --- Missing unzip / awk Cases ---
run_case fail 127 "fail_missing_unzip_clean_apk" "" \
  LC_ALL=C PATH="$NO_UNZIP_BIN" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 127 "fail_missing_unzip_vanilla_apk" "" \
  LC_ALL=C PATH="$NO_UNZIP_BIN" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/vanilla_assets.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 127 "fail_missing_awk_clean_apk" "" \
  LC_ALL=C PATH="$NO_AWK_BIN" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

# --- Controlled Mock unzip Non-Zero Exits ---
run_case fail 1 "fail_mock_unzip_rc1_clean_stdout" "" \
  LC_ALL=C PATH="$MOCK_UNZIP_BIN:$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  MOCK_UNZIP_RC=1 MOCK_UNZIP_STDOUT='AndroidManifest.xml\nminemaps/atlas.png\n' \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 2 "fail_mock_unzip_rc2_clean_stdout" "" \
  LC_ALL=C PATH="$MOCK_UNZIP_BIN:$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  MOCK_UNZIP_RC=2 MOCK_UNZIP_STDOUT='AndroidManifest.xml\n' MOCK_UNZIP_STDERR='zip error\n' \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 9 "fail_mock_unzip_rc9_empty_stdout" "" \
  LC_ALL=C PATH="$MOCK_UNZIP_BIN:$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  MOCK_UNZIP_RC=9 MOCK_UNZIP_STDOUT='' MOCK_UNZIP_STDERR='cannot find zipfile\n' \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail 1 "fail_mock_unzip_rc1_vanilla_stdout" "" \
  LC_ALL=C PATH="$MOCK_UNZIP_BIN:$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  MOCK_UNZIP_RC=1 MOCK_UNZIP_STDOUT='assets/minemaps/packs/vanilla/atlas.png\n' \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

# --- Argument, Env, Cert, Version & Signer/aapt2 Failure Cases ---
run_case fail "" "fail_missing_args" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER"

run_case fail "" "fail_empty_apk_arg" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail "" "fail_empty_version_arg_with_empty_aapt2" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" MOCK_AAPT2_FORMAT=empty \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "" "$DEFAULT_CERT"

run_case fail "" "fail_empty_cert_arg_with_empty_apksigner" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" MOCK_APKSIGNER_FORMAT=empty \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" ""

run_case fail "" "fail_colons_only_cert_arg_with_empty_apksigner" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" MOCK_APKSIGNER_FORMAT=empty \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" ":::"

run_case fail "" "fail_non_64_hex_cert" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "deadbeef"

run_case fail "" "fail_unset_android_home" "" \
  LC_ALL=C PATH="$SYS_PATH" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail "" "fail_cert_mismatch" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$WRONG_CERT"

run_case fail "" "fail_version_mismatch" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "9.9.9" "$DEFAULT_CERT"

run_case fail "" "fail_nonexistent_apk" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" \
  "$VERIFY_APK" "$FIXTURES/does_not_exist.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail "" "fail_apksigner_nonzero" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" MOCK_APKSIGNER_RC=1 \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

run_case fail "" "fail_aapt2_nonzero" "" \
  LC_ALL=C PATH="$SYS_PATH" ANDROID_HOME="$MOCK_SDK" MOCK_AAPT2_RC=1 \
  "$VERIFY_APK" "$FIXTURES/clean.apk" "$DEFAULT_VER" "$DEFAULT_CERT"

set -e

echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
if [ "$FAIL_COUNT" -ne 0 ]; then
  exit 1
fi
