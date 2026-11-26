# Shared helpers for the dedicated-server tests. Source this file, then call
# use_server <mc_version> <neoforge_version> before the other functions.
#
# The mod jar under test is the one in versions/<mc>/build/libs (Forge:
# versions/<mc>-forge/build/libs), loaded the way players load it: a production
# server installed by the official NeoForge or Forge installer, not the development
# run.
#
# JAVA (default: java on PATH) is the Java used for the installer and the server.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_JS="$ROOT/.github/integration/server-test.mjs"
# 240 ticks (12 s): long enough to tell the pickup hook (the tests give it 4 s) from
# the inventory scan, short enough that waiting for a scan does not dominate the run.
SCAN_INTERVAL_TICKS=240
JAVA="${JAVA:-java}"
SERVER_JVM_OPTS=()
SERVER_PGID=""
SERVER_LOG=""

# Selects the server that the functions below work on.
use_server() {
  MC="$1"
  NEO="$2"
  LOADER="${3:-neoforge}"
  # The Forge port lives in versions/1.20.1-forge, not versions/1.20.1.
  if [ "$LOADER" = "forge" ]; then
    LIBS="$ROOT/versions/$MC-forge/build/libs"
  else
    LIBS="$ROOT/versions/$MC/build/libs"
  fi
  SERVER_DIR="$ROOT/build/server/$MC-$NEO"
  LOG_DIR="$ROOT/build/server-logs/$MC-$NEO"
  # Config and SavedData names differ per loader generation:
  # - Forge 1.20.1 registers a COMMON config and stores SavedData under the overworld
  #   data directory with the legacy single-file name.
  # - NeoForge 26.3 (FML 12) registers the config as LOCAL, which FML names
  #   <modid>-local.toml; the other NeoForge versions use the common config.
  case "$LOADER:$MC" in
    forge:1.20.1)
      CONFIG_FILE="$SERVER_DIR/config/itemmilestonelog-common.toml"
      DATA_FILE="$SERVER_DIR/world/data/itemmilestonelog_data.dat"
      ;;
    neoforge:26.3)
      CONFIG_FILE="$SERVER_DIR/config/itemmilestonelog-local.toml"
      DATA_FILE="$SERVER_DIR/world/data/itemmilestonelog/milestones.dat"
      ;;
    neoforge:1.21|neoforge:1.21.*)
      CONFIG_FILE="$SERVER_DIR/config/itemmilestonelog-common.toml"
      DATA_FILE="$SERVER_DIR/world/data/itemmilestonelog_data.dat"
      ;;
    *)
      CONFIG_FILE="$SERVER_DIR/config/itemmilestonelog-common.toml"
      DATA_FILE="$SERVER_DIR/world/data/itemmilestonelog/milestones.dat"
      ;;
  esac
  mkdir -p "$LOG_DIR" "$SERVER_DIR"
}

# ------------------------------------------------------------------ setup

install_server() {
  local installer args_dir maven loader_name
  if [ "$LOADER" = "forge" ]; then
    loader_name="Forge $NEO"
    args_dir="libraries/net/minecraftforge/forge/$MC-$NEO"
    installer="forge-$MC-$NEO-installer.jar"
    maven="https://maven.minecraftforge.net/net/minecraftforge/forge/$MC-$NEO"
  else
    loader_name="NeoForge $NEO"
    args_dir="libraries/net/neoforged/neoforge/$NEO"
    installer="neoforge-$NEO-installer.jar"
    maven="https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO"
  fi

  if [ -f "$SERVER_DIR/$args_dir/unix_args.txt" ]; then
    echo "$loader_name server already installed in ${SERVER_DIR#$ROOT/}"
  else
    echo "::group::Install $loader_name server"
    curl -fsSL --retry 3 -o "$SERVER_DIR/$installer" "$maven/$installer"
    if ! (cd "$SERVER_DIR" && "$JAVA" -jar "$installer" --installServer > "$LOG_DIR/installer.log" 2>&1); then
      echo "$loader_name installer failed:"
      tail -n 80 "$LOG_DIR/installer.log"
      echo "::endgroup::"
      return 1
    fi
    rm -f "$SERVER_DIR/$installer" "$SERVER_DIR/$installer.log"
    echo "Installed into ${SERVER_DIR#$ROOT/}"
    echo "::endgroup::"
  fi
  # The installer writes the launch arguments to libraries/.../unix_args.txt (run.sh
  # just calls java with them).
  ARGS_FILE="$args_dir/unix_args.txt"
  if [ ! -f "$SERVER_DIR/$ARGS_FILE" ]; then
    local find_path="*neoforge*"
    [ "$LOADER" = "forge" ] && find_path="*minecraftforge*"
    ARGS_FILE="$(cd "$SERVER_DIR" && find libraries -name unix_args.txt -path "$find_path" 2>/dev/null | head -n 1 || true)"
  fi
  if [ -z "$ARGS_FILE" ] || [ ! -f "$SERVER_DIR/$ARGS_FILE" ]; then
    echo "The $loader_name installer did not create unix_args.txt; installed files:"
    (cd "$SERVER_DIR" && find . -maxdepth 2 -not -path './libraries/*' | sort)
    return 1
  fi
}

install_mod() {
  local jar="${1:-}"
  if [ -z "$jar" ]; then
    shopt -s nullglob
    local jars=()
    local pattern="itemmilestonelog-neoforge-$MC-*.jar"
    [ "$LOADER" = "forge" ] && pattern="itemmilestonelog-forge-$MC-*.jar"
    for f in "$LIBS"/$pattern; do
      case "$f" in *-sources.jar|*-javadoc.jar) ;; *) jars+=("$f") ;; esac
    done
    shopt -u nullglob
    if [ "${#jars[@]}" -ne 1 ]; then
      echo "Expected exactly one mod jar in ${LIBS#$ROOT/}, found: ${jars[*]:-none}"
      return 1
    fi
    jar="${jars[0]}"
  elif [ ! -f "$jar" ]; then
    echo "Specified mod jar does not exist: $jar"
    return 1
  fi
  rm -rf "$SERVER_DIR/mods"
  mkdir -p "$SERVER_DIR/mods"
  cp "$jar" "$SERVER_DIR/mods/"
  echo "Mod under test: ${jar#$ROOT/}"
}

# Fresh world and config, and settings that make the server fast and scriptable.
prepare_server() {
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
  mkdir -p "$SERVER_DIR/config/itemmilestonelog/presets"
  cat > "$SERVER_DIR/config/itemmilestonelog/presets/ci_custom.json" <<'EOF'
{
  "name": "CI Custom Pack",
  "items": [
    "minecraft:emerald",
    "othermod:missing_item"
  ]
}
EOF
  printf '{ broken json' > "$SERVER_DIR/config/itemmilestonelog/presets/broken.json"
  cat > "$SERVER_DIR/config/itemmilestonelog/presets/atm_star.json" <<'EOF'
{
  "name": "Fake ATM Star",
  "items": [
    "minecraft:dirt"
  ]
}
EOF
  # SCAN_INTERVAL_TICKS (see the top of this file) lets the tests tell the pickup hook
  # and the inventory scan apart. NeoForge rewrites a config file that lacks the spec's
  # comments (keeping a .bak of it) but keeps valid values; check_config makes sure
  # this one was used.
  cat > "$CONFIG_FILE" <<EOF
defaultTargets = []
scanIntervalTicks = $SCAN_INTERVAL_TICKS
notifyPlayer = true
broadcastMilestones = true
EOF
}

# --------------------------------------------------------------- server

start_server() {
  local log="$1"
  SERVER_LOG="$log"
  local loader_name="NeoForge $NEO"
  [ "$LOADER" = "forge" ] && loader_name="Forge $NEO"
  echo "::group::Start server (MC $MC, $loader_name) -> ${log#$ROOT/}"
  # setsid: own process group, so everything the server starts can be stopped together.
  # Positional arguments (not interpolated into the shell command) keep paths with
  # quotes or spaces intact.
  setsid bash -c 'cd "$1" && shift && exec "$@"' _ "$SERVER_DIR" "$JAVA" -Xmx2G \
    "${SERVER_JVM_OPTS[@]}" "@$ARGS_FILE" nogui \
    > "$log" 2>&1 < /dev/null &
  SERVER_PGID=$!
  local waited=0
  until grep -q 'Done (' "$log"; do
    if ! kill -0 "$SERVER_PGID" 2>/dev/null; then
      echo "Server process exited before it finished starting:"
      tail -n 200 "$log"
      echo "::endgroup::"
      SERVER_PGID=""
      return 1
    fi
    if [ "$waited" -ge 600 ]; then
      echo "Server did not finish starting within 600s:"
      tail -n 200 "$log"
      echo "::endgroup::"
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "Server is up after ~${waited}s"
  echo "::endgroup::"
}

stop_server() {
  [ -n "$SERVER_PGID" ] || return 0
  node "$TEST_JS" --phase stop || true
  local waited=0
  while kill -0 "$SERVER_PGID" 2>/dev/null && [ "$waited" -lt 120 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$SERVER_PGID" 2>/dev/null; then
    local loader_name="NeoForge $NEO"
    [ "$LOADER" = "forge" ] && loader_name="Forge $NEO"
    echo "::warning::Server (MC $MC, $loader_name) did not stop within 120s after /stop"
    echo "Last lines of ${SERVER_LOG#$ROOT/}:"
    tail -n 30 "$SERVER_LOG" | sed 's/^/    /'
    # The server PID is the JVM (exec java), so its threads show what blocks the shutdown.
    local jstack
    jstack="$(dirname "$(command -v "$JAVA")")/jstack"
    if [ -x "$jstack" ]; then
      "$jstack" "$SERVER_PGID" > "$LOG_DIR/stop-timeout-threads.txt" 2>&1 || true
      echo "Non-daemon threads (full dump in ${LOG_DIR#$ROOT/}/stop-timeout-threads.txt):"
      awk 'BEGIN { RS = "" } /^"/ { split($0, l, "\n"); if (l[1] !~ / daemon /) print $0 "\n" }' \
        "$LOG_DIR/stop-timeout-threads.txt" | head -n 120 | sed 's/^/    /' || true
    fi
    kill -TERM -- "-$SERVER_PGID" 2>/dev/null || true
    waited=0
    while kill -0 "$SERVER_PGID" 2>/dev/null && [ "$waited" -lt 30 ]; do
      sleep 1
      waited=$((waited + 1))
    done
    kill -KILL -- "-$SERVER_PGID" 2>/dev/null || true
  fi
  SERVER_PGID=""
}

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
  # A distinct file per server log: the compat test runs three scenarios in the same
  # LOG_DIR, and a later scenario must not overwrite an earlier scenario's trace.
  local trace="$LOG_DIR/$(basename "$log" .log)-mod-stacktraces.txt"
  if grep -nE '^\s+at com\.pengin0503\.itemmilestonelog' "$log" > "$trace"; then
    echo "  FAIL  stack traces through mod code in the server log:"
    head -n 40 "$trace"
    ok=1
  else
    echo "  PASS  no stack traces through mod code"
  fi
  if grep -nE 'ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError' "$log" | grep -i 'itemmilestonelog\|pengin0503'; then
    echo "  FAIL  linkage errors involving the mod"
    ok=1
  fi
  # The Forge 1.20.1 verification gate (docs/DESIGN_1.20.1.md section 9, gate 2)
  # requires no ERROR in the server log. NeoForge logs are not held to this rule
  # because unrelated loader warnings there are expected.
  if [ "${LOADER:-neoforge}" = "forge" ]; then
    local errors="$LOG_DIR/$(basename "$log" .log)-errors.txt"
    if grep -nE '^\[[^]]*\] \[[^]]*/(ERROR|FATAL)\]' "$log" > "$errors"; then
      echo "  FAIL  ERROR/FATAL lines in the server log:"
      head -n 20 "$errors"
      ok=1
    else
      echo "  PASS  no ERROR/FATAL lines in the server log"
    fi
  fi
  return $ok
}

# The mod must have read the config written by prepare_server (right file name, value kept).
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

check_data_file() {
  if [ -f "$DATA_FILE" ]; then
    echo "  PASS  saved data written to ${DATA_FILE#"$SERVER_DIR"/}"
  else
    echo "  FAIL  expected saved data at ${DATA_FILE#"$SERVER_DIR"/}; .dat files under world/:"
    find "$SERVER_DIR/world" -name '*.dat' | sed "s|$SERVER_DIR/|    |"
    return 1
  fi
}
