#!/usr/bin/env bash
# Assembles the merged 26.x release jar from the three per-version release jars
# (N251, docs/DESIGN_1.3.0.md section 3.3).
#
#   tools/merge-26x-jar.sh <base_jar_26_1_2> <jar_26_2> <jar_26_3> <output_jar>
#
# Optional environment variables:
#   MC_RANGE   Minecraft dependency versionRange written to META-INF/neoforge.mods.toml
#              (default: "[26.1.2,26.3]")
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  tools/merge-26x-jar.sh <base_jar_26_1_2> <jar_26_2> <jar_26_3> <output_jar>

Optional env:
  MC_RANGE   Minecraft versionRange for META-INF/neoforge.mods.toml (default: "[26.1.2,26.3]")
EOF
}

if [[ $# -ne 4 ]]; then
  usage
  exit 2
fi

base_jar="$1"
jar_26_2="$2"
jar_26_3="$3"
output_jar="$4"
MC_RANGE="${MC_RANGE:-[26.1.2,26.3]}"

for jar in "$base_jar" "$jar_26_2" "$jar_26_3"; do
  if [[ ! -f "$jar" ]]; then
    echo "  FAIL  input jar not found: $jar" >&2
    exit 1
  fi
done

abs_path() {
  if [[ "$1" == /* ]]; then
    printf '%s\n' "$1"
  else
    printf '%s/%s\n' "$PWD" "$1"
  fi
}

abs_base_jar="$(abs_path "$base_jar")"
abs_jar_26_2="$(abs_path "$jar_26_2")"
abs_jar_26_3="$(abs_path "$jar_26_3")"
abs_output="$(abs_path "$output_jar")"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
outdir="$workdir/out"
mkdir -p "$outdir"

# Extract the base 26.1.2 jar in full.
unzip -q "$abs_base_jar" -d "$outdir"
echo "  PASS  extracted base jar: $base_jar"

required_compat_entries=(
  'ModTriggers.class'
  'MilestoneRecordedTrigger.class'
  'MilestoneRecordedTrigger$TriggerInstance.class'
  'TriggerCompatImpl.class'
  'TagEventCompatImpl.class'
  'ConfigCompatImpl.class'
  'client/ScreenCompatImpl.class'
)

overlay_compat() {
  local overlay_jar="$1"
  local display_jar="$2"
  local suffix="$3"
  local prefix="com/pengin0503/itemmilestonelog/compat/${suffix}/"
  local listing
  local status=0

  listing="$(unzip -Z1 "$overlay_jar")"
  for rel in "${required_compat_entries[@]}"; do
    local entry="${prefix}${rel}"
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
}

overlay_compat "$abs_jar_26_2" "$jar_26_2" "v26_2"
overlay_compat "$abs_jar_26_3" "$jar_26_3" "v26_3"

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
