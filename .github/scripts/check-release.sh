#!/usr/bin/env bash
# Checks the jars for a release and writes the release notes.
#
#   check-release.sh <jar_dir> <tag or ""> <notes_file>
#
# - every versions/*/gradle.properties has the same mod_version,
# - jar_dir holds exactly the four jars of that version, one per Minecraft version,
# - the tag (if given) is v<mod_version>,
# - CHANGELOG.md has a "## <mod_version>" section; it goes into the notes, followed
#   by a table of the files.
set -euo pipefail

DIR="$1"
TAG="$2"
NOTES="$3"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MC_VERSIONS=(1.21.1 26.1.2 26.2 26.3)

prop() { grep -E "^$2=" "$ROOT/versions/$1/gradle.properties" | cut -d= -f2-; }

status=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; status=1; }

VERSION="$(prop "${MC_VERSIONS[0]}" mod_version)"
echo "Release $VERSION${TAG:+ (tag $TAG)}"

for mc in "${MC_VERSIONS[@]}"; do
  v="$(prop "$mc" mod_version)"
  if [ "$v" = "$VERSION" ]; then pass "$mc: mod_version $v"; else fail "$mc: mod_version is $v, not $VERSION"; fi
  jar="$DIR/itemmilestonelog-neoforge-$mc-$VERSION.jar"
  if [ -f "$jar" ]; then pass "$(basename "$jar")"; else fail "missing $(basename "$jar")"; fi
done

shopt -s nullglob
jars=("$DIR"/*.jar)
shopt -u nullglob
if [ "${#jars[@]}" -eq "${#MC_VERSIONS[@]}" ]; then
  pass "${#jars[@]} jars, nothing else"
else
  fail "expected ${#MC_VERSIONS[@]} jars in $DIR, found ${#jars[@]}: $(cd "$DIR" && ls -1 *.jar 2>/dev/null | tr '\n' ' ')"
fi

if [ -n "$TAG" ]; then
  if [ "$TAG" = "v$VERSION" ]; then pass "tag $TAG matches mod_version"; else fail "tag $TAG does not match mod_version $VERSION (expected v$VERSION)"; fi
fi

mkdir -p "$(dirname "$NOTES")"
# The lines after "## <version>" (a heading may add text after the version) up to the next "## ".
awk -v v="$VERSION" '
  /^## / { if (found) exit; if ($2 == v) { found = 1; next } }
  found && !started && /^[[:space:]]*$/ { next }
  found { started = 1; print }
' "$ROOT/CHANGELOG.md" > "$NOTES"
if grep -q '[^[:space:]]' "$NOTES"; then
  pass "CHANGELOG.md has a section for $VERSION"
else
  fail "CHANGELOG.md has no '## $VERSION' section"
fi

{
  echo
  echo "### Files"
  echo
  echo "Each jar only loads on its own Minecraft version."
  echo
  echo "| Minecraft | File | NeoForge | Java |"
  echo "|---|---|---|---|"
  for mc in "${MC_VERSIONS[@]}"; do
    java=25
    [ "$mc" = "1.21.1" ] && java=21
    echo "| $mc | \`itemmilestonelog-neoforge-$mc-$VERSION.jar\` | $(prop "$mc" neo_version) or newer | $java |"
  done
} >> "$NOTES"

echo "Release notes (${NOTES}):"
sed 's/^/    /' "$NOTES"
exit $status
