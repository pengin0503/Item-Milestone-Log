#!/usr/bin/env bash
# N252 stage 2b: prepares a throwaway 1.21.x verification harness for one compat profile.
#
# Generates versions/verify/<mc> from versions/1.21.1 (N150 script), strips the Parchment
# block (1.21.1 mappings do not apply to other MC versions; same approach as
# verify-121x.sh), pins neo_version, and swaps the v121_1 compat implementations for the
# profile's variant tree under versions/1.21.1/compat-variants/<profile>/.
# The base profile v121_1 has no variant tree and no overrides: its impls are already in
# the generated sources (copied from versions/1.21.1), so steps 4 and 5 are skipped for it.
#
# Usage:
#   tools/prepare-121x-harness.sh <mc> <profile> <neo_version>
#   IML_SMOKETEST=1 tools/prepare-121x-harness.sh ...   # + Layer-2 client smoke hook
# Example:
#   tools/prepare-121x-harness.sh 1.21.4 v121_4 21.4.121
#
# Profiles (the McVersion mapping) and the harness/profile-jar build entries
# (<mc> <profile> <neo_version>; the profile jars use the first MC version of each):
#   1.21     v121_1   21.0.143        1.21.1   v121_1   21.1.1   (versions/1.21.1 itself)
#   1.21.2   v121_2   21.2.0-beta     1.21.3   v121_2   21.3.56
#   1.21.4   v121_4   21.4.121
#   1.21.5   v121_5   21.5.74
#   1.21.6   v121_6   21.6.0-beta
#   1.21.7   v121_7   21.7.0-beta     (reflective NetworkCompatImpl: NeoForge 21.7
#                                      moved sendToServer mid-line)
#   1.21.8   v121_8   21.8.9
#   1.21.9   v121_9   21.9.0-beta     1.21.10  v121_9   21.10.64
#   1.21.11  v121_11  21.11.42
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <mc> <profile> <neo_version>" >&2
  exit 2
fi

mc="$1"
profile="$2"
neo_version="$3"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
dest="$ROOT/versions/verify/$mc"
variant="$ROOT/versions/1.21.1/compat-variants/$profile/com/pengin0503/itemmilestonelog/compat"
pkg_dir="$dest/src/main/java/com/pengin0503/itemmilestonelog"

if [[ "$profile" != "v121_1" && ! -d "$variant" ]]; then
  echo "Error: variant tree not found: $variant" >&2
  exit 1
fi

# 1. (Re)generate the verify project. new-version-project.sh refuses an existing
#    destination and requires <neo_min> <neo_latest>; pass neo_version for both.
rm -rf "$dest"
"$ROOT/tools/new-version-project.sh" "$mc" "$neo_version" "$neo_version"

# 2. Strip the Parchment mappings block (same approach as verify-121x.sh).
python3 - "$dest/build.gradle" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
block = """    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }
"""
assert block in s, "parchment block not found"
open(p, "w").write(s.replace(block, ""))
print("parchment block removed:", p)
PYEOF

# 3. Pin neo_version (replace the existing line).
props="$dest/gradle.properties"
if ! grep -qE '^neo_version=' "$props"; then
  echo "Error: neo_version missing from $props" >&2
  exit 1
fi
sed -i "s|^neo_version=.*|neo_version=${neo_version}|" "$props"

if [[ "$profile" == "v121_1" ]]; then
  # 4-5. Base profile: compat/v121_1 and compat/client/v121_1 are already in the
  #      generated sources and there are no overrides; nothing to swap.
  echo "PASS: base profile v121_1 already in generated sources; variant swap and overrides skipped"
else
  # 4. Swap the compat implementations for the profile's variant tree.
  rm -rf "$pkg_dir/compat/v121_1" "$pkg_dir/compat/client/v121_1"
  cp -R "$variant/." "$pkg_dir/compat/"

  # 5. File overrides: some profiles need whole shared source files replaced (e.g. the
  #    GameTest registration, whose API changed completely in 1.21.5). Files under
  #    compat-variants/<profile>/overrides/ mirror src/main/java/ and replace the
  #    generated project's copies wholesale for that profile.
  overrides="$ROOT/versions/1.21.1/compat-variants/$profile/overrides"
  if [[ -d "$overrides" ]]; then
    cp -R "$overrides/." "$dest/src/main/java/"
    echo "File overrides applied from: $overrides"
  fi
fi

# 6. 1.21.11 only: ResourceLocation was renamed to Identifier in MC 1.21.11.
#    Apply the mechanical rename to the generated project's sources.
if [[ "$profile" == "v121_11" ]]; then
  find "$dest/src" -name '*.java' -exec sed -i \
    -e 's/ResourceLocationArgument/IdentifierArgument/g' \
    -e 's/\bResourceLocation\b/Identifier/g' {} +
  echo "ResourceLocation -> Identifier rename applied under: $dest/src"
fi

# 7. Optional: inject the Layer-2 client smoke hook (docs/CLIENT_SMOKE_TEST.md).
#    Off by default, so profile jars built from this harness never contain it.
if [[ "${IML_SMOKETEST:-0}" == "1" ]]; then
  "$ROOT/tools/smoketest/inject.sh" "$dest"
fi

# 8. Done.
echo "Harness project ($mc, profile $profile, neo $neo_version): $dest"
