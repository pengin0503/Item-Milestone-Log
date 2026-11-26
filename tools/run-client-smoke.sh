#!/usr/bin/env bash
# Layer-2 headless client smoke test runner (docs/CLIENT_SMOKE_TEST.md).
#
# Runs the real dev client (moddev `runClient`) of each requested Minecraft version under
# Xvfb + Mesa (llvmpipe for OpenGL 1.21.x, lavapipe for Vulkan 26.x) with the smoke hook
# from tools/smoketest/ injected into a throwaway harness under versions/verify/<mc>.
# The hook enters a singleplayer world, opens MilestoneScreen, ItemPickerScreen and
# PresetListScreen, screenshots each and stops the client.
#
# Usage (on a Linux box with Xvfb + Mesa, e.g. the GCE VM iml-builder; repo root):
#   tools/run-client-smoke.sh [<mc> ...]
# Default versions: 1.21 1.21.4 1.21.5 1.21.7 1.21.8 1.21.9 1.21.11 26.1.2 26.3
#
# Output: build/smoke/<mc>/{result.json,*.png,latest.log,gradle.log,crash-*.txt,status}
#         build/smoke/summary.md
# Exit status: 0 if every version passes, 1 otherwise (2 = usage / setup error).
#
# Environment:
#   SMOKE_PARALLEL=2       clients run at the same time (each gets its own Xvfb display)
#   SMOKE_TIMEOUT=600      seconds per client before it is killed (FAIL)
#   SMOKE_FRESH_WORLD=0    1 = delete run/saves/iml-smoke first (world is re-created)
#   SMOKE_PREPARE=1        0 = reuse the existing harness projects as they are
#   SMOKE_DISPLAY_BASE=99  first Xvfb display number
#   JDK21 / JDK25          JDK homes (default ~/tools/jdk21, ~/tools/jdk25)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DEFAULT_VERSIONS=(1.21 1.21.4 1.21.5 1.21.7 1.21.8 1.21.9 1.21.11 26.1.2 26.3)
if [[ $# -gt 0 ]]; then
  case "$1" in -h|--help) sed -n '2,27p' "$0"; exit 0 ;; esac
  VERSIONS=("$@")
else
  VERSIONS=("${DEFAULT_VERSIONS[@]}")
fi

PAR="${SMOKE_PARALLEL:-2}"
TIMEOUT="${SMOKE_TIMEOUT:-600}"
DISPLAY_BASE="${SMOKE_DISPLAY_BASE:-99}"
JDK21="${JDK21:-$HOME/tools/jdk21}"
JDK25="${JDK25:-$HOME/tools/jdk25}"
OUT="$ROOT/build/smoke"

# <mc> -> "<profile> <neo_version>" for tools/prepare-121x-harness.sh (same table as its header).
harness_121x() {
  case "$1" in
    1.21)    echo "v121_1 21.0.143" ;;
    1.21.1)  echo "v121_1 21.1.1" ;;
    1.21.2)  echo "v121_2 21.2.0-beta" ;;
    1.21.3)  echo "v121_2 21.3.56" ;;
    1.21.4)  echo "v121_4 21.4.121" ;;
    1.21.5)  echo "v121_5 21.5.74" ;;
    1.21.6)  echo "v121_6 21.6.0-beta" ;;
    1.21.7)  echo "v121_7 21.7.0-beta" ;;
    1.21.8)  echo "v121_8 21.8.9" ;;
    1.21.9)  echo "v121_9 21.9.0-beta" ;;
    1.21.10) echo "v121_9 21.10.64" ;;
    1.21.11) echo "v121_11 21.11.42" ;;
    *) return 1 ;;
  esac
}

is_26x() { [[ "$1" == 26.* ]]; }

log() { printf '[smoke %(%H:%M:%S)T] %s\n' -1 "$*"; }

# ---- checks -----------------------------------------------------------------------
command -v Xvfb >/dev/null || { echo "Xvfb not installed (apt-get install xvfb mesa-utils libgl1-mesa-dri mesa-vulkan-drivers)" >&2; exit 2; }
command -v flock >/dev/null || { echo "flock not installed (util-linux)" >&2; exit 2; }
for mc in "${VERSIONS[@]}"; do
  if is_26x "$mc"; then
    [[ -d "versions/$mc" ]] || { echo "unknown version: $mc (no versions/$mc)" >&2; exit 2; }
    [[ -x "$JDK25/bin/java" ]] || { echo "JDK 25 not found: $JDK25" >&2; exit 2; }
  else
    harness_121x "$mc" >/dev/null || { echo "unknown version: $mc" >&2; exit 2; }
    [[ -x "$JDK21/bin/java" ]] || { echo "JDK 21 not found: $JDK21" >&2; exit 2; }
  fi
done

mkdir -p "$OUT"
XVFB_PIDS=()
WORKERS=()
QUEUE=""
LOCK=""

# Kill any JVM still running with its cwd inside the version's harness project (the
# game). Defined before cleanup so an interrupt during harness preparation is safe.
kill_project_java() { # <mc>
  local dir="$ROOT/versions/verify/$1" pid cwd
  for pid in $(pgrep java); do
    cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)" || continue
    if [[ "$cwd" == "$dir" || "$cwd" == "$dir/"* ]]; then kill -9 "$pid" 2>/dev/null; fi
  done
}

cleanup() {
  for p in "${XVFB_PIDS[@]}"; do kill "$p" 2>/dev/null; done
  for p in "${WORKERS[@]}"; do
    # Stop the worker and its direct child (the setsid/timeout process group leader);
    # timeout forwards SIGTERM to the Gradle JVM, and kill_project_java catches any
    # process that outlived it.
    pkill -TERM -P "$p" 2>/dev/null
    kill "$p" 2>/dev/null
  done
  for mc in "${VERSIONS[@]}"; do kill_project_java "$mc"; done
  if [[ -n "$QUEUE" ]]; then rm -f "$QUEUE" "$LOCK"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---- 1. harness projects (sequential; keeps build/ and run/ so caches stay warm) -------
prepare() {
  local mc="$1" dest="versions/verify/$1" keep="versions/verify/.smoke-keep-$1"
  rm -rf "$keep"; mkdir -p "$keep"
  for d in build run .gradle; do [[ -d "$dest/$d" ]] && mv "$dest/$d" "$keep/$d"; done
  if is_26x "$mc"; then
    IML_SMOKETEST=1 tools/prepare-26x-harness.sh "$mc"
  else
    # shellcheck disable=SC2046
    IML_SMOKETEST=1 tools/prepare-121x-harness.sh "$mc" $(harness_121x "$mc")
  fi
  local rc=$?
  # The harness script removes $dest before generating it. If it fails before
  # recreating it, restore the saved build/run/.gradle caches into the (recreated)
  # directory instead of losing them with $keep.
  local restore_failed=0
  mkdir -p "$dest"
  for d in build run .gradle; do
    if [[ -d "$keep/$d" ]]; then
      rm -rf "${dest:?}/$d"
      mv "$keep/$d" "$dest/$d" || restore_failed=1
    fi
  done
  if [[ "$restore_failed" -eq 0 ]]; then
    rm -rf "$keep"
  else
    echo "warning: could not restore every cached directory; kept $keep" >&2
    [[ "$rc" -ne 0 ]] || rc=1
  fi
  return $rc
}

set_option() { # <options.txt> <key> <value>
  if grep -q "^$2:" "$1" 2>/dev/null; then sed -i "s|^$2:.*|$2:$3|" "$1"; else echo "$2:$3" >> "$1"; fi
}

seed_run_dir() {
  local mc="$1" run="versions/verify/$1/run"
  mkdir -p "$run/config"
  local opt="$run/options.txt"
  touch "$opt"
  set_option "$opt" onboardAccessibility false   # skip the first-launch accessibility screen
  set_option "$opt" pauseOnLostFocus false       # Xvfb has no window manager / focus
  set_option "$opt" renderDistance 4             # keep llvmpipe / lavapipe load low
  set_option "$opt" simulationDistance 5
  set_option "$opt" soundCategory_master 0.0
  set_option "$opt" tutorialStep none
  if is_26x "$mc"; then
    # The FML early window fails under Xvfb ("Couldn't find matching GLX visual") and
    # then blocks on a modal dialog.
    local fml="$run/config/fml.toml"
    if [[ -f "$fml" ]] && grep -q '^earlyWindowControl' "$fml"; then
      sed -i 's/^earlyWindowControl.*/earlyWindowControl = false/' "$fml"
    else
      echo 'earlyWindowControl = false' >> "$fml"
    fi
  fi
  if [[ "${SMOKE_FRESH_WORLD:-0}" == "1" ]]; then rm -rf "$run/saves/iml-smoke"; fi
  rm -rf "$run/iml-smoke"
  rm -f "$run"/screenshots/iml-smoke-*.png
}

if [[ "${SMOKE_PREPARE:-1}" == "1" ]]; then
  for mc in "${VERSIONS[@]}"; do
    log "preparing harness $mc"
    if ! prepare "$mc" > "$OUT/prepare-$mc.log" 2>&1; then
      echo "harness preparation failed for $mc (see $OUT/prepare-$mc.log)" >&2
      exit 2
    fi
  done
fi

# ---- 2. displays --------------------------------------------------------------------
for ((s = 0; s < PAR; s++)); do
  d=$((DISPLAY_BASE + s))
  if ! pgrep -f "Xvfb :$d " >/dev/null; then
    Xvfb ":$d" -screen 0 1280x720x24 -nolisten tcp > "$OUT/xvfb-$d.log" 2>&1 &
    XVFB_PIDS+=($!)
  fi
done
sleep 2

# ---- 3. clients ---------------------------------------------------------------------
run_one() { # <mc> <display>
  local mc="$1" disp="$2" proj="versions/verify/$1"
  local out="$OUT/$mc" run="$ROOT/versions/verify/$1/run"
  rm -rf "$out"; mkdir -p "$out"
  seed_run_dir "$mc"
  local jdk="$JDK21"
  is_26x "$mc" && jdk="$JDK25"
  local marker="$out/.start"; touch "$marker"
  local t0; t0=$(date +%s)
  log "start $mc on :$disp"
  (
    export DISPLAY=":$disp" JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH"
    [[ -f /usr/share/vulkan/icd.d/lvp_icd.json ]] && export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json
    exec setsid timeout -k 30 "$TIMEOUT" ./gradlew -p "$proj" runClient --no-daemon --console=plain \
      -Piml.smoketest=1 -Piml.smoketest.timeoutSec=$((TIMEOUT - 60))
  ) > "$out/gradle.log" 2>&1
  local rc=$?
  local secs=$(( $(date +%s) - t0 ))
  kill_project_java "$mc"

  cp -f "$run/iml-smoke/result.json" "$out/" 2>/dev/null
  cp -f "$run"/screenshots/iml-smoke-*.png "$out/" 2>/dev/null
  cp -f "$run/logs/latest.log" "$out/" 2>/dev/null
  local c
  for c in "$run"/crash-reports/*.txt; do
    [[ -f "$c" && "$c" -nt "$marker" ]] && cp -f "$c" "$out/crash-$(basename "$c")"
  done

  local status=FAIL reason=""
  local shots; shots=$(find "$out" -maxdepth 1 -name 'iml-smoke-*.png' | wc -l)
  # Read only the top-level status: the per-screen objects also carry "status": "PASS".
  local hook_status=""
  [[ -f "$out/result.json" ]] && hook_status="$(sed -n 's/^  "status": "\(.*\)",$/\1/p' "$out/result.json" | head -1)"
  if [[ ! -f "$out/result.json" ]]; then
    reason="no result.json (client crashed or never reached the hook; rc=$rc)"
  elif [[ "$hook_status" != "PASS" ]]; then
    reason="hook: ${hook_status:-unreadable result.json}; $(sed -n 's/^  "reason": "\(.*\)",$/\1/p' "$out/result.json" | head -1)"
  elif [[ $rc -ne 0 ]]; then
    reason="client exited with rc=$rc after a passing hook"
  elif [[ "$shots" -ne 3 ]]; then
    reason="expected 3 screenshots, found $shots"
  else
    status=PASS
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$mc" "$status" "$secs" "$rc" "$reason" > "$out/status"
  log "done  $mc: $status in ${secs}s ${reason:+($reason)}"
}

QUEUE="$OUT/.queue"; LOCK="$OUT/.queue.lock"
printf '%s\n' "${VERSIONS[@]}" > "$QUEUE"
worker() { # <display>
  local mc
  while :; do
    mc="$(flock "$LOCK" sh -c 'head -n1 "$1"; sed -i 1d "$1"' _ "$QUEUE")"
    [[ -z "$mc" ]] && return 0
    run_one "$mc" "$1"
  done
}

T_ALL=$(date +%s)
WORKERS=()
for ((s = 0; s < PAR; s++)); do
  worker $((DISPLAY_BASE + s)) &
  WORKERS+=($!)
done
wait "${WORKERS[@]}"  # not a bare `wait`: that would also wait for the Xvfb children
rm -f "$QUEUE" "$LOCK"

# ---- 4. summary ---------------------------------------------------------------------
json_num() { sed -n "s/^  \"$2\": \(-\{0,1\}[0-9]*\),\$/\1/p" "$1" 2>/dev/null; }
fails=0
{
  echo "| MC | Result | Wall s | Title s | World s | Screens (open ms) | Notes |"
  echo "|---|---|---|---|---|---|---|"
  for mc in "${VERSIONS[@]}"; do
    st="$OUT/$mc/status"
    if [[ ! -f "$st" ]]; then echo "| $mc | FAIL | - | - | - | - | not run |"; fails=$((fails + 1)); continue; fi
    IFS=$'\t' read -r _ status secs _ reason < "$st"
    [[ "$status" == PASS ]] || fails=$((fails + 1))
    r="$OUT/$mc/result.json"; title="-"; world="-"; screens="-"
    if [[ -f "$r" ]]; then
      t="$(json_num "$r" title_at_ms)"; w="$(json_num "$r" world_at_ms)"
      [[ -n "$t" && "$t" -ge 0 ]] && title=$(awk "BEGIN{printf \"%.1f\", $t/1000}")
      [[ -n "$w" && "$w" -ge 0 ]] && world=$(awk "BEGIN{printf \"%.1f\", $w/1000}")
      screens="$(sed -n 's/.*"name": "\([A-Za-z]*\)", "status": "\([A-Z]*\)", "open_ms": \(-\{0,1\}[0-9]*\).*/\1 \2 (\3)/p' "$r" | paste -sd ';' - | sed 's/;/; /g')"
      d="$(sed -n 's/^  "dismissed_screens": \[\(..*\)\],$/\1/p' "$r")"
      [[ -n "$d" ]] && reason="${reason:+$reason; }dismissed: $d"
      wm="$(sed -n 's/^  "world_mode": "\(.*\)",$/\1/p' "$r")"
      [[ -n "$wm" ]] && reason="${reason:+$reason; }world $wm"
    fi
    echo "| $mc | $status | $secs | $title | $world | ${screens:--} | ${reason//|/\\|} |"
  done
} > "$OUT/summary.md"

echo
echo "Client smoke summary (total $(( $(date +%s) - T_ALL ))s, $PAR in parallel; title/world = seconds since JVM start):"
cat "$OUT/summary.md"
echo "Artifacts: $OUT/<mc>/"
[[ $fails -eq 0 ]] && exit 0 || exit 1
