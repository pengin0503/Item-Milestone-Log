#!/usr/bin/env bash
# Generates a throwaway verification project for a target Minecraft version
# from an existing base version project (N150).
#
# Generated projects live under versions/verify/<mc>/ and are intended only for
# compilation, unit testing, and API diff inspection. They are NOT release
# projects and must not be committed (versions/verify/ is expected to be
# gitignored).
#
# Usage:
#   tools/new-version-project.sh <mc> <neo_min> <neo_latest> \
#     [--from <base_dir>] [--parchment-mc <ver>] [--parchment <ver>]
#
# Options:
#   --from <base_dir>      Base project directory (default: versions/1.21.1,
#                          resolved relative to the repo root if not absolute)
#   --parchment-mc <ver>   Override parchment_minecraft_version (only if the key
#                          already exists in gradle.properties)
#   --parchment <ver>      Override parchment_mappings_version (only if the key
#                          already exists in gradle.properties)
#
# Example (MC 1.21.2; verify real NeoForge / Parchment versions before running):
#   tools/new-version-project.sh 1.21.2 21.2.0-beta 21.2.1-beta \
#     --from versions/1.21.1 --parchment-mc 1.21.1 --parchment 2024.11.17
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat >&2 <<'EOF'
Usage:
  tools/new-version-project.sh <mc> <neo_min> <neo_latest> [--from <base_dir>] [--parchment-mc <ver>] [--parchment <ver>]

Required arguments:
  <mc>          Target Minecraft version (e.g. 1.21.2)
  <neo_min>     Minimum NeoForge version (written to neo_version and neo_version_range)
  <neo_latest>  Latest NeoForge version (written to neo_version_latest)

Options:
  --from <base_dir>      Base project directory (default: versions/1.21.1)
  --parchment-mc <ver>   Override parchment_minecraft_version if present
  --parchment <ver>      Override parchment_mappings_version if present
EOF
}

base_arg="versions/1.21.1"
parchment_mc=""
parchment_mappings=""
positional=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --from)
      if [[ $# -lt 2 || -z "$2" ]]; then
        echo "Error: --from requires a non-empty value." >&2
        usage
        exit 2
      fi
      base_arg="$2"
      shift 2
      ;;
    --parchment-mc)
      if [[ $# -lt 2 || -z "$2" ]]; then
        echo "Error: --parchment-mc requires a non-empty value." >&2
        usage
        exit 2
      fi
      parchment_mc="$2"
      shift 2
      ;;
    --parchment)
      if [[ $# -lt 2 || -z "$2" ]]; then
        echo "Error: --parchment requires a non-empty value." >&2
        usage
        exit 2
      fi
      parchment_mappings="$2"
      shift 2
      ;;
    --)
      shift
      while [[ $# -gt 0 ]]; do
        positional+=("$1")
        shift
      done
      ;;
    -*)
      echo "Error: unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      positional+=("$1")
      shift
      ;;
  esac
done

if [[ ${#positional[@]} -ne 3 ]]; then
  echo "Error: expected 3 required arguments (<mc> <neo_min> <neo_latest>), got ${#positional[@]}." >&2
  usage
  exit 2
fi

mc="${positional[0]}"
neo_min="${positional[1]}"
neo_latest="${positional[2]}"

if [[ -z "$mc" || -z "$neo_min" || -z "$neo_latest" ]]; then
  echo "Error: <mc>, <neo_min>, and <neo_latest> must be non-empty." >&2
  usage
  exit 2
fi

if [[ "$mc" == */* || "$mc" == "." || "$mc" == ".." ]]; then
  echo "Error: invalid Minecraft version: $mc" >&2
  usage
  exit 2
fi

if [[ "$base_arg" == /* ]]; then
  base_dir="$base_arg"
else
  base_dir="$ROOT/$base_arg"
fi
base_display="${base_arg%/}"

if [[ ! -d "$base_dir" ]]; then
  echo "Error: base project directory does not exist: $base_dir" >&2
  exit 1
fi

if [[ ! -f "$base_dir/gradle.properties" ]]; then
  echo "Error: base project is missing gradle.properties: $base_dir/gradle.properties" >&2
  exit 1
fi

dest_dir="$ROOT/versions/verify/$mc"
if [[ -e "$dest_dir" ]]; then
  echo "Error: destination already exists: $dest_dir" >&2
  exit 1
fi

mkdir -p "$dest_dir"

tar -C "$base_dir" \
  --exclude='./build' \
  --exclude='./run' \
  --exclude='./logs' \
  --exclude='./.gradle' \
  -cf - . | tar --no-same-owner -C "$dest_dir" -xf -

# The base projects pull the repo-root shared sources via ../../common/...
# (they live at versions/<mc>/). Generated projects sit one level deeper
# (versions/verify/<mc>/), so those paths need one more ../ — otherwise
# Gradle silently drops the nonexistent srcDirs and every shared class
# (all *Logic classes, resources) vanishes from the verification build.
if [[ -f "$dest_dir/build.gradle" ]]; then
  sed -i "s|\.\./\.\./common/|../../../common/|g" "$dest_dir/build.gradle"
fi

props_file="$dest_dir/gradle.properties"

has_prop() {
  local file="$1"
  local key="$2"
  grep -qE "^${key}=" "$file"
}

set_prop() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp="${file}.tmp.$$"

  if ! PROP_KEY="$key" PROP_VAL="$value" awk '
    BEGIN {
      prefix = ENVIRON["PROP_KEY"] "="
      val = ENVIRON["PROP_VAL"]
      len = length(prefix)
      found = 0
    }
    substr($0, 1, len) == prefix {
      print prefix val
      found = 1
      next
    }
    { print }
    END {
      if (!found) exit 1
    }
  ' "$file" > "$tmp"; then
    rm -f "$tmp"
    echo "Error: required key '$key' is missing from $file" >&2
    exit 1
  fi

  mv "$tmp" "$file"
}

set_prop "$props_file" "minecraft_version" "$mc"
set_prop "$props_file" "minecraft_version_range" "[${mc}]"
set_prop "$props_file" "neo_version" "$neo_min"
set_prop "$props_file" "neo_version_range" "[${neo_min},)"
set_prop "$props_file" "neo_version_latest" "$neo_latest"

parchment_untouched=()
if has_prop "$props_file" "parchment_minecraft_version"; then
  if [[ -n "$parchment_mc" ]]; then
    set_prop "$props_file" "parchment_minecraft_version" "$parchment_mc"
  else
    parchment_untouched+=("parchment_minecraft_version")
  fi
fi

if has_prop "$props_file" "parchment_mappings_version"; then
  if [[ -n "$parchment_mappings" ]]; then
    set_prop "$props_file" "parchment_mappings_version" "$parchment_mappings"
  else
    parchment_untouched+=("parchment_mappings_version")
  fi
fi

if [[ ${#parchment_untouched[@]} -gt 0 ]]; then
  echo "Warning: ${parchment_untouched[*]} left at base project values in $props_file; check whether they match MC $mc." >&2
fi

cat <<EOF
Generated verification project: $dest_dir

Next steps:
  1. Build & unit-test: run Gradle \`test\` inside versions/verify/${mc}
     (standalone build, JDK 21 for 1.21.x / JDK 25 for 26.x).
  2. Diff the sources against the base project after adapting sources until it compiles, to catalogue API differences:
     diff -ru ${base_display}/src versions/verify/${mc}/src
  3. Record the result in docs/VERSION_MATRIX.md.
EOF
