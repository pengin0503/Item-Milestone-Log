#!/usr/bin/env bash
# TEMPORARY, removed again before merge: reports what the restored Gradle cache holds.
#
#   tmp-cache-report.sh restored   right after setup-gradle restored the cache
#   tmp-cache-report.sh built      after the build
set -uo pipefail

G="$HOME/.gradle"
N="$G/caches/neoformruntime"
I="$N/intermediate_results"
MARK="$RUNNER_TEMP/tmp-cache-mark"
mb() { awk '{printf "%9.1f MB  %s\n", $1/1e6, $2}'; }

echo "== Gradle home: $1 =="
du -sb "$G"/caches/* "$G"/wrapper 2>/dev/null | sort -n | mb
echo "-- neoformruntime"
du -sb "$N"/* 2>/dev/null | sort -n | mb
echo "-- artifacts"
du -sb "$N"/artifacts/* 2>/dev/null | mb
echo "-- asset indexes"
for idx in "$N"/assets/indexes/*.json; do
  [ -e "$idx" ] || continue
  echo "  $(basename "$idx"): $(grep -o '"hash"' "$idx" | wc -l) objects"
done
echo "  objects present: $(find "$N/assets/objects" -type f 2>/dev/null | wc -l)"

if [ "$1" = restored ]; then
  touch "$MARK"
  echo "intermediate results: $(find "$I" -maxdepth 1 -name '*.txt' 2>/dev/null | wc -l) entries"
  exit 0
fi

# An intermediate result is <step>_<hash>.txt plus its outputs <step>_<hash>_*. NeoForm Runtime
# writes the .txt when it stores a result and sets its mtime when it reuses one.
used=0; usedb=0; stale=0; staleb=0
for m in "$I"/*.txt; do
  [ -e "$m" ] || continue
  base="${m%.txt}"
  size=$(du -cb "$m" "$base"_* 2>/dev/null | tail -1 | cut -f1)
  if [ "$m" -nt "$MARK" ]; then
    used=$((used + 1)); usedb=$((usedb + size))
  else
    stale=$((stale + 1)); staleb=$((staleb + size))
    echo "  not used: $(basename "$base") $((size / 1000000)) MB"
  fi
done
echo "intermediate results used or written in this job: $used entries, $((usedb / 1000000)) MB"
echo "intermediate results not used in this job: $stale entries, $((staleb / 1000000)) MB"
