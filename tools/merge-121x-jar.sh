#!/usr/bin/env bash
# Assembles the MERGED 1.21.x release jars (Jar A and Jar B1, docs/VERSION_MATRIX.md,
# docs/DESIGN_1.3.0.md section 3) from a base profile jar plus overlay profile jars
# built by tools/build-121x-profile-jar.sh. Jar B2 and Jar C are single-profile jars:
# CI also runs them through this script, with zero overlay arguments, to perform the
# mods.toml range rewrite.
#
#   tools/merge-121x-jar.sh <base_jar> <mc_range> <output_jar> [<overlay_jar>:<suffix> ...]
#
# For each overlay, the compat subtrees compat/<suffix>/ and compat/client/<suffix>/
# are copied in, plus the compiled classes of every file override under
# versions/1.21.1/compat-variants/<suffix>/overrides/. For an overridden source,
# the base jar's outer and inner class files are removed first, so an old anonymous
# class from the base profile cannot survive next to the override's classes.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  tools/merge-121x-jar.sh <base_jar> <mc_range> <output_jar> [<overlay_jar>:<suffix> ...]

Example (Jar A):
  tools/merge-121x-jar.sh build/v121_1.jar '[1.21,1.21.4]' \
    build/itemmilestonelog-neoforge-1.21-1.21.4-1.3.0.jar \
    build/v121_2.jar:v121_2 build/v121_4.jar:v121_4

Example (Jar B1):
  tools/merge-121x-jar.sh build/v121_5.jar '[1.21.5,1.21.8]' \
    build/itemmilestonelog-neoforge-1.21.5-1.21.8-1.3.0.jar \
    build/v121_6.jar:v121_6 build/v121_7.jar:v121_7 build/v121_8.jar:v121_8
EOF
}

if [[ $# -lt 3 ]]; then
  usage
  exit 2
fi

base_jar="$1"
MC_RANGE="$2"
output_jar="$3"
shift 3
overlay_args=("$@")

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

abs_path() {
  if [[ "$1" == /* ]]; then
    printf '%s\n' "$1"
  else
    printf '%s/%s\n' "$PWD" "$1"
  fi
}

if [[ -z "$MC_RANGE" ]]; then
  echo "  FAIL  <mc_range> must not be empty" >&2
  exit 2
fi

if [[ ! -f "$base_jar" ]]; then
  echo "  FAIL  input jar not found: $base_jar" >&2
  exit 1
fi

overlay_jars=()
overlay_suffixes=()
for arg in "${overlay_args[@]}"; do
  if [[ "$arg" != *:* ]]; then
    echo "  FAIL  overlay argument must be <overlay_jar>:<suffix>: $arg" >&2
    exit 2
  fi
  jar="${arg%:*}"
  suffix="${arg##*:}"
  if [[ -z "$jar" || -z "$suffix" ]]; then
    echo "  FAIL  overlay argument must be <overlay_jar>:<suffix>: $arg" >&2
    exit 2
  fi
  if [[ ! -f "$jar" ]]; then
    echo "  FAIL  input jar not found: $jar" >&2
    exit 1
  fi
  overlay_jars+=("$jar")
  overlay_suffixes+=("$suffix")
done

abs_base_jar="$(abs_path "$base_jar")"
abs_output="$(abs_path "$output_jar")"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
outdir="$workdir/out"
mkdir -p "$outdir"

# Extract the base profile jar in full (shared classes + base compat subtree).
unzip -q "$abs_base_jar" -d "$outdir"
echo "  PASS  extracted base jar: $base_jar"

required_compat_entries=(
  'ChatCompatImpl.class'
  'DataCompatImpl.class'
  'GameTestCompatImpl.class'
  'MilestoneRecordedTrigger.class'
  'MilestoneRecordedTrigger$TriggerInstance.class'
  'ModTriggers.class'
  'NbtCompatImpl.class'
  'PermissionCompatImpl.class'
  'PlayerCompatImpl.class'
  'RegistryCompatImpl.class'
  'ReloadCompatImpl.class'
  'TriggerCompatImpl.class'
)

required_client_compat_entries=(
  'ListCompatImpl.class'
  'NetworkCompatImpl.class'
  'ScrollCompatImpl.class'
  'SoundCompatImpl.class'
  'TextCompatImpl.class'
  'TooltipCompatImpl.class'
)

overlay_profile() {
  local overlay_jar="$1"
  local display_jar="$2"
  local suffix="$3"
  local prefix="com/pengin0503/itemmilestonelog/compat/${suffix}/"
  local client_prefix="com/pengin0503/itemmilestonelog/compat/client/${suffix}/"
  local listing
  local status=0

  listing="$(unzip -Z1 "$overlay_jar")"

  # a. Compat subtrees.
  for rel in "${required_compat_entries[@]}"; do
    local entry="${prefix}${rel}"
    if ! grep -qxF "$entry" <<< "$listing"; then
      echo "  FAIL  $display_jar is missing required entry: $entry" >&2
      status=1
    fi
  done
  for rel in "${required_client_compat_entries[@]}"; do
    local entry="${client_prefix}${rel}"
    if ! grep -qxF "$entry" <<< "$listing"; then
      echo "  FAIL  $display_jar is missing required entry: $entry" >&2
      status=1
    fi
  done
  if [[ "$status" -ne 0 ]]; then
    exit 1
  fi

  unzip -q -o "$overlay_jar" "${prefix}*" -d "$outdir"
  echo "  PASS  overlaid ${prefix} from $display_jar"
  unzip -q -o "$overlay_jar" "${client_prefix}*" -d "$outdir"
  echo "  PASS  overlaid ${client_prefix} from $display_jar"

  # b. File overrides: the compiled classes of each override source (outer class
  #    plus its inner/anonymous classes) replace the base copies.
  local overrides="$ROOT/versions/1.21.1/compat-variants/${suffix}/overrides"
  if [[ ! -d "$overrides" ]]; then
    return 0
  fi

  local -a override_sources=()
  mapfile -t override_sources < <(cd "$overrides" && find . -type f -name '*.java' | sed 's|^\./||' | LC_ALL=C sort)

  local src
  for src in "${override_sources[@]}"; do
    local class_path="${src%.java}"
    local -a matches=()
    mapfile -t matches < <(awk -v outer="${class_path}.class" -v inner="${class_path}\$" '
      $0 == outer || (index($0, inner) == 1 && $0 ~ /\.class$/) { print }
    ' <<< "$listing")
    if [[ ${#matches[@]} -eq 0 ]]; then
      echo "  FAIL  $display_jar has no class entry for override source: overrides/$src" >&2
      status=1
      continue
    fi
    # Remove the base outer/inner class files for this source before extracting the
    # override: the overlay may have fewer or differently numbered anonymous classes
    # (e.g. base Foo$2.class, override Foo$1.class), and old bytecode must not remain.
    rm -f "$outdir/${class_path}.class" "$outdir/${class_path}"'$'*.class
    unzip -q -o "$overlay_jar" "${matches[@]}" -d "$outdir"
    echo "  PASS  overlaid override ${class_path} (${#matches[@]} class entries) from $display_jar"
  done
  if [[ "$status" -ne 0 ]]; then
    exit 1
  fi
}

for i in "${!overlay_jars[@]}"; do
  overlay_profile "$(abs_path "${overlay_jars[$i]}")" "${overlay_jars[$i]}" "${overlay_suffixes[$i]}"
done

# Rewrite the minecraft dependency versionRange in META-INF/neoforge.mods.toml
# while leaving the neoforge dependency block untouched.
toml_file="$outdir/META-INF/neoforge.mods.toml"
if [[ ! -f "$toml_file" ]]; then
  echo "  FAIL  base jar is missing META-INF/neoforge.mods.toml" >&2
  exit 1
fi

toml_tmp="$workdir/neoforge.mods.toml.tmp"
if ! MC_RANGE="$MC_RANGE" awk '
  BEGIN {
    mc_range = ENVIRON["MC_RANGE"]
    in_mc = 0
    replaced = 0
  }
  /^[[:space:]]*\[/ {
    in_mc = 0
  }
  /^[[:space:]]*modId[[:space:]]*=[[:space:]]*"minecraft"[[:space:]]*$/ {
    in_mc = 1
  }
  in_mc && !replaced && /^[[:space:]]*versionRange[[:space:]]*=[[:space:]]*"[^"]*"/ {
    match($0, /versionRange[[:space:]]*=[[:space:]]*"[^"]*"/)
    print substr($0, 1, RSTART - 1) "versionRange=\"" mc_range "\"" substr($0, RSTART + RLENGTH)
    replaced = 1
    in_mc = 0
    next
  }
  { print }
  END {
    if (!replaced) exit 1
  }
' "$toml_file" > "$toml_tmp"; then
  echo "  FAIL  could not replace minecraft versionRange in META-INF/neoforge.mods.toml" >&2
  exit 1
fi
mv "$toml_tmp" "$toml_file"

if ! grep -qF "versionRange=\"$MC_RANGE\"" "$toml_file"; then
  echo "  FAIL  expected versionRange=\"$MC_RANGE\" not found in META-INF/neoforge.mods.toml" >&2
  exit 1
fi
echo "  PASS  updated minecraft versionRange=\"$MC_RANGE\" in META-INF/neoforge.mods.toml"

mkdir -p "$(dirname "$abs_output")"
rm -f "$abs_output"
(cd "$outdir" && zip -q -r "$abs_output" .)

size_bytes="$(stat -c %s "$abs_output")"
class_count="$(unzip -Z1 "$abs_output" | grep -c '\.class$' || true)"
echo "Merged jar: $output_jar ($size_bytes bytes, $class_count classes)"
