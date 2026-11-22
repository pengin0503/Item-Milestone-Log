#!/usr/bin/env bash
# Installs a NeoForge dedicated server, puts the built mod jar into its mods folder
# and runs .github/integration/server-test.mjs against it.
#
#   run-server-tests.sh <mc_version> <neoforge_version> <bot: true|false>
#
# The jar under test is the one in versions/<mc>/build/libs, loaded the way players
# load it (a production server, not the development run). CI builds that jar against
# the minimum NeoForge version, as for a release, and runs it on both the minimum and
# the latest NeoForge.
#
# With bot=true a vanilla-protocol client (mineflayer) joins, the server is then
# restarted and the client checks that the saved data came back.
#
# Needs Java (21 for 1.21.1, 25 for 26.x), curl, Node 22 and
# "npm ci --prefix .github/integration".
set -euo pipefail

MC="$1"
NEO="$2"
BOT="$3"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LIBS="$ROOT/versions/$MC/build/libs"
SERVER_DIR="$ROOT/build/server/$MC-$NEO"
LOG_DIR="$ROOT/build/server-logs/$MC-$NEO"
TEST_JS="$ROOT/.github/integration/server-test.mjs"
SCAN_INTERVAL_TICKS=600
SERVER_PGID=""

mkdir -p "$LOG_DIR" "$SERVER_DIR"

# ------------------------------------------------------------------ setup

install_server() {
  if [ -d "$SERVER_DIR/libraries/net/neoforged/neoforge/$NEO" ]; then
    echo "NeoForge $NEO server already installed in ${SERVER_DIR#$ROOT/}"
    return 0
  fi
  echo "::group::Install NeoForge $NEO server"
  local installer="neoforge-$NEO-installer.jar"
  curl -fsSL --retry 3 -o "$SERVER_DIR/$installer" \
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO/$installer"
  if ! (cd "$SERVER_DIR" && java -jar "$installer" --installServer > "$LOG_DIR/installer.log" 2>&1); then
    echo "NeoForge installer failed:"
    tail -n 80 "$LOG_DIR/installer.log"
    echo "::endgroup::"
    return 1
  fi
  rm -f "$SERVER_DIR/$installer" "$SERVER_DIR/$installer.log"
  echo "Installed into ${SERVER_DIR#$ROOT/}"
  echo "::endgroup::"
}

install_mod() {
  shopt -s nullglob
  local jars=()
  for f in "$LIBS"/itemmilestonelog-neoforge-"$MC"-*.jar; do
    case "$f" in *-sources.jar|*-javadoc.jar) ;; *) jars+=("$f") ;; esac
  done
  shopt -u nullglob
  if [ "${#jars[@]}" -ne 1 ]; then
    echo "Expected exactly one mod jar in ${LIBS#$ROOT/}, found: ${jars[*]:-none}"
    return 1
  fi
  rm -rf "$SERVER_DIR/mods"
  mkdir -p "$SERVER_DIR/mods"
  cp "${jars[0]}" "$SERVER_DIR/mods/"
  echo "Mod under test: ${jars[0]#$ROOT/}"
}

install_server
install_mod

# Fresh world and config each time, and settings that make the server fast and scriptable.
rm -rf "$SERVER_DIR/world" "$SERVER_DIR/config" "$SERVER_DIR/logs"
mkdir -p "$SERVER_DIR/config"
echo "eula=true" > "$SERVER_DIR/eula.txt"
cat > "$SERVER_DIR/server.properties" <<'EOF'
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
# 26.3 (FML 12) registers the config as LOCAL, which FML names <modid>-local.toml.
case "$MC" in
  26.3) CONFIG_FILE="$SERVER_DIR/config/itemmilestonelog-local.toml" ;;
  *)    CONFIG_FILE="$SERVER_DIR/config/itemmilestonelog-common.toml" ;;
esac
# NeoForge rewrites a config file that lacks the spec's comments (keeping a .bak of
# it) but keeps valid values; check_config below makes sure this one was used.
cat > "$CONFIG_FILE" <<EOF
defaultTargets = []
scanIntervalTicks = $SCAN_INTERVAL_TICKS
notifyPlayer = true
broadcastMilestones = false
EOF

# The installer writes the launch arguments to libraries/.../unix_args.txt (run.sh
# just calls java with them).
ARGS_FILE="libraries/net/neoforged/neoforge/$NEO/unix_args.txt"
if [ ! -f "$SERVER_DIR/$ARGS_FILE" ]; then
  ARGS_FILE="$(cd "$SERVER_DIR" && find libraries -name unix_args.txt -path '*neoforge*' 2>/dev/null | head -n 1 || true)"
fi
if [ -z "$ARGS_FILE" ] || [ ! -f "$SERVER_DIR/$ARGS_FILE" ]; then
  echo "The NeoForge installer did not create unix_args.txt; installed files:"
  (cd "$SERVER_DIR" && find . -maxdepth 2 -not -path './libraries/*' | sort)
  exit 1
fi

# --------------------------------------------------------------- server

start_server() {
  local log="$1"
  echo "::group::Start server (MC $MC, NeoForge $NEO) -> ${log#$ROOT/}"
  # setsid: own process group, so everything the server starts can be stopped together.
  setsid bash -c "cd '$SERVER_DIR' && exec java -Xmx2G @'$ARGS_FILE' nogui" \
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
    if [ "$waited" -ge 600 ]; then
      echo "Server did not finish starting within 600s:"
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

# ---------------------------------------------------------------- checks

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

# The mod must have read the config written above (right file name, value kept).
check_config() {
  echo "Config files in config/:"
  ls -1 "$SERVER_DIR/config" | grep -i itemmilestonelog | sed 's/^/    /' || echo "    (none)"
  local name="${CONFIG_FILE#"$SERVER_DIR"/}"
  # The spec's comments only get into the file if the mod loaded (and corrected) it.
  if grep -qE "^[[:space:]]*scanIntervalTicks[[:space:]]*=[[:space:]]*$SCAN_INTERVAL_TICKS[[:space:]]*$" "$CONFIG_FILE" 2>/dev/null \
      && grep -q 'Inventory scan interval' "$CONFIG_FILE"; then
    echo "  PASS  the mod loaded $name and kept scanIntervalTicks = $SCAN_INTERVAL_TICKS"
  else
    echo "  FAIL  $name was not loaded by the mod, or lost scanIntervalTicks = $SCAN_INTERVAL_TICKS:"
    sed 's/^/    /' "$CONFIG_FILE" 2>/dev/null || echo "    (missing)"
    return 1
  fi
}

# ------------------------------------------------------------------ run

status=0
BOT_FLAG=()
[ "$BOT" = "true" ] && BOT_FLAG=(--bot)

start_server "$LOG_DIR/server-1.log"
node "$TEST_JS" --phase fresh --mc "$MC" --scan-interval-ticks "$SCAN_INTERVAL_TICKS" "${BOT_FLAG[@]}" || status=1
stop_server
check_log "$LOG_DIR/server-1.log" || status=1
check_config || status=1

if [ "$BOT" = "true" ]; then
  case "$MC" in
    1.21.1) data_file="$SERVER_DIR/world/data/itemmilestonelog_data.dat" ;;
    *)      data_file="$SERVER_DIR/world/data/itemmilestonelog/milestones.dat" ;;
  esac
  if [ -f "$data_file" ]; then
    echo "  PASS  saved data written to ${data_file#"$SERVER_DIR"/}"
  else
    echo "  FAIL  expected saved data at ${data_file#"$SERVER_DIR"/}; .dat files under world/:"
    find "$SERVER_DIR/world" -name '*.dat' | sed "s|$SERVER_DIR/||"
    status=1
  fi

  start_server "$LOG_DIR/server-2.log"
  node "$TEST_JS" --phase restart --mc "$MC" "${BOT_FLAG[@]}" || status=1
  stop_server
  check_log "$LOG_DIR/server-2.log" || status=1
fi

exit $status
