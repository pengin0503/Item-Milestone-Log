#!/usr/bin/env bash
# Verifies that classes outside the per-version compat packages
# (com/pengin0503/itemmilestonelog/compat/v26_*/) do not reference
# version-specific binary names (docs/DESIGN_1.3.0.md section 3.2).
#
#   .github/scripts/check-merged-jar-bytecode.sh <merged_jar>
set -euo pipefail

usage() {
  echo "Usage: .github/scripts/check-merged-jar-bytecode.sh <merged_jar>" >&2
}

if [[ $# -ne 1 ]]; then
  usage
  exit 2
fi

JAR="$1"
if [[ ! -f "$JAR" ]]; then
  echo "  FAIL  jar not found: $JAR" >&2
  exit 1
fi

if ! command -v javap >/dev/null 2>&1; then
  echo "  FAIL  javap not found on PATH" >&2
  exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

unzip -q "$JAR" '*.class' -d "$workdir"

forbidden_patterns=(
  'net/minecraft/advancements/criterion/'
  'net/minecraft/advancements/triggers/'
  'net/minecraft/advancements/predicates/'
  'TagsUpdatedEvent$UpdateCause'
  'getUpdateCause'
  'getSlotUnderMouse'
  'getHoveredSlot'
  'net/minecraft/client/Minecraft.screen:'
  'net/minecraft/client/Minecraft.setScreen'
  'ModConfig$Type.LOCAL'
  'ModConfig$Type.COMMON'
)

mapfile -t classes < <(cd "$workdir" && find . -type f -name '*.class' | sed 's|^\./||' | LC_ALL=C sort)

status=0
checked=0

# Performance note: the merged jar has ~200 classes outside compat/v26_*, so
# invoking javap once per class is acceptable in CI.
for rel_path in "${classes[@]}"; do
  if [[ "$rel_path" == com/pengin0503/itemmilestonelog/compat/v26_* ]]; then
    continue
  fi

  checked=$((checked + 1))
  if ! javap_out="$(javap -v -p "$workdir/$rel_path")"; then
    echo "  FAIL  javap failed on $rel_path"
    status=1
    continue
  fi

  for pattern in "${forbidden_patterns[@]}"; do
    if grep -qF -- "$pattern" <<< "$javap_out"; then
      echo "  FAIL  $rel_path references $pattern"
      status=1
    fi
  done
done

if [[ "$checked" -eq 0 ]]; then
  echo "  FAIL  no common classes found outside com/pengin0503/itemmilestonelog/compat/v26_*"
  status=1
fi

if [[ "$status" -eq 0 ]]; then
  echo "  PASS  bytecode check passed ($checked classes checked)"
else
  echo "  FAIL  bytecode check failed ($checked classes checked)"
fi

exit "$status"
