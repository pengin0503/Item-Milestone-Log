#!/usr/bin/env bash
# Prepares a throwaway 26.x verification harness: versions/verify/<mc> as a copy of the
# real release project versions/<mc> (tools/new-version-project.sh --from, which also
# fixes the ../../common paths), with the same NeoForge pins as the release project.
#
# Usage:
#   tools/prepare-26x-harness.sh <mc>            # e.g. 26.3
#   IML_SMOKETEST=1 tools/prepare-26x-harness.sh 26.3
#
# With IML_SMOKETEST=1 the Layer-2 client smoke hook is injected
# (tools/smoketest/inject.sh, docs/CLIENT_SMOKE_TEST.md). The release project
# versions/<mc> itself is never modified.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <mc>" >&2
  exit 2
fi

mc="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
src="$ROOT/versions/$mc"
dest="$ROOT/versions/verify/$mc"

if [[ "$mc" != 26.* || ! -f "$src/gradle.properties" ]]; then
  echo "Error: not a 26.x release project: versions/$mc" >&2
  exit 1
fi

prop() { grep -E "^$1=" "$src/gradle.properties" | head -1 | cut -d= -f2-; }
neo="$(prop neo_version)"
neo_latest="$(prop neo_version_latest)"
neo_latest="${neo_latest:-$neo}"

rm -rf "$dest"
"$ROOT/tools/new-version-project.sh" "$mc" "$neo" "$neo_latest" --from "versions/$mc"

if [[ "${IML_SMOKETEST:-0}" == "1" ]]; then
  "$ROOT/tools/smoketest/inject.sh" "$dest"
fi

echo "Harness project ($mc, neo $neo): $dest"
