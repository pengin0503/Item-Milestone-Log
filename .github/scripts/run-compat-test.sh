#!/usr/bin/env bash
# Verifies network compatibility between the current development version and the
# released 1.0.1 mod jar by launching a real Minecraft client under Xvfb and
# joining a production NeoForge dedicated server (docs/FEATURE_SPEC_1.1.0.md §3.1).
#
#   run-compat-test.sh <mc_version> <neoforge_version> <baseline_1_0_1_dir>
#
# Runs three scenarios against the same Minecraft / NeoForge version:
#   (a) dev client   -> 1.0.1 server  (expects client debug log: Received SyncMilestonesPayload)
#   (b) 1.0.1 client -> dev server    (expects server debug log: Received RequestSyncPayload)
#   (c) dev client   -> dev server    (control: expects both debug logs)
#
# Prerequisites:
#   - Dev jar in versions/<mc>/build/libs/
#   - 1.0.1 jar in <baseline_1_0_1_dir>/versions/<mc>/build/libs/
#   - ModDevGradle client launch scripts created in both checkouts
#     (./gradlew -p versions/<mc> createClientLaunchScript)
#   - Xvfb, xdotool, Mesa software OpenGL (llvmpipe), Java, Node 22 + integration deps
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <mc_version> <neoforge_version> <baseline_1_0_1_dir>" >&2
  exit 2
fi

MC="$1"
NEO="$2"
BASELINE_DIR="$(cd "$3" && pwd)"

source "$(dirname "$0")/server-lib.sh"

use_server "$MC" "$NEO"
COMPAT_LOG_DIR="$ROOT/build/compat-logs/$MC-$NEO"
mkdir -p "$COMPAT_LOG_DIR"

XVFB_DISPLAY=":99"
XVFB_PID=""
CLIENT_PGID=""

cleanup() {
  stop_client
  stop_server
  if [ -n "$XVFB_PID" ] && kill -0 "$XVFB_PID" 2>/dev/null; then
    kill "$XVFB_PID" 2>/dev/null || true
    wait "$XVFB_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

find_mod_jar() {
  local dir="$1"
  shopt -s nullglob
  local jars=()
  for f in "$dir"/itemmilestonelog-neoforge-"$MC"-*.jar; do
    case "$f" in *-sources.jar|*-javadoc.jar) ;; *) jars+=("$f") ;; esac
  done
  shopt -u nullglob
  if [ "${#jars[@]}" -ne 1 ]; then
    echo "Expected exactly one mod jar in $dir, found: ${jars[*]:-none}" >&2
    return 1
  fi
  printf '%s\n' "${jars[0]}"
}

find_client_script() {
  local project_dir="$1"
  local default_script="$project_dir/build/moddev/runClient.sh"
  if [ -f "$default_script" ]; then
    printf '%s\n' "$default_script"
    return 0
  fi
  local found
  found="$(find "$project_dir/build/moddev" -maxdepth 1 -type f -name '*Client*.sh' 2>/dev/null | head -n 1 || true)"
  if [ -z "$found" ]; then
    echo "No ModDevGradle client launch script found under $project_dir/build/moddev" >&2
    return 1
  fi
  printf '%s\n' "$found"
}

write_server_log4j_config() {
  local config_path="$1"
  cat > "$config_path" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="warn">
  <Appenders>
    <Console name="Console" target="SYSTEM_OUT">
      <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level] [%logger]: %msg%n%xEx"/>
    </Console>
    <File name="DebugFile" fileName="logs/debug.log" append="false">
      <PatternLayout pattern="[%d{HH:mm:ss.SSS}] [%t/%level] [%logger]: %msg%n%xEx"/>
    </File>
  </Appenders>
  <Loggers>
    <Logger name="com.pengin0503.itemmilestonelog" level="debug" additivity="false">
      <AppenderRef ref="Console"/>
      <AppenderRef ref="DebugFile"/>
    </Logger>
    <Root level="info">
      <AppenderRef ref="Console"/>
      <AppenderRef ref="DebugFile"/>
    </Root>
  </Loggers>
</Configuration>
EOF
}

# The version line matters: without it the client treats options.txt as a
# pre-1.13 file and the data fixer tries to parse key binding values as
# legacy LWJGL2 numeric codes, which fails on "key.keyboard.m" and wedges
# startup before quick play can connect (observed in the first CI run).
prepare_client_options() {
  local run_dir="$1"
  mkdir -p "$run_dir"
  cat > "$run_dir/options.txt" <<'EOF'
version:3955
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarning:true
key_key.itemmilestonelog.open_gui:key.keyboard.m
EOF
}

# CreateLaunchScriptTask writes `(cd '...'; exec ...)` without forwarding "$@",
# so build a disposable wrapper script that appends --quickPlayMultiplayer.
make_quickplay_script() {
  local src_script="$1"
  local dst_script="$2"
  local host_port="$3"
  python3 - "$src_script" "$dst_script" "$host_port" <<'PY'
import pathlib, sys

src, dst, host_port = sys.argv[1], sys.argv[2], sys.argv[3]
text = pathlib.Path(src).read_text(encoding="utf-8")
lines = text.splitlines()
idx = -1
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip():
        idx = i
        break
if idx < 0 or not lines[idx].rstrip().endswith(")"):
    raise SystemExit(f"Unexpected launch script format in {src}")
line = lines[idx].rstrip()
lines[idx] = f"{line[:-1]} '--quickPlayMultiplayer' '{host_port}')"
out = pathlib.Path(dst)
out.write_text("\n".join(lines) + "\n", encoding="utf-8")
out.chmod(0o755)
PY
}

start_xvfb() {
  echo "::group::Start Xvfb ($XVFB_DISPLAY)"
  Xvfb "$XVFB_DISPLAY" -screen 0 1280x720x24 -ac +extension GLX +render -noreset \
    > "$COMPAT_LOG_DIR/xvfb.log" 2>&1 &
  XVFB_PID=$!
  sleep 2
  if ! kill -0 "$XVFB_PID" 2>/dev/null; then
    echo "Xvfb failed to start:"
    cat "$COMPAT_LOG_DIR/xvfb.log"
    echo "::endgroup::"
    return 1
  fi
  echo "Xvfb running on $XVFB_DISPLAY (pid $XVFB_PID)"
  echo "::endgroup::"
}

stop_client() {
  [ -n "$CLIENT_PGID" ] || return 0
  kill -TERM -- "-$CLIENT_PGID" 2>/dev/null || true
  local waited=0
  while kill -0 "$CLIENT_PGID" 2>/dev/null && [ "$waited" -lt 20 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  kill -KILL -- "-$CLIENT_PGID" 2>/dev/null || true
  CLIENT_PGID=""
}

send_open_gui_key() {
  local win_id=""
  win_id="$(DISPLAY="$XVFB_DISPLAY" xdotool search --name 'Minecraft' 2>/dev/null | head -n 1 || true)"
  if [ -n "$win_id" ]; then
    DISPLAY="$XVFB_DISPLAY" xdotool windowfocus --sync "$win_id" 2>/dev/null || true
    DISPLAY="$XVFB_DISPLAY" xdotool key --window "$win_id" m 2>/dev/null || true
  else
    DISPLAY="$XVFB_DISPLAY" xdotool key m 2>/dev/null || true
  fi
}

has_disconnect_error() {
  local log="$1"
  grep -qiE 'NetworkComponentNegotiator|Failed to negotiate|Disconnected|Connection lost|DecoderException|EncoderException|incompatible.*channel' "$log"
}

check_client_log_errors() {
  local log="$1"
  local ok=0
  if grep -nE '^\s+at com\.pengin0503\.itemmilestonelog' "$log" > "$COMPAT_LOG_DIR/client-mod-stacktraces.txt"; then
    echo "  FAIL  stack traces through mod code in the client log:"
    head -n 40 "$COMPAT_LOG_DIR/client-mod-stacktraces.txt"
    ok=1
  else
    echo "  PASS  no stack traces through mod code in client log"
  fi
  if grep -nE 'ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError' "$log" | grep -Ei 'itemmilestonelog|pengin0503'; then
    echo "  FAIL  linkage errors involving the mod in client log"
    ok=1
  fi
  return $ok
}

run_scenario() {
  local name="$1"
  local client_project="$2"
  local server_jar="$3"
  local expect_client_sync="$4"   # true | false
  local expect_server_req="$5"    # true | false

  echo "================================================================"
  echo "Scenario: $name"
  echo "  client project: ${client_project#$ROOT/}"
  echo "  server jar:     ${server_jar#$ROOT/}"
  echo "================================================================"

  local failed=0
  local server_log="$COMPAT_LOG_DIR/${name}-server.log"
  local client_log="$COMPAT_LOG_DIR/${name}-client.log"
  local server_snap="$COMPAT_LOG_DIR/${name}-server-prestop.log"
  local client_snap="$COMPAT_LOG_DIR/${name}-client-prestop.log"
  local wrapper_script="$COMPAT_LOG_DIR/${name}-runClient.sh"

  local base_client_script
  base_client_script="$(find_client_script "$client_project")"
  make_quickplay_script "$base_client_script" "$wrapper_script" "localhost:25565"
  prepare_client_options "$client_project/run"

  install_mod "$server_jar"
  prepare_server
  start_server "$server_log"

  echo "::group::Start client ($name) -> ${client_log#$ROOT/}"
  setsid env \
    DISPLAY="$XVFB_DISPLAY" \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    MESA_GL_VERSION_OVERRIDE=4.5 \
    ALSOFT_DRIVERS=null \
    bash "$wrapper_script" > "$client_log" 2>&1 < /dev/null &
  CLIENT_PGID=$!
  echo "Client launched (pgid $CLIENT_PGID)"
  echo "::endgroup::"

  # 1. Wait for the client to join the server (up to 300s for cold llvmpipe startup).
  local joined=0
  local waited=0
  while [ "$waited" -lt 300 ]; do
    if grep -q 'joined the game' "$server_log"; then
      joined=1
      echo "Client joined the server after ~${waited}s"
      break
    fi
    if ! kill -0 "$CLIENT_PGID" 2>/dev/null; then
      echo "  FAIL  client process exited before joining the server"
      failed=1
      break
    fi
    if has_disconnect_error "$client_log" || has_disconnect_error "$server_log"; then
      echo "  FAIL  disconnect or negotiation failure observed while joining"
      failed=1
      break
    fi
    sleep 2
    waited=$((waited + 2))
  done

  if [ "$joined" -eq 0 ] && [ "$failed" -eq 0 ]; then
    echo "  FAIL  client did not join the server within 300s"
    failed=1
  fi

  # 2. Give the client a few seconds to enter gameplay, then press 'm' to open GUI
  #    and wait for the expected debug log lines.
  if [ "$joined" -eq 1 ]; then
    sleep 5
    local sync_ok=0
    local attempt=0
    while [ "$attempt" -lt 20 ]; do
      send_open_gui_key
      sleep 3

      local client_seen=1
      local server_seen=1
      if [ "$expect_client_sync" = "true" ]; then
        grep -q '\[ItemMilestoneLog\] Received SyncMilestonesPayload' "$client_log" || client_seen=0
      fi
      if [ "$expect_server_req" = "true" ]; then
        grep -q '\[ItemMilestoneLog\] Received RequestSyncPayload' "$server_log" || server_seen=0
      fi

      if [ "$client_seen" -eq 1 ] && [ "$server_seen" -eq 1 ]; then
        sync_ok=1
        echo "  PASS  observed expected sync debug log(s) after $((attempt + 1)) key press(es)"
        break
      fi
      if ! kill -0 "$CLIENT_PGID" 2>/dev/null; then
        echo "  FAIL  client exited while waiting for GUI sync"
        break
      fi
      attempt=$((attempt + 1))
    done

    if [ "$sync_ok" -eq 0 ]; then
      if [ "$expect_client_sync" = "true" ] && ! grep -q '\[ItemMilestoneLog\] Received SyncMilestonesPayload' "$client_log"; then
        echo "  FAIL  client log missing '[ItemMilestoneLog] Received SyncMilestonesPayload'"
      fi
      if [ "$expect_server_req" = "true" ] && ! grep -q '\[ItemMilestoneLog\] Received RequestSyncPayload' "$server_log"; then
        echo "  FAIL  server log missing '[ItemMilestoneLog] Received RequestSyncPayload'"
      fi
      failed=1
    fi
  fi

  # Snapshot logs BEFORE terminating the client so normal shutdown disconnects are not
  # confused with protocol negotiation / decoding disconnects.
  cp "$server_log" "$server_snap"
  cp "$client_log" "$client_snap"

  if has_disconnect_error "$server_snap"; then
    echo "  FAIL  disconnect / negotiation error in server log before shutdown:"
    grep -inE 'NetworkComponentNegotiator|Failed to negotiate|Disconnected|Connection lost|DecoderException|EncoderException|incompatible.*channel' "$server_snap" | head -n 20 | sed 's/^/    /'
    failed=1
  else
    echo "  PASS  no disconnect / negotiation errors in server log before shutdown"
  fi

  if has_disconnect_error "$client_snap"; then
    echo "  FAIL  disconnect / negotiation error in client log before shutdown:"
    grep -inE 'NetworkComponentNegotiator|Failed to negotiate|Disconnected|Connection lost|DecoderException|EncoderException|incompatible.*channel' "$client_snap" | head -n 20 | sed 's/^/    /'
    failed=1
  else
    echo "  PASS  no disconnect / negotiation errors in client log before shutdown"
  fi

  stop_client
  stop_server

  check_log "$server_log" || failed=1
  check_client_log_errors "$client_log" || failed=1

  if [ "$failed" -ne 0 ]; then
    echo "::group::Tail of logs for failed scenario ($name)"
    echo "--- server log (last 80 lines) ---"
    tail -n 80 "$server_log" || true
    echo "--- client log (last 80 lines) ---"
    tail -n 80 "$client_log" || true
    echo "::endgroup::"
  fi

  return $failed
}

DEV_PROJECT="$ROOT/versions/$MC"
OLD_PROJECT="$BASELINE_DIR/versions/$MC"
DEV_JAR="$(find_mod_jar "$DEV_PROJECT/build/libs")"
OLD_JAR="$(find_mod_jar "$OLD_PROJECT/build/libs")"

LOG4J_CONFIG="$COMPAT_LOG_DIR/log4j2-compat.xml"
write_server_log4j_config "$LOG4J_CONFIG"
SERVER_JVM_OPTS=("-Dlog4j2.configurationFile=$LOG4J_CONFIG" "-Dlog4j.configurationFile=$LOG4J_CONFIG")

install_server
start_xvfb

status=0
run_scenario "a-dev-client-to-1.0.1-server" "$DEV_PROJECT" "$OLD_JAR" true false || status=1
run_scenario "b-1.0.1-client-to-dev-server" "$OLD_PROJECT" "$DEV_JAR" false true || status=1
run_scenario "c-dev-client-to-dev-server"   "$DEV_PROJECT" "$DEV_JAR" true true || status=1

exit $status
