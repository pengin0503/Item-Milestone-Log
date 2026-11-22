#!/usr/bin/env bash
# Checks the contents of the built mod jar.
#
#   verify-jar.sh <mc_version>
set -euo pipefail

MC="$1"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LIBS="$ROOT/versions/$MC/build/libs"

shopt -s nullglob
jars=()
for f in "$LIBS"/itemmilestonelog-neoforge-"$MC"-*.jar; do
  case "$f" in *-sources.jar|*-javadoc.jar) ;; *) jars+=("$f") ;; esac
done
if [ "${#jars[@]}" -ne 1 ]; then
  echo "Expected exactly one mod jar in ${LIBS#$ROOT/}, found: ${jars[*]:-none}"
  exit 1
fi
JAR="${jars[0]}"
echo "Jar: ${JAR#$ROOT/} ($(stat -c %s "$JAR") bytes)"

listing="$(unzip -Z1 "$JAR")"
toml="$(unzip -p "$JAR" META-INF/neoforge.mods.toml)"
props="$ROOT/versions/$MC/gradle.properties"
prop() { grep -E "^$1=" "$props" | cut -d= -f2-; }

status=0
expect_file() {
  if grep -qxF "$1" <<< "$listing"; then echo "  PASS  contains $1"; else echo "  FAIL  missing $1"; status=1; fi
}
expect_toml() {
  if grep -qF "$1" <<< "$toml"; then echo "  PASS  mods.toml has $1"; else echo "  FAIL  mods.toml lacks $1"; status=1; fi
}

expect_file META-INF/neoforge.mods.toml
expect_file logo.png
expect_file assets/itemmilestonelog/lang/en_us.json
expect_file assets/itemmilestonelog/lang/ja_jp.json
expect_file com/pengin0503/itemmilestonelog/ItemMilestoneLog.class
expect_file com/pengin0503/itemmilestonelog/ServerLang.class
expect_file com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.class

expect_toml 'modId="itemmilestonelog"'
expect_toml "version=\"$(prop mod_version)\""
expect_toml 'logoFile="logo.png"'
expect_toml "versionRange=\"$(prop neo_version_range)\""
expect_toml "versionRange=\"$(prop minecraft_version_range)\""
expect_toml 'displayTest="IGNORE_ALL_VERSION"'
expect_toml 'updateJSONURL="https://raw.githubusercontent.com/pengin0503/Item-Milestone-Log/main/docs/updates.json"'

if grep -q '\${' <<< "$toml"; then
  echo "  FAIL  unexpanded \${...} placeholders in mods.toml"
  status=1
fi
if grep -qE '(^|/)(run|build)/|\.bbmodel$' <<< "$listing"; then
  echo "  FAIL  development files packed into the jar"
  status=1
fi

echo "::group::META-INF/neoforge.mods.toml"
grep -vE '^\s*(#|$)' <<< "$toml"
echo "::endgroup::"
exit $status
