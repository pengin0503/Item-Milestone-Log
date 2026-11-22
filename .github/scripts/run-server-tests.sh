#!/usr/bin/env bash
# Starts a dedicated server with the mod (ModDevGradle's runServer) and runs
# .github/integration/server-test.mjs against it.
#
#   run-server-tests.sh <mc_version> <neoforge_version> <bot: true|false>
#
# With bot=true a vanilla-protocol client (mineflayer) joins, the server is then
# restarted and the client checks that the saved data came back.
set -euo pipefail

MC="$1"
NEO="$2"
BOT="$3"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJECT="versions/$MC"
RUN_DIR="$ROOT/$PROJECT/run"
LOG_DIR="$ROOT/build/server-logs/$MC-$NEO"
TEST_JS="$ROOT/.github/integration/server-test.mjs"
SCAN_INTERVAL_TICKS=600
SERVER_PGID=""

mkdir -p "$LOG_DIR" "$RUN_DIR/config"

# Fresh world each time, and settings that make the server fast and scriptable.
rm -rf "$RUN_DIR/world"
echo "eula=true" > "$RUN_DIR/eula.txt"
cat > "$RUN_DIR/server.properties" <<'EOF'
online-mode=false
enforce-secure-profile=false
enable-rcon=true
rcon.port=25575
rcon.password=iml-ci
server-port=25565
level-name=world
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
difficulty=peaceful
max-players=4
EOF
# A long scan interval lets the tests tell the pickup hook and the inventory scan apart.
cat > "$RUN_DIR/config/itemmilestonelog-common.toml" <<EOF
defaultTargets = []
scanIntervalTicks = $SCAN_INTERVAL_TICKS
notifyPlayer = true
broadcastMilestones = false
EOF

start_server() {
  local log="$1"
  echo "::group::Start server (MC $MC, NeoForge $NEO) -> $log"
  # setsid: own process group, so the Gradle client and the server JVM can be stopped together.
  setsid bash -c "cd '$ROOT' && exec gradle -p '$PROJECT' runServer -Pneo_version='$NEO' --console=plain --no-daemon" \
    > "$log" 2>&1 < /dev/null &
  SERVER_PGID=$!
  local waited=0
  until grep -q 'Done (' "$log"; do
    if ! kill -0 "$SERVER_PGID" 2>/dev/null; then
      echo "Server process exited before it finished starting:"
      tail -n 200 "$log"
      echo "::endgroup::"
      return 1
    fi
    if [ "$waited" -ge 900 ]; then
      echo "Server did not finish starting within 900s:"
      tail -n 200 "$log"
      echo "::endgroup::"
      return 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  echo "Server is up after ~${waited}s"
  echo "::endgroup::"
}

stop_server() {
  [ -n "$SERVER_PGID" ] || return 0
  node "$TEST_JS" --phase stop || true
  local waited=0
  while kill -0 "$SERVER_PGID" 2>/dev/null && [ "$waited" -lt 120 ]; do
    sleep 2
    waited=$((waited + 2))
  done
  if kill -0 "$SERVER_PGID" 2>/dev/null; then
    echo "Server did not stop within 120s; killing it"
    kill -TERM -- "-$SERVER_PGID" 2>/dev/null || true
    sleep 5
    kill -KILL -- "-$SERVER_PGID" 2>/dev/null || true
  fi
  SERVER_PGID=""
}
trap stop_server EXIT

# Problems in the server log that involve this mod.
check_log() {
  local log="$1"
  local ok=0
  if grep -q 'Item Milestone Log loaded' "$log"; then
    echo "  PASS  mod constructed on the dedicated server"
  else
    echo "  FAIL  'Item Milestone Log loaded' not found in the server log"
    ok=1
  fi
  if grep -nE '^\s+at com\.pengin0503\.itemmilestonelog' "$log" > "$LOG_DIR/mod-stacktraces.txt"; then
    echo "  FAIL  stack traces through mod code in the server log:"
    head -n 40 "$LOG_DIR/mod-stacktraces.txt"
    ok=1
  else
    echo "  PASS  no stack traces through mod code"
  fi
  if grep -nE 'ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError' "$log" | grep -i 'itemmilestonelog\|pengin0503'; then
    echo "  FAIL  linkage errors involving the mod"
    ok=1
  fi
  return $ok
}

status=0
BOT_FLAG=()
[ "$BOT" = "true" ] && BOT_FLAG=(--bot)

start_server "$LOG_DIR/server-1.log"
node "$TEST_JS" --phase fresh --mc "$MC" --scan-interval-ticks "$SCAN_INTERVAL_TICKS" "${BOT_FLAG[@]}" || status=1
stop_server
check_log "$LOG_DIR/server-1.log" || status=1

echo "Config files written by the mod:"
ls -1 "$RUN_DIR/config" | grep -i itemmilestonelog || echo "  (none)"

if [ "$BOT" = "true" ]; then
  case "$MC" in
    1.21.1) data_file="$RUN_DIR/world/data/itemmilestonelog_data.dat" ;;
    *)      data_file="$RUN_DIR/world/data/itemmilestonelog/milestones.dat" ;;
  esac
  if [ -f "$data_file" ]; then
    echo "  PASS  saved data written to ${data_file#$ROOT/}"
  else
    echo "  FAIL  expected saved data at ${data_file#$ROOT/}; files under world/:"
    find "$RUN_DIR/world" -name '*.dat' | sed "s|$ROOT/||"
    status=1
  fi

  start_server "$LOG_DIR/server-2.log"
  node "$TEST_JS" --phase restart --mc "$MC" "${BOT_FLAG[@]}" || status=1
  stop_server
  check_log "$LOG_DIR/server-2.log" || status=1
fi

exit $status
