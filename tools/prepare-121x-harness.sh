#!/usr/bin/env bash
# N252 stage 2b: prepares a throwaway 1.21.x verification harness for one compat profile.
#
# Generates versions/verify/<mc> from versions/1.21.1 (N150 script), strips the Parchment
# block (1.21.1 mappings do not apply to other MC versions; same approach as
# verify-121x.sh), pins neo_version, and swaps the v121_1 compat implementations for the
# profile's variant tree under versions/1.21.1/compat-variants/<profile>/.
#
# Usage:
#   tools/prepare-121x-harness.sh <mc> <profile> <neo_version>
# Example:
#   tools/prepare-121x-harness.sh 1.21.4 v121_4 21.4.121
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

if [[ ! -d "$variant" ]]; then
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

# 4. Swap the compat implementations for the profile's variant tree.
rm -rf "$pkg_dir/compat/v121_1" "$pkg_dir/compat/client/v121_1"
cp -R "$variant/." "$pkg_dir/compat/"

# 5. Test-only patch: the DimensionDataStorage constructor changed File -> Path in
#    MC 1.21.2, so the generated project's MilestoneBackupTest must pass the Path
#    directly. Shipped sources are unaffected (they keep compiling on 1.21.1).
if [[ "$profile" != "v121_1" ]]; then
  python3 - "$dest/src/test/java/com/pengin0503/itemmilestonelog/MilestoneBackupTest.java" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = "new DimensionDataStorage(dataDir.toFile(), null, null)"
new = "new DimensionDataStorage(dataDir, null, null)"
assert old in s, "DimensionDataStorage File constructor call not found"
open(p, "w").write(s.replace(old, new))
print("DimensionDataStorage constructor patched:", p)
PYEOF
fi

# 6. Done.
echo "Harness project ($mc, profile $profile, neo $neo_version): $dest"
