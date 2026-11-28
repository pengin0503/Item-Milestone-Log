# 1.5.0 step-3 pre-release audit fixes — progress / results

Branch: `release/1.5.0`, created from step-3 completion `67396ab` (NOT main; main contains
steps 4–6). Audit source: `~/workspace/iml-tools/audit-150-report.md`. Scope per brief:
RB-1, SF-1..SF-10 (SF-11 explicitly out of scope), MN-1/2/4/5/7 fixed; MN-3/6/8/9 no
product change; MN-10 covered by the regression tests added below. No `./gradlew` used.

## Per-finding changes

### RB-1 — import replaces world-first holder without history
- `versions/<tree>/src/main/java/.../MilestoneData.java` (all five trees): `replaceWorldFirsts`
  now diffs old vs. incoming per key and calls `appendPastHolder(key, oldRecord)` for every
  replaced (record differs) or removed key before clearing/putAll. No-op merges append nothing.
  This also covers the `MilestoneImportAll` rollback caller and `MilestoneExport.applyMergePlan`.
- Test: `MilestoneDataTest.replaceWorldFirstsAppendsHistoryForReplacedAndRemovedKeys` in all
  five trees (earlier-epoch incoming record + removed key + no-op case).

### SF-1 — bulk addsteps / bulk expandtag had no undo snapshot
- `MilestoneCommands.java` (all five trees): `addSteps` now honours `fromBulk`; after a
  confirmed run with `applied > 0` it offers `UndoLogic.Snapshot.bulkRemoval(added, Map.of(), now)`
  and sends `undo_hint`; never on preview/zero-change. `bulkExpandTag` does the same. New
  package-private helper `addedKeys(...)` (normalize + dedupe + skip invalid) feeds the snapshot.
- Test: `MilestoneCommandsLogicTest.addedKeysNormalizesAndDeduplicatesValidRefs`,
  `.addedKeysSkipsNullInvalidAndOutOfRangeRefs` in all five trees.

### SF-2 — undo did not restore targetMeta
- Shared `UndoLogic.java`: `Snapshot` gains an 8th component `Map<String, TargetMeta>
  removedTargetMeta` (defensive copy), with new `reset(..., long, Map)` / `bulkRemoval(..., long,
  Map)` overloads (old factories delegate with `Map.of()`). `Result` gains a 9th component
  `targetMeta`; `restore(...)` gains a final `Map<String, TargetMeta> currentTargetMeta` and
  merges removed metadata with `putIfAbsent` (metadata set after removal wins).
- `MilestoneService.java` (all five trees): new `takeTargetMeta(...)` helper captures metadata
  before each undoable removal in `presetremove` / `removeAllTargets`; `applyUndo` restores
  `pd.targetMeta` from the new Result. `MilestoneCommands.bulkCleanup` captures its removals
  into the 4-arg `bulkRemoval` overload.
- Tests: shared `UndoLogicTest.bulkRemovalRestoresRemovedTargetMetaWithoutOverwritingFreshValues`;
  per-tree `MilestoneServiceTest.removeAllTargetsUndoRestoresTargetMetaWithoutOverwritingFreshValues`.

### SF-3 — dry-run counts disagreed with a real import
- Shared `CommandAnalysisModel.importPreview`: an absent local record now counts as a write
  (`currentTicks == null || importedTicks < currentTicks`); null imported ticks still skipped.
  The displayed `recordsToOverwrite` line keeps its name/wording and now covers adds.
- `MilestoneCommands.importAllDryRun` (all five trees): world-first count now comes from
  `MilestoneExport.mergeWorldFirsts(...).updatedCount()` (same dedup + earlier-epoch rule as the
  real merge) instead of a hand-rolled loop.
- Tests: shared `CommandAnalysisModelTest.importPreviewCountsAbsentRecordsButNotEqualLargerOrNullTicks`
  plus corrected the pre-existing codified-wrong assertion in
  `importPreviewComputesTargetsOverwritesFirstsAndSkipped` (1 → 2, message fixed).

### SF-4 — sharetargets semantics / revoke invalidation
- `MilestoneCommands.java` (all five trees): `offer` encodes the CALLER's targets and stores the
  code keyed by the recipient UUID; `accept` requires a live pending code for the acceptor before
  preview or confirm and consumes it on a successful confirmed apply; `revoke` removes the
  recipient's pending code through the same helpers, so a revoked code no longer accepts.
  Reused existing `sharetargets_invalid` key; no new lang keys. New package-private helpers
  `putPendingShareCode`, `pendingShareCode`, `pendingShareCodeMatches`, `removePendingShareCode`,
  `consumePendingShareCode`.
- Tests: `MilestoneCommandsLogicTest.pendingShareCodeIsKeyedByRecipientOnly`,
  `.pendingShareCodeMatchesRejectsMismatchAndMissingCodes`,
  `.pendingShareCodeRevocationInvalidatesThePresentedCode`,
  `.pendingShareCodeIsConsumedOnlyByAnExactMatch` in all five trees.

### SF-5 — rename orphaned targetMeta / holder-history keys
- `MilestoneService.java` (all five trees): new `rewriteEncodedKeys(...)` helper migrates
  `TargetKey.toEncodedString()` keys in `pd.targetMeta` and in the data-level
  `firstsHolderHistory` alongside the existing item/tag rewrites; migration is skipped when
  `apply == false`. History is recomputed AFTER `replaceWorldFirsts` so rows appended by RB-1
  under the old key migrate too. Collision rule: the migrated value wins (documented in code).
  Metadata-only changes now count as a player change but do not alter `RenameCounts` fields.
- Uses new package-private `MilestoneData.replaceFirstsHolderHistory(...)` mutator added in all
  five trees (clear + putAll + setDirty only when the map actually changes).
- Tests: per-tree `MilestoneServiceTest.renameItemInDataMigratesTargetMetaAndHolderHistoryKeysOnlyWhenApplied`
  and `.renameTagInDataMigratesTargetMetaAndHolderHistoryKeysOnlyWhenApplied`.

### SF-6 — preset protect wrote before confirm
- `MilestoneCommands.presetProtect` (all five trees): both `protect` and `unprotect` are now
  preview-only without `confirm`, via `presetProtectApplies(protect, confirm)`. New lang key
  `message.itemmilestonelog.preset_protect_confirm` added once to `common/resources/.../en_us.json`
  and `ja_jp.json` (one `%s` each, same slot, symmetric key sets). Command registration unchanged
  (both forms still parse).
- Tests: `MilestoneCommandsLogicTest.presetProtectAppliesOnlyWithConfirmForBothDirections` in all
  five trees; registry `LangFilesTest` / `ServerLangTest` re-run for key/arity parity.

### SF-7 — one wrong-typed history value discarded all sibling histories (26.x)
- `MilestoneData.java` in 26.1.2/26.2/26.3: new `LENIENT_HOLDER_HISTORY_LIST_CODEC` wraps
  `Codec.list(LENIENT_HOLDER_ENTRY_CODEC)`; a non-list value records one element skip and decodes
  to `List.of()` instead of failing the enclosing map. Used as the `firstsHolderHistory` map value.
- Tests: `MilestoneDataTest.firstsHolderHistoryLoaderKeepsValidSiblingsWhenOneValueIsNotAList`
  in the three 26.x trees.

### SF-8 — prunedata ignored targetMeta
- Shared `PruneDataLogic.PlayerState` gains `boolean targetMetaEmpty` (plus a 10-arg backwards-
  compatible constructor defaulting it to `true`), and `isEmptyWithDefaultSettings` requires it.
- `MilestoneService.pruneStateOf` (all five trees) passes `pd.targetMeta.isEmpty()`.
- Tests: shared `PruneDataLogicTest.nonEmptyTargetMetaExcludesPlayerFromPruning`; per-tree
  `MilestoneServiceTest.pruneStateOfReportsNonEmptyTargetMetaAsFunctionalState`.

### SF-9 — isEmpty() ignored holder history (backup gate)
- `MilestoneData.isEmpty()` in all five trees now also requires `firstsHolderHistory.isEmpty()`
  (`lastDigestEpoch` deliberately NOT added, to avoid changing unrelated backup behavior).
- Test: `MilestoneDataTest.isEmptyIsFalseWhenOnlyHolderHistoryRemains` in all five trees.

### SF-10 — granttag overwrote existing records
- `MilestoneCommands.adminGrantTag` (all five trees) routes through new helper
  `grantTagRecord(existing, candidateTicks, epoch)`, which keeps an existing record unchanged
  (matching `Tracker`'s `ALREADY_RECORDED` semantics) and only creates a record when absent.
- Tests: `MilestoneCommandsLogicTest.grantTagRecordKeepsAnExistingRecordUnchanged` and
  `.grantTagRecordCreatesARecordWhenAbsent` in all five trees.

### SF-11 — OUT OF SCOPE
- F-150-28 (`help search`) / F-150-34 (`analyze`) deliberately deferred per the spec's own PR
  split. No code, no `CommandHelp` entry, and NO stub lang keys were added.

### MN-1 — loader kept oldest 20 history entries
- `MilestoneData` load paths (26.x `fromCodec`, NBT trees' root history reader): replaced the
  `size >= LIMIT → break` truncation with `while (size > LIMIT) remove(0)` so the newest 20 survive.
- Test: `MilestoneDataTest.firstsHolderHistoryLoaderKeepsNewestTwentyEntries` in all five trees.

### MN-2 — undo <generation> on empty history
- `MilestoneCommands.undoGeneration` (all five trees): explicit `available == 0` branch using
  the existing `undo_list_empty` key; the non-empty invalid-generation message keeps the real
  available count. No new lang key. No separate handler test (selection logic is two branches;
  covered by the existing undo command tests + registry/lang checks).

### MN-4 — NBT/CODEC targetMeta subfield defaults
- NBT trees (1.20.1-forge, 1.21.1) `MilestoneData` reader: absent `priority` / `label` / `pinned`
  now keep the 0 / "" / false defaults; wrong-TYPE subfields still mark the entry malformed.
- Tests: `MilestoneDataTest.targetMetaReaderDefaultsMissingFieldsAndSkipsWrongTypes` in both trees.
- Existing `targetMetaLoaderSkipsMalformedElementsAndKeepsValidSiblings` expectations in both
  NBT trees were updated: the `missing_fields` fixture now loads with defaults and the expected
  element-skip count drops 5 → 4. This is the NEW correct behavior, not a weakened assertion.

### MN-5 — targetMeta keys never validated on load
- `MilestoneData` (all five trees): CODEC factory and NBT reader now call
  `TargetKey.tryParseEncoded(key)`; invalid keys record one element skip and are skipped, valid
  siblings survive.
- Test: `MilestoneDataTest.targetMetaLoaderSkipsInvalidEncodedKeysAndKeepsValidOnes` in all five trees.

### MN-7 — pin accepted any word as "off"
- `MilestoneCommands.attachMetaOptions` (all five trees): the three `metaPin` word arguments were
  replaced by literal `on`/`off` children (`pinLiterals()` builder) feeding a `@Nullable Boolean
  pinLiteral` into `targetMetaApply` (legacy `metaPin` parsing retained for compatibility).
- Tests: `MilestoneCommandsLogicTest.pinLiteralValueIsTrueOnlyForOn` in all five trees;
  `MilestoneCommandsHelpTest` needed NO expectation change — its pin assertion (`pin on`) still
  parses with literal children and no registration assertion references `metaPin`.

### MN-3 / MN-6 / MN-8 / MN-9 — no product change (intentional)
- MN-3: skipped. Cosmetic undo-list label only; no spec text mandates it and a fix would add a
  new message path with no functional benefit.
- MN-6: intentional. Empty optional containers are still written for the 26.x CODEC path,
  matching the pre-existing `presetState` precedent and preserving backward compatibility; NBT
  trees continue to omit them. Not a defect for this release.
- MN-8: skipped. Seven call sites and a new "nothing to change" lang key would be needed; all
  printed counts are truthful, so this is cosmetic and not worth the churn before release.
- MN-9: intentional. `resetall` keeps `targetMeta` because metadata is meaningful only while its
  target exists (`clearAllPlayerState` deliberately keeps `targets` too); no ghost entry results.
  `clearAllPlayerState` was left untouched.

### MN-10 — test gaps
- Covered by the per-finding regression tests above: import holder history (RB-1), bulk undo
  snapshots (SF-1), share/revoke semantics (SF-4), undo restoration of metadata (SF-2), and
  load of new fields incl. `targetMeta` / `firstsHolderHistory` in all five trees (MN-4/5/SF-7).
  New command-decision helpers were introduced specifically so handler-level semantics are
  unit-testable without a running server.

## Verification (real runs, hand-built JUnit launcher; no Gradle)

Script: `.scratch/verify-150-audit-fixes.sh` (compiles all five trees from scratch with the
JDK matching each tree, then runs the affected + registry-sensitive classes once per tree).
Final run: `.scratch/verify-150-audit-fixes-run3.log`, `VERIFY_EXIT=0`.

| Tree | Main/test sources compiled | Tests successful | Tests failed | Runner exit |
|---|---|---|---|---|
| 1.21.1 | 136 / 83 | 321 | 0 | 0 |
| 1.20.1-forge | 127 / 80 | 321 | 0 | 0 |
| 26.1.2 | 115 / 82 | 327 | 0 | 0 |
| 26.2 | 115 / 82 | 327 | 0 | 0 |
| 26.3 | 115 / 82 | 327 | 0 | 0 |

Classes run once per tree: `CommandAnalysisModelTest`, `UndoLogicTest`, `PruneDataLogicTest`,
`FirstsHolderHistoryTest`, `WorldFirstImportLogicTest`, `CommandHelpTest`, `LangFilesTest`,
`ServerLangTest`, `MilestoneDataTest`, `MilestoneServiceTest`, `MilestoneCommandsLogicTest`,
`MilestoneCommandsHelpTest`, `PresetsTest`, `MilestoneExportTest`, `MilestoneImportAllTest`.

Two earlier runs are preserved as evidence of the real debugging sequence:
- run1: all five trees failed at discovery — `PermissionNodeMappingTest` does not exist at the
  step-3 base commit (added only later on main: `git log --all` shows it introduced in
  `678760e`/`de39da9`; `git ls-tree HEAD` has `CommandHelpTest` but not this class). The selector
  was removed; the other three registry-sensitive classes still run once per tree. **Deviation
  from the brief, documented here rather than faked.**
- run2: two genuine test defects surfaced and were fixed: a `null` itemId `TargetRef` in the new
  `addedKeys` test (constructor rejects null) and the obsolete NBT `missing_fields` expectation
  (see MN-4 above).

`git diff --check`: clean (exit 0). SHA-256: all six changed per-tree files are byte-identical
across 26.1.2 / 26.2 / 26.3. Shared `common/` files edited once; 26.x copies of the three
per-tree main files and three per-tree test files verified identical after all fixes.

## Other deviations / notes
- New test class `MilestoneCommandsLogicTest.java` added to each tree (untracked before commit;
  must be staged explicitly).
- New non-public helper APIs: `MilestoneData.replaceFirstsHolderHistory`, `MilestoneCommands`
  `addedKeys` / five pending-share helpers / `presetProtectApplies` / `grantTagRecord` /
  `pinLiteralValue`; shared `UndoLogic` Snapshot/Result/restore signatures extended, and
  `PruneDataLogic.PlayerState` gained a component with an arity-compatible constructor.
- `granttag` still sends the existing `admin_granted` success message when an existing record is
  preserved; no new lang key was introduced for that path.
- `importPreview` keeps the field name `recordsToOverwrite` but now counts adds as well, matching
  the real import and the four-line dry run contract.
