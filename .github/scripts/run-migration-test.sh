#!/usr/bin/env bash
# Opens a 1.21.1 world, with records made by the 1.21.1 jar, on a 26.1.2 server and
# checks that the 26.1.2 jar brings the records over (issue #13).
#
#   run-migration-test.sh <neoforge_1.21.1> <neoforge_26.1.2>
#
# Needs the jars in versions/1.21.1/build/libs and versions/26.1.2/build/libs,
# Java 21 and 25 (JAVA_21 and JAVA_25, default: JAVA_HOME_21_X64 and JAVA_HOME_25_X64
# as set by actions/setup-java), curl, Node 22 and "npm ci --prefix .github/integration".
set -euo pipefail

source "$(dirname "$0")/server-lib.sh"

NEO_OLD="$1"
NEO_NEW="$2"
JAVA_21="${JAVA_21:-${JAVA_HOME_21_X64:+$JAVA_HOME_21_X64/bin/java}}"
JAVA_25="${JAVA_25:-${JAVA_HOME_25_X64:+$JAVA_HOME_25_X64/bin/java}}"
JAVA_21="${JAVA_21:-java}"
JAVA_25="${JAVA_25:-java}"
trap stop_server EXIT
status=0

list_data() {
  echo "Files under world/ that hold saved data ($1):"
  (cd "$SERVER_DIR/world" && find . -name '*.dat' -not -path './region/*' -not -path '*/region/*' \
    -not -path './playerdata/*' -not -path '*/playerdata/*' | sort | sed 's/^/    /')
}

# ------------------------------------------------- 1.21.1: make some records
JAVA="$JAVA_21"
use_server 1.21.1 "$NEO_OLD"
install_server
install_mod
prepare_server
start_server "$LOG_DIR/migration-1.log"
node "$TEST_JS" --phase migrate-before --mc 1.21.1 || status=1
stop_server
check_log "$LOG_DIR/migration-1.log" || status=1
check_data_file || status=1
list_data "written by 1.21.1"
OLD_WORLD="$SERVER_DIR/world"

# ------------------------------------- 26.1.2: open the same world, see them
JAVA="$JAVA_25"
use_server 26.1.2 "$NEO_NEW"
install_server
install_mod
prepare_server
cp -a "$OLD_WORLD" "$SERVER_DIR/world"
start_server "$LOG_DIR/migration-2.log"
node "$TEST_JS" --phase migrate-after --mc 26.1.2 || status=1
stop_server
check_log "$LOG_DIR/migration-2.log" || status=1
list_data "after the 26.1.2 server upgraded the world"

if grep -q 'Imported the records of 1 player(s)' "$LOG_DIR/migration-2.log"; then
  echo "  PASS  the 26.1.2 jar imported the 1.21.1 records"
else
  echo "  FAIL  no 'Imported the records' line in the 26.1.2 server log; lines from the mod:"
  grep -i 'itemmilestonelog' "$LOG_DIR/migration-2.log" | tail -n 20 | sed 's/^/    /' || true
  status=1
fi
check_data_file || status=1
legacy="$SERVER_DIR/world/data/itemmilestonelog_data.dat"
if [ -f "$legacy" ]; then
  echo "  PASS  the 1.21.1 file is still there (data/itemmilestonelog_data.dat)"
else
  echo "  FAIL  data/itemmilestonelog_data.dat is gone after the upgrade"
  status=1
fi

exit $status
