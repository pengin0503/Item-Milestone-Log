#!/usr/bin/env bash
# Checks the contents, mods.toml ranges, duplicate entries, and bytecode
# isolation of the merged 26.x mod jar (docs/DESIGN_1.3.0.md section 3.3).
#
#   .github/scripts/verify-merged-jar.sh <merged_jar>
set -euo pipefail

usage() {
  echo "Usage: .github/scripts/verify-merged-jar.sh <merged_jar>" >&2
}

if [[ $# -ne 1 ]]; then
  usage
  exit 2
fi

JAR="$1"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

if [[ ! -f "$JAR" ]]; then
  echo "  FAIL  jar not found: $JAR" >&2
  exit 1
fi

echo "Jar: ${JAR#$ROOT/} ($(stat -c %s "$JAR") bytes)"

listing="$(unzip -Z1 "$JAR")"
toml="$(unzip -p "$JAR" META-INF/neoforge.mods.toml 2>/dev/null || true)"
props="$ROOT/versions/26.1.2/gradle.properties"
prop() { grep -E "^$1=" "$props" | cut -d= -f2-; }

status=0
expect_file() {
  if grep -qxF "$1" <<< "$listing"; then echo "  PASS  contains $1"; else echo "  FAIL  missing $1"; status=1; fi
}
expect_toml() {
  if grep -qF "$1" <<< "$toml"; then echo "  PASS  mods.toml has $1"; else echo "  FAIL  mods.toml lacks $1"; status=1; fi
}

compat_entries=(
  'ModTriggers.class'
  'MilestoneRecordedTrigger.class'
  'TriggerCompatImpl.class'
  'TagEventCompatImpl.class'
  'ConfigCompatImpl.class'
  'client/ScreenCompatImpl.class'
)

for suffix in v26_1 v26_2 v26_3; do
  for rel in "${compat_entries[@]}"; do
    expect_file "com/pengin0503/itemmilestonelog/compat/${suffix}/${rel}"
  done
done

expect_file META-INF/neoforge.mods.toml
expect_file logo.png
expect_file assets/itemmilestonelog/lang/en_us.json
expect_file assets/itemmilestonelog/lang/ja_jp.json
expect_file com/pengin0503/itemmilestonelog/ItemMilestoneLog.class
expect_file com/pengin0503/itemmilestonelog/compat/McCompat.class
expect_file com/pengin0503/itemmilestonelog/compat/McVersion.class
expect_file com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.class

dups="$(unzip -Z1 "$JAR" | sort | uniq -d)"
if [[ -z "$dups" ]]; then
  echo "  PASS  no duplicate jar entries"
else
  echo "  FAIL  duplicate jar entries found:"
  sed 's/^/        /' <<< "$dups"
  status=1
fi

expect_toml 'modId="itemmilestonelog"'
expect_toml "versionRange=\"$(prop neo_version_range)\""
expect_toml 'versionRange="[26.1.2,26.3]"'

if grep -q '\${' <<< "$toml"; then
  echo "  FAIL  unexpanded \${...} placeholders in mods.toml"
  status=1
else
  echo "  PASS  no unexpanded \${...} placeholders in mods.toml"
fi

if ! "$ROOT/.github/scripts/check-merged-jar-bytecode.sh" "$JAR"; then
  status=1
fi

echo "::group::META-INF/neoforge.mods.toml"
grep -vE '^\s*(#|$)' <<< "$toml"
echo "::endgroup::"
exit "$status"
