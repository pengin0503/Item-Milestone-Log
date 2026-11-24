#!/usr/bin/env bash
# Verifies that files expected to stay byte-identical across Minecraft versions
# have not drifted out of sync (docs/FEATURE_SPEC_1.1.0.md §3.2 (a)).
#
#   .github/scripts/check-identical-files.sh
#
# How to intentionally diverge a file in the future:
#   1. Remove the path from IDENTICAL_ALL_4 or IDENTICAL_26_X below.
#   2. Document the path and the reason for the divergence in the
#      "Intentionally excluded files" comment block below.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ALL_VERSIONS=(1.21.1 26.1.2 26.2 26.3)
VERSIONS_26=(26.1.2 26.2 26.3)

# Files that must be byte-identical across ALL four Minecraft versions
# (1.21.1, 26.1.2, 26.2, 26.3).
IDENTICAL_ALL_4=(
  # src/main/java (17 files)
  "src/main/java/com/pengin0503/itemmilestonelog/DiscordNotifier.java"
  "src/main/java/com/pengin0503/itemmilestonelog/ItemInfoLogic.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestoneBackup.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestoneCommandRunner.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestoneRankings.java"
  "src/main/java/com/pengin0503/itemmilestonelog/ScoreboardSync.java"
  "src/main/java/com/pengin0503/itemmilestonelog/WorldFirstNames.java"
  "src/main/java/com/pengin0503/itemmilestonelog/api/ItemMilestoneLogApi.java"
  "src/main/java/com/pengin0503/itemmilestonelog/api/MilestoneRecord.java"
  "src/main/java/com/pengin0503/itemmilestonelog/api/MilestoneRecordedEvent.java"
  "src/main/java/com/pengin0503/itemmilestonelog/api/PresetCompletedEvent.java"
  "src/main/java/com/pengin0503/itemmilestonelog/api/RecordCause.java"
  "src/main/java/com/pengin0503/itemmilestonelog/api/TargetKey.java"
  "src/main/java/com/pengin0503/itemmilestonelog/api/WorldFirstRecord.java"
  "src/main/java/com/pengin0503/itemmilestonelog/client/ActionRowLayout.java"
  "src/main/java/com/pengin0503/itemmilestonelog/gametest/MilestoneGameTests.java"
  "src/main/java/com/pengin0503/itemmilestonelog/network/TargetEntry.java"

  # src/test/java (20 files)
  "src/test/java/com/pengin0503/itemmilestonelog/DiscordNotifierTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/ItemInfoLogicTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/MilestoneCommandRunnerTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/MilestoneCommandsHelpTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/MilestonePermissionsTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/MilestoneRankingsTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/ScoreboardSyncTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/WorldFirstNamesTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/api/ItemMilestoneLogApiTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/LangFilesTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/MilestoneExportTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/MilestoneServiceTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/PlaytimeTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/TargetKeyTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/TrackerTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/client/ActionRowLayoutTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/network/MilestoneActionPayloadTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/network/ModNetworkingRateLimitTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/network/SyncExtraPayloadTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/network/SyncMilestonesPayloadTest.java"
)

# Files that differ in 1.21.1 (ResourceLocation, NBT SavedData, player.server,
# mods.toml loaderVersion, etc.) but must be byte-identical across 26.1.2, 26.2,
# and 26.3. Together with the 17 src/main/java files in IDENTICAL_ALL_4, this
# covers all 50 src/main/java files that are identical across 26.x (of 57; the
# remaining 7 are the two javadoc-only entry points, the two trigger classes
# behind TriggerCompat, and the three per-version compat implementations).
IDENTICAL_26_X=(
  # src/main/java (33 files; +17 from IDENTICAL_ALL_4 = 50 src/main/java files)
  "src/main/java/com/pengin0503/itemmilestonelog/Config.java"
  "src/main/java/com/pengin0503/itemmilestonelog/DatapackPresetReloadListener.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestoneCommands.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestoneData.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestoneExport.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestonePermissions.java"
  "src/main/java/com/pengin0503/itemmilestonelog/MilestoneService.java"
  "src/main/java/com/pengin0503/itemmilestonelog/Playtime.java"
  "src/main/java/com/pengin0503/itemmilestonelog/Presets.java"
  "src/main/java/com/pengin0503/itemmilestonelog/ServerLang.java"
  "src/main/java/com/pengin0503/itemmilestonelog/TagTargets.java"
  "src/main/java/com/pengin0503/itemmilestonelog/Tracker.java"
  "src/main/java/com/pengin0503/itemmilestonelog/client/ClientMilestoneHandler.java"
  "src/main/java/com/pengin0503/itemmilestonelog/client/ConfirmResetScreen.java"
  "src/main/java/com/pengin0503/itemmilestonelog/client/ItemPickerScreen.java"
  "src/main/java/com/pengin0503/itemmilestonelog/client/MilestoneScreen.java"
  "src/main/java/com/pengin0503/itemmilestonelog/client/PresetListScreen.java"
  "src/main/java/com/pengin0503/itemmilestonelog/client/SavePresetScreen.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/McVersion.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/McCompat.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/TriggerCompat.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/TriggerCompatImpl.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/TagEventCompat.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/ConfigCompat.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/client/ScreenCompat.java"
  "src/main/java/com/pengin0503/itemmilestonelog/compat/client/ClientCompat.java"
  "src/main/java/com/pengin0503/itemmilestonelog/gametest/MilestoneGameTestRegistration.java"
  "src/main/java/com/pengin0503/itemmilestonelog/network/MilestoneActionPayload.java"
  "src/main/java/com/pengin0503/itemmilestonelog/network/ModNetworking.java"
  "src/main/java/com/pengin0503/itemmilestonelog/network/PresetEntry.java"
  "src/main/java/com/pengin0503/itemmilestonelog/network/RequestSyncPayload.java"
  "src/main/java/com/pengin0503/itemmilestonelog/network/SyncExtraPayload.java"
  "src/main/java/com/pengin0503/itemmilestonelog/network/SyncMilestonesPayload.java"

  # src/test/java (3 files)
  "src/test/java/com/pengin0503/itemmilestonelog/MilestoneBackupTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/MilestoneDataTest.java"
  "src/test/java/com/pengin0503/itemmilestonelog/PresetsTest.java"

  # src/main/templates (1 file)
  "src/main/templates/META-INF/neoforge.mods.toml"
)

# Intentionally excluded files (differ across versions by design):
# - src/main/java/.../ItemMilestoneLog.java:
#     Only the version-specific javadoc comment differs across 26.x (the config
#     type and trigger registration went through McCompat in N251). 1.21.1 differs
#     in substance as well.
# - src/main/java/.../client/ItemMilestoneLogClient.java:
#     Only the version-specific javadoc comment differs across 26.x (screen and
#     slot access went through ClientCompat in N251). 1.21.1 uses a String
#     KeyMapping category and differs in substance.
# - src/main/java/.../DatapackPresetReloadListener.java:
#     1.21.1 registers via AddReloadListenerEvent and uses ResourceLocation;
#     26.x registers via AddServerReloadListenersEvent and uses Identifier.
# - Tracker.java is checked across 26.x as of N251: the TagsUpdatedEvent
#     interpretation moved behind TagEventCompat (26.3 removed getUpdateCause()),
#     so the 26.x sources are identical again. 1.21.1 still differs
#     (ResourceLocation, player.server, GameProfile#getName).
# - src/main/java/.../MilestoneRecordedTrigger.java:
#     Minecraft moved advancement packages and changed SimpleInstance#player() across versions:
#     1.21.1 uses advancements.critereon.*; 26.1.2 uses advancements.criterion.*;
#     26.2 uses advancements.triggers.* + advancements.predicates.*;
#     26.3 replaces ContextAwarePredicate / EntityPredicate.ADVANCEMENT_CODEC with
#     Optional<Holder<LootItemCondition>> and LootItemCondition.CODEC.
# - src/main/java/.../ModTriggers.java:
#     1.21.1 and 26.1.2 import net.minecraft.advancements.CriterionTrigger;
#     26.2 and 26.3 import net.minecraft.advancements.triggers.CriterionTrigger.
# - src/main/java/.../compat/{v26_1,v26_2,v26_3}/{TagEventCompatImpl,ConfigCompatImpl,client/ScreenCompatImpl}.java:
#     Per-version compat implementations under compat/v26_1, compat/v26_2, compat/v26_3
#     intentionally differ per version (N251, docs/DESIGN_1.3.0.md);
#     ModTriggers/MilestoneRecordedTrigger likewise remain version-specific behind TriggerCompat.
# - src/test/java/.../ServerLangTest.java:
#     Fallback source directory path embeds each project's own Minecraft version.

status=0
checked=0

check_group() {
  local label="$1"
  local versions_ref="$2"
  local files_ref="$3"
  local -n versions="$versions_ref"
  local -n files="$files_ref"

  local base="${versions[0]}"
  echo "Checking ${#files[@]} file(s) across ${label} (${versions[*]}):"

  for rel in "${files[@]}"; do
    checked=$((checked + 1))
    local base_path="$ROOT/versions/$base/$rel"
    if [ ! -f "$base_path" ]; then
      echo "  FAIL  missing base file: versions/$base/$rel"
      status=1
      continue
    fi

    local file_ok=0
    for ver in "${versions[@]:1}"; do
      local target_path="$ROOT/versions/$ver/$rel"
      if [ ! -f "$target_path" ]; then
        echo "  FAIL  missing file: versions/$ver/$rel"
        file_ok=1
        status=1
        continue
      fi
      if ! cmp -s "$base_path" "$target_path"; then
        echo "  FAIL  differs: versions/$base/$rel vs versions/$ver/$rel"
        diff -u "$base_path" "$target_path" | head -n 40 | sed 's/^/    /' || true
        file_ok=1
        status=1
      fi
    done

    if [ "$file_ok" -eq 0 ]; then
      echo "  PASS  $rel"
    fi
  done
}

check_group "all 4 versions" ALL_VERSIONS IDENTICAL_ALL_4
check_group "26.x versions" VERSIONS_26 IDENTICAL_26_X

if [ "$status" -eq 0 ]; then
  echo "All $checked file check(s) passed."
else
  echo "One or more identical-file checks failed." >&2
fi
exit $status
