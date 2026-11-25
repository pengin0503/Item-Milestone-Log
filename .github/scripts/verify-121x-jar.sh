#!/usr/bin/env bash
# Checks the contents, mods.toml minecraft range, duplicate entries, and bytecode
# isolation of a 1.21.x mod jar (merged A/B1, single-profile B2/C) (N252,
# docs/VERSION_MATRIX.md, docs/DESIGN_1.3.0.md section 3).
#
#   .github/scripts/verify-121x-jar.sh <jar> <segment>
#
# Segments (the former B [1.21.5, 1.21.10] is split at 1.21.9 because the client
# screens' Entry rendering forks there (render -> renderContent)):
#   A   MC [1.21, 1.21.4]    profiles v121_1, v121_2, v121_4
#   B1  MC [1.21.5, 1.21.8]  profiles v121_5, v121_6, v121_8
#   B2  MC [1.21.9, 1.21.10] profile v121_9 (client screen + gametest overrides)
#   C   MC [1.21.11]         profile v121_11 (ResourceLocation -> Identifier rename)
set -euo pipefail

usage() {
  echo "Usage: .github/scripts/verify-121x-jar.sh <jar> <A|B1|B2|C>" >&2
}

if [[ $# -ne 2 ]]; then
  usage
  exit 2
fi

JAR="$1"
SEGMENT="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

PKG="com/pengin0503/itemmilestonelog"

# Bytecode-check skips for classes replaced by per-profile file overrides
# (versions/1.21.1/compat-variants/<profile>/overrides/). Outer class paths
# without .class; inner classes (<path>$*.class) are skipped too.
override_skips=()

case "$SEGMENT" in
  A)
    expected_range='[1.21,1.21.4]'
    suffixes=(v121_1 v121_2 v121_4)
    forbidden_patterns=(
      'net/minecraft/resources/Identifier'
      'arguments/IdentifierArgument'
      'SavedDataType'
      'ClickEvent$RunCommand'
      'ClickEvent$SuggestCommand'
      'HoverEvent$ShowItem'
      'ClientPacketDistributor'
      'setTooltipForNextFrame'
      'renderContent'
      'NameAndId'
      'PermissionCheck'
    )
    ;;
  B1|B2)
    # Shared by B1 and B2; ";;&" then continues to the segment-specific branch.
    forbidden_patterns=(
      'net/minecraft/resources/Identifier'
      'arguments/IdentifierArgument'
      'SavedData$Factory'
      'ClickEvent$Action'
      'HoverEvent$Action'
      'GameTestHolder'
      'PrefixGameTestTemplate'
      'ServerPlayer.playNotifySound'
      'net/minecraft/server/players/NameAndId'
    )
    ;;&
  B1)
    expected_range='[1.21.5,1.21.8]'
    suffixes=(v121_5 v121_6 v121_8)
    # Only the compat/v121_* packages are skipped. B1's sole file override
    # (gametest registration, identical in v121_5/6/8) is already in 1.21.5+
    # form, so it is scanned like any other class.
    ;;
  B2)
    expected_range='[1.21.9,1.21.10]'
    suffixes=(v121_9)
    override_skips=(
      "$PKG/gametest/MilestoneGameTestRegistration"
      "$PKG/client/ItemPickerScreen"
      "$PKG/client/MilestoneScreen"
      "$PKG/client/PresetListScreen"
      "$PKG/client/ItemMilestoneLogClient"
    )
    ;;
  C)
    expected_range='[1.21.11,1.21.11]'
    suffixes=(v121_11)
    # The rename must be complete: every class is scanned, compat included.
    forbidden_patterns=(
      'net/minecraft/resources/ResourceLocation'
      'arguments/ResourceLocationArgument'
    )
    ;;
  *)
    echo "  FAIL  unknown segment: $SEGMENT (expected A, B1, B2, or C)" >&2
    usage
    exit 2
    ;;
esac

if [[ ! -f "$JAR" ]]; then
  echo "  FAIL  jar not found: $JAR" >&2
  exit 1
fi

echo "Jar: ${JAR#$ROOT/} ($(stat -c %s "$JAR") bytes, segment $SEGMENT)"

listing="$(unzip -Z1 "$JAR")"
toml="$(unzip -p "$JAR" META-INF/neoforge.mods.toml 2>/dev/null || true)"

status=0
expect_file() {
  if grep -qxF "$1" <<< "$listing"; then echo "  PASS  contains $1"; else echo "  FAIL  missing $1"; status=1; fi
}
expect_toml() {
  if grep -qF "$1" <<< "$toml"; then echo "  PASS  mods.toml has $1"; else echo "  FAIL  mods.toml lacks $1"; status=1; fi
}

# 1. Duplicate entries.
dups="$(sort <<< "$listing" | uniq -d)"
if [[ -z "$dups" ]]; then
  echo "  PASS  no duplicate jar entries"
else
  echo "  FAIL  duplicate jar entries found:"
  sed 's/^/        /' <<< "$dups"
  status=1
fi

# 2. Minecraft versionRange (read from the minecraft dependency block only).
expect_file META-INF/neoforge.mods.toml
mc_range="$(awk '
  /^[[:space:]]*\[/ { in_mc = 0 }
  /^[[:space:]]*modId[[:space:]]*=[[:space:]]*"minecraft"[[:space:]]*$/ { in_mc = 1; next }
  in_mc && /^[[:space:]]*versionRange[[:space:]]*=/ {
    match($0, /"[^"]*"/)
    print substr($0, RSTART + 1, RLENGTH - 2)
    exit
  }
' <<< "$toml")"
if [[ "$mc_range" == "$expected_range" ]]; then
  echo "  PASS  minecraft versionRange=\"$mc_range\""
else
  echo "  FAIL  minecraft versionRange is \"$mc_range\", expected \"$expected_range\""
  status=1
fi
expect_toml 'modId="itemmilestonelog"'

if grep -q '\${' <<< "$toml"; then
  echo "  FAIL  unexpanded \${...} placeholders in mods.toml"
  status=1
else
  echo "  PASS  no unexpanded \${...} placeholders in mods.toml"
fi

# 3. Required profile subtrees (one representative entry per subtree) and shared entries.
for suffix in "${suffixes[@]}"; do
  expect_file "$PKG/compat/${suffix}/DataCompatImpl.class"
  expect_file "$PKG/compat/client/${suffix}/TooltipCompatImpl.class"
done

expect_file logo.png
expect_file assets/itemmilestonelog/lang/en_us.json
expect_file assets/itemmilestonelog/lang/ja_jp.json
expect_file "$PKG/ItemMilestoneLog.class"
expect_file "$PKG/compat/McCompat.class"
expect_file "$PKG/compat/McVersion.class"
expect_file "$PKG/client/ItemMilestoneLogClient.class"

# 4. Bytecode isolation (segment-aware variant of check-merged-jar-bytecode.sh).
if ! command -v javap >/dev/null 2>&1; then
  echo "  FAIL  javap not found on PATH" >&2
  exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

unzip -q "$JAR" '*.class' -d "$workdir"

mapfile -t classes < <(cd "$workdir" && find . -type f -name '*.class' | sed 's|^\./||' | LC_ALL=C sort)

is_skipped() {
  local rel_path="$1"
  local suffix skip
  if [[ "$SEGMENT" == C ]]; then
    return 1
  fi
  for suffix in "${suffixes[@]}"; do
    if [[ "$rel_path" == "$PKG/compat/${suffix}/"* || "$rel_path" == "$PKG/compat/client/${suffix}/"* ]]; then
      return 0
    fi
  done
  for skip in "${override_skips[@]}"; do
    if [[ "$rel_path" == "${skip}.class" || "$rel_path" == "${skip}\$"*.class ]]; then
      return 0
    fi
  done
  return 1
}

checked=0
bytecode_status=0

# Performance note: invoking javap once per class is acceptable in CI (a few
# hundred classes at most).
for rel_path in "${classes[@]}"; do
  if is_skipped "$rel_path"; then
    continue
  fi

  checked=$((checked + 1))
  if ! javap_out="$(javap -v -p "$workdir/$rel_path")"; then
    echo "  FAIL  javap failed on $rel_path"
    bytecode_status=1
    continue
  fi

  for pattern in "${forbidden_patterns[@]}"; do
    if grep -qF -- "$pattern" <<< "$javap_out"; then
      echo "  FAIL  $rel_path references $pattern"
      bytecode_status=1
    fi
  done
done

if [[ "$checked" -eq 0 ]]; then
  echo "  FAIL  no classes checked (segment $SEGMENT)"
  bytecode_status=1
fi

if [[ "$bytecode_status" -eq 0 ]]; then
  echo "  PASS  bytecode check passed (segment $SEGMENT, $checked classes checked)"
else
  echo "  FAIL  bytecode check failed (segment $SEGMENT, $checked classes checked)"
  status=1
fi

echo "::group::META-INF/neoforge.mods.toml"
grep -vE '^\s*(#|$)' <<< "$toml" || true
echo "::endgroup::"
exit "$status"
