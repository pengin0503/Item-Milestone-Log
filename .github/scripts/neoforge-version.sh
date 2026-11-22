#!/usr/bin/env bash
# Picks the NeoForge version of a CI job from versions/<mc>/gradle.properties.
#
#   neoforge-version.sh <mc_version> <min|latest>
#
# Writes to $GITHUB_OUTPUT (when set):
#   version  the NeoForge version of the job: neo_version (min) or neo_version_latest (latest)
#   min      neo_version, which the release jar is compiled against
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <mc_version> <min|latest>" >&2
  exit 2
fi
MC="$1"
WHICH="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROPS="$ROOT/versions/$MC/gradle.properties"

# Empty when the file or the property is missing; the check below reports it.
prop() { grep -E "^$1=" "$PROPS" 2>/dev/null | cut -d= -f2- || true; }

min="$(prop neo_version)"
case "$WHICH" in
  min)    version="$min" ;;
  latest) version="$(prop neo_version_latest)" ;;
  *)      echo "usage: $0 <mc_version> <min|latest>" >&2; exit 2 ;;
esac
if [ -z "$min" ] || [ -z "$version" ]; then
  echo "No NeoForge version for MC $MC ($WHICH) in ${PROPS#$ROOT/}" >&2
  exit 1
fi

echo "NeoForge $version (release jar compiled against $min)"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "version=$version" >> "$GITHUB_OUTPUT"
  echo "min=$min" >> "$GITHUB_OUTPUT"
fi
