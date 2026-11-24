#!/usr/bin/env bash
# Installs a NeoForge dedicated server, puts the built mod jar into its mods folder
# and runs .github/integration/server-test.mjs against it.
#
#   run-server-tests.sh <mc_version> <neoforge_version> <bot: true|false> [mod_jar]
#
# The jar under test is the one in versions/<mc>/build/libs, loaded the way players
# load it (a production server, not the development run). CI builds that jar against
# the minimum NeoForge version, as for a release, and runs it on both the minimum and
# the latest NeoForge. With the optional 4th argument, that exact jar file is tested
# instead of the one in versions/<mc>/build/libs (used for the merged 26.x jar,
# which must run on every 26.x version).
#
# With bot=true a vanilla-protocol client (mineflayer) joins, the server is then
# restarted and the client checks that the saved data came back.
#
# Needs Java (21 for 1.21.1, 25 for 26.x), curl, Node 22 and
# "npm ci --prefix .github/integration".
set -euo pipefail

source "$(dirname "$0")/server-lib.sh"

BOT="$3"
use_server "$1" "$2"
trap stop_server EXIT

install_server
install_mod "${4:-}"
prepare_server

status=0
BOT_FLAG=()
[ "$BOT" = "true" ] && BOT_FLAG=(--bot)

start_server "$LOG_DIR/server-1.log"
node "$TEST_JS" --phase fresh --mc "$MC" --scan-interval-ticks "$SCAN_INTERVAL_TICKS" --server-dir "$SERVER_DIR" "${BOT_FLAG[@]}" || status=1
stop_server
check_log "$LOG_DIR/server-1.log" || status=1
check_config || status=1

if [ "$BOT" = "true" ]; then
  check_data_file || status=1

  start_server "$LOG_DIR/server-2.log"
  node "$TEST_JS" --phase restart --mc "$MC" --server-dir "$SERVER_DIR" "${BOT_FLAG[@]}" || status=1
  stop_server
  check_log "$LOG_DIR/server-2.log" || status=1
fi

exit $status
