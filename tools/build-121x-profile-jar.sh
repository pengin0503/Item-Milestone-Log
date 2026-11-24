#!/usr/bin/env bash
# Builds one 1.21.x compat-profile jar from a generated verify project
# (N252 stage 2c, docs/VERSION_MATRIX.md, docs/DESIGN_1.3.0.md section 3).
#
#   tools/build-121x-profile-jar.sh <mc> <profile> <neo_version> <output_jar>
#
# Regenerates versions/verify/<mc> with tools/prepare-121x-harness.sh, runs the
# project's `jar` task, and copies the single produced jar to <output_jar>.
#
# Optional environment variables:
#   MC_RANGE   When set, rewrite the minecraft dependency versionRange inside the
#              output jar's META-INF/neoforge.mods.toml to this value. When unset,
#              the jar is left untouched.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  tools/build-121x-profile-jar.sh <mc> <profile> <neo_version> <output_jar>

Example:
  tools/build-121x-profile-jar.sh 1.21.4 v121_4 21.4.121 build/v121_4.jar

Optional env:
  MC_RANGE   Minecraft versionRange to write into META-INF/neoforge.mods.toml
             (default: unset, jar left untouched)
EOF
}

if [[ $# -ne 4 ]]; then
  usage
  exit 2
fi

mc="$1"
profile="$2"
neo_version="$3"
output_jar="$4"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
dest="$ROOT/versions/verify/$mc"

abs_path() {
  if [[ "$1" == /* ]]; then
    printf '%s\n' "$1"
  else
    printf '%s/%s\n' "$PWD" "$1"
  fi
}

abs_output="$(abs_path "$output_jar")"

# 1. Regenerate the verify project for this profile (from scratch).
"$ROOT/tools/prepare-121x-harness.sh" "$mc" "$profile" "$neo_version"
echo "  PASS  prepared harness project: versions/verify/$mc ($profile)"

# 2. Build the jar.
(cd "$dest" && ../../../gradlew jar --console=plain)
echo "  PASS  built jar task in versions/verify/$mc"

# 3. Locate exactly one produced jar and copy it to the output path.
shopt -s nullglob
built_jars=("$dest"/build/libs/*.jar)
shopt -u nullglob
if [[ ${#built_jars[@]} -eq 0 ]]; then
  echo "  FAIL  no jar produced under versions/verify/$mc/build/libs/" >&2
  exit 1
fi
if [[ ${#built_jars[@]} -gt 1 ]]; then
  echo "  FAIL  expected exactly one jar under versions/verify/$mc/build/libs/, found ${#built_jars[@]}:" >&2
  printf '        %s\n' "${built_jars[@]}" >&2
  exit 1
fi

mkdir -p "$(dirname "$abs_output")"
cp "${built_jars[0]}" "$abs_output"
echo "  PASS  copied ${built_jars[0]#$ROOT/} -> $output_jar"

# 4. Optional: rewrite the minecraft dependency versionRange inside the jar.
if [[ -n "${MC_RANGE:-}" ]]; then
  workdir="$(mktemp -d)"
  trap 'rm -rf "$workdir"' EXIT

  if ! unzip -q "$abs_output" META-INF/neoforge.mods.toml -d "$workdir"; then
    echo "  FAIL  output jar is missing META-INF/neoforge.mods.toml" >&2
    exit 1
  fi
  toml_file="$workdir/META-INF/neoforge.mods.toml"
  toml_tmp="$workdir/neoforge.mods.toml.tmp"

  # Same rewrite as tools/merge-26x-jar.sh: only the minecraft dependency block
  # is touched; the neoforge dependency block is left as-is.
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

  # Update the jar entry in place.
  (cd "$workdir" && zip -q "$abs_output" META-INF/neoforge.mods.toml)

  if ! unzip -p "$abs_output" META-INF/neoforge.mods.toml | grep -qF "versionRange=\"$MC_RANGE\""; then
    echo "  FAIL  expected versionRange=\"$MC_RANGE\" not found in $output_jar META-INF/neoforge.mods.toml" >&2
    exit 1
  fi
  echo "  PASS  updated minecraft versionRange=\"$MC_RANGE\" in META-INF/neoforge.mods.toml"
fi

size_bytes="$(stat -c %s "$abs_output")"
class_count="$(unzip -Z1 "$abs_output" | grep -c '\.class$' || true)"
echo "Profile jar: $output_jar ($mc, $profile, $size_bytes bytes, $class_count classes)"
