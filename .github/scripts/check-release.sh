#!/usr/bin/env bash
# Checks the jars for a release and writes the release notes.
#
#   check-release.sh <jar_dir> <tag or ""> <notes_file>
#
# The release composition comes from publish-manifest.json (repo root): five merged
# jars, the 26.x jar (26.1.2-26.3) and the four 1.21.x jars (A 1.21-1.21.4,
# B1 1.21.5-1.21.8, B2 1.21.9-1.21.10, C 1.21.11), each with its game_versions,
# neoforge_min and java (docs/DESIGN_1.3.0.md section 6).
#
# - every versions/*/gradle.properties has the same mod_version,
# - jar_dir holds exactly the manifest's jars for that version ({version} in each
#   file name substituted): each listed jar present, no other *.jar,
# - the concrete manifest (placeholders substituted) is written to
#   <jar_dir>/publish-manifest.json so the release ships it,
# - the tag (if given) is v<mod_version>,
# - CHANGELOG.md has a "## <mod_version>" section; it goes into the notes, followed
#   by a table of the files built from the manifest.
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
done

# The manifest: substitute {version} in every file name, then the jars in DIR must
# be exactly that set.
MANIFEST="$ROOT/publish-manifest.json"
if [ ! -f "$MANIFEST" ]; then
  echo "  FAIL  $MANIFEST is missing"
  exit 1
fi
if ! jq -e '
    (.files | type == "array" and length > 0)
    and all(.files[];
      (.file | type == "string" and length > 0)
      and (.game_versions | type == "array" and length > 0 and all(.[]; type == "string"))
      and (.neoforge_min | type == "string" and length > 0)
      and (.java | type == "number"))
  ' "$MANIFEST" > /dev/null 2>&1; then
  echo "  FAIL  $MANIFEST is not valid JSON or does not match the expected shape"
  echo "        ({\"files\": [{\"file\", \"game_versions\": [...], \"neoforge_min\", \"java\"}, ...]})"
  exit 1
fi
CONCRETE="$(jq --arg v "$VERSION" '.files |= map(.file |= gsub("\\{version\\}"; $v))' "$MANIFEST")"
mapfile -t expected < <(jq -r '.files[].file' <<< "$CONCRETE")
pass "publish-manifest.json lists ${#expected[@]} jars"

for f in "${expected[@]}"; do
  if [ -f "$DIR/$f" ]; then pass "$f"; else fail "missing $f"; fi
done

shopt -s nullglob
jars=("$DIR"/*.jar)
shopt -u nullglob
extras=()
for jar in "${jars[@]}"; do
  base="$(basename "$jar")"
  known=0
  for f in "${expected[@]}"; do
    if [ "$base" = "$f" ]; then known=1; break; fi
  done
  [ "$known" -eq 1 ] || extras+=("$base")
done
if [ "${#extras[@]}" -eq 0 ]; then
  pass "${#jars[@]} jars, nothing else"
else
  fail "jars in $DIR not in publish-manifest.json: ${extras[*]}"
fi

mkdir -p "$DIR"
printf '%s\n' "$CONCRETE" > "$DIR/publish-manifest.json"
pass "wrote the concrete manifest to $DIR/publish-manifest.json"

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
  echo "Each jar loads on the Minecraft versions in its row."
  echo
  echo "| Minecraft | File | NeoForge | Java |"
  echo "|---|---|---|---|"
  # Minecraft label: first–last of game_versions when more than one (en dash, as in
  # CHANGELOG.md), else the single version.
  jq -r '.files[]
    | (if (.game_versions | length) > 1
         then "\(.game_versions[0])–\(.game_versions[-1])"
         else .game_versions[0] end) as $mc
    | "| \($mc) | `\(.file)` | \(.neoforge_min) or newer | \(.java) |"' <<< "$CONCRETE"
} >> "$NOTES"

echo "Release notes (${NOTES}):"
sed 's/^/    /' "$NOTES"
exit $status
