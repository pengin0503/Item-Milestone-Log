#!/usr/bin/env bash
# Deletes the NeoForm Runtime results that the build did not use, so that a NeoForm cache
# entry saved after it holds only what this Minecraft version's builds need.
#
#   prune-neoform-cache.sh <marker>
#
# <marker> is a file created before the build. NeoForm Runtime (run by ModDevGradle) keeps
# each result in caches/neoformruntime/intermediate_results as <step>_<hash>.txt (the cache
# key) and <step>_<hash>_<output>.<ext> files. It writes the .txt when it stores a result
# and sets the .txt's modification time when it reuses one, for every step it needs. A
# result whose .txt is not newer than <marker> was not used by the build: for example one
# made for an earlier NeoForge version, restored with an older cache entry.
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 <marker file created before the build>" >&2
  exit 2
fi
MARKER="$1"
DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/neoformruntime/intermediate_results"
if [ ! -d "$DIR" ]; then
  echo "No NeoForm results in $DIR" >&2
  exit 1
fi

kept=0
dropped=0
other=0
for file in "$DIR"/*; do
  [ -f "$file" ] || continue
  name="$(basename "$file")"
  # The result a file belongs to: <step>_<40-hex hash>, followed by .txt or _<output>.
  # Anything else is not a result in the layout described above, and is left alone.
  if ! [[ "$name" =~ ^(.+_[0-9a-f]{40})(\.txt|_.+)$ ]]; then
    other=$((other + 1))
    continue
  fi
  key="$DIR/${BASH_REMATCH[1]}.txt"
  if [ "$key" -nt "$MARKER" ]; then
    [ "$file" = "$key" ] && kept=$((kept + 1))
  else
    [ "$file" = "$key" ] && dropped=$((dropped + 1))
    rm -f -- "$file"
  fi
done
echo "NeoForm results: kept $kept that the build used, deleted $dropped that it did not use"
if [ "$other" -gt 0 ]; then
  echo "Left $other other files in $DIR alone"
fi
echo "$(du -sh "$DIR" | cut -f1) in $DIR"
