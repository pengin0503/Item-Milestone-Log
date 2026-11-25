#!/usr/bin/env bash
# Layer-2 client smoke test: injects the smoke hook into a THROWAWAY harness project.
#
#   tools/smoketest/inject.sh <project_dir>
#
# Copies tools/smoketest/java/ into <project_dir>/src/main/java/ and appends a
# runs.client block to <project_dir>/build.gradle that forwards -Piml.smoketest=1
# (and the optional -Piml.smoketest.* tuning properties) to the client JVM as system
# properties and sizes the window to 1280x720.
#
# Only the harness generators call this (tools/prepare-121x-harness.sh and
# tools/prepare-26x-harness.sh, both when IML_SMOKETEST=1). It refuses to touch the
# real release projects versions/<mc>/, so production jars never contain smoke classes.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <project_dir>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
proj="$(cd "$1" && pwd)"

case "$proj" in
  "$ROOT"/versions/verify/*) ;;
  *)
    echo "Error: refusing to inject the smoke hook outside versions/verify/: $proj" >&2
    exit 1
    ;;
esac

if [[ ! -f "$proj/build.gradle" || ! -d "$proj/src/main/java" ]]; then
  echo "Error: not a mod project (build.gradle / src/main/java missing): $proj" >&2
  exit 1
fi

cp -R "$ROOT/tools/smoketest/java/." "$proj/src/main/java/"

# Dev runs register the mod's GameTests (NeoForge GameTestHooks.isGametestEnabled() is
# true in dev, false in production). On 1.21.5+ the inline GameTestInstance codec is not
# in the test_instance_type registry, so registry sync to a joining client fails and the
# dev client can never enter a singleplayer world. Production never registers the tests,
# so the smoke harness drops the registration listener to match production.
main_class="$proj/src/main/java/com/pengin0503/itemmilestonelog/ItemMilestoneLog.java"
gt_line='modEventBus.addListener(MilestoneGameTestRegistration::register);'
if grep -qF "$gt_line" "$main_class"; then
  sed -i "s|^\(\s*\)modEventBus.addListener(MilestoneGameTestRegistration::register);|\1// smoke harness: GameTest registration removed (production never registers them)|" "$main_class"
  echo "GameTest registration listener removed from the harness copy of ItemMilestoneLog.java"
fi

marker="// ---- IML client smoke test (tools/smoketest/inject.sh) ----"
if ! grep -qF "$marker" "$proj/build.gradle"; then
  cat >> "$proj/build.gradle" <<'GRADLE'

// ---- IML client smoke test (tools/smoketest/inject.sh) ----
// Verify harness only. `runClient -Piml.smoketest=1` arms the hook; without the
// property the hook class is present but inert.
neoForge {
    runs {
        client {
            ['iml.smoketest', 'iml.smoketest.timeoutSec', 'iml.smoketest.holdTicks',
             'iml.smoketest.settleTicks', 'iml.smoketest.world'].each { key ->
                def value = project.findProperty(key)
                if (value != null) {
                    systemProperty key, value.toString()
                }
            }
            programArguments.addAll '--width', '1280', '--height', '720'
        }
    }
}
GRADLE
fi

count="$(find "$ROOT/tools/smoketest/java" -name '*.java' | wc -l)"
echo "Smoke hook injected into $proj ($count source file(s))"
