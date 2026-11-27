# PR-3 — 150-commands write operations (N08 / N97 / N165 / N11 / N20 / N154 / N99 / N127 / N155)

Branch: `feat/150-commands-3` (base `origin feat/150-commands-2` = `77157b842ab48146df3d277d70c3bb99a5c254cc`).
Spec authority: `spec-150-commands.md` in the work directory. One local commit; not pushed. PR #168 remains OPEN/unmerged; main 1.4.0 RC frozen. No Gradle invocation, no new dependencies, no GUI/client payload changes.

## Per-item summary

| Item | Spec section | Files changed | Tests |
|---|---|---|---|
| F-150-13 `copytargets` (N08) | §248 | `CommandAnalysisModel.java` (copy set math), all five `MilestoneCommands.java` (top-level literal, `player` word arg + `confirm`, gates, handlers), `CommandHelp.java`, `en_us.json`/`ja_jp.json`, `MilestoneCommandsHelpTest.java` (all trees) | `CommandAnalysisModelTest`, `MilestoneCommandsHelpTest` (two-step shape, `Gate.LOOKUP`, parse), `CommandHelpTest`, `LangFilesTest`, `ServerLangTest` |
| F-150-14 `admin <player> copyfrom` (N97) | §294 | Five `MilestoneCommands.java` (three-slot preview/done, reuses N08 model, `MilestoneService.adminAddTarget`); lang + help test | `MilestoneCommandsHelpTest` (admin subtree, inherited admin node only), `LangFilesTest` |
| F-150-37 `sharetargets` (N165) | §1407 | Five `MilestoneCommands.java` (`offer`/`accept`/`revoke`, `PresetShareLogic` encode/decode, in-memory `ConcurrentHashMap<UUID,String>`); lang + help test | `MilestoneCommandsHelpTest` (3 branches, parse), `CommandAnalysisModelTest` (`sharePlan`), `LangFilesTest` |
| F-150-16 admin tag ops (N11) | §387 | Five `MilestoneCommands.java` (`addtag`/`removetag`/`granttag` under `admin <player>`; no new permission node); lang help keys + help test | `MilestoneCommandsHelpTest` (literals under admin, offline-capable handlers, existing `resettag` retained), `LangFilesTest` |
| F-150-17 `addsteps` (N20) | §438 | Five `MilestoneCommands.java` (`<item>` and `tag` branches, `confirm`, `CommandAnalysisModel.parseSteps`/`stepsFor`); lang + help test | `CommandAnalysisModelTest` (ascending/dup/decimal/negative/21-values/capacity/tag keys), `MilestoneCommandsHelpTest`, `LangFilesTest` |
| F-150-30 bulk add/tag expansion/cleanup (N154) | §1060 | Five `MilestoneCommands.java` (`preview`, `addsteps`, `expandtag`, `cleanup`), `MilestoneService.java` metadata cleanup; lang + help test | `CommandAnalysisModelTest` (`bulkPlan`, union/caps), `MilestoneCommandsHelpTest`, `LangFilesTest` |
| F-150-25 undo generations (N99) | §818 | `UndoLogic.java`/`UndoLogicTest.java` (stack, depth 5, `list`/`get`, consume drops newer), `MilestoneService.java` (generation overload; plain `undo` still consumes newest), five `MilestoneCommands.java` (`undo list`, `undo <generation>`); lang + help test | `UndoLogicTest` (20 tests), `MilestoneCommandsHelpTest`, `LangFilesTest`, plain-`undo` regression covered by existing tests |
| F-150-26 import dry-run (N127) | §868 | Five `MilestoneCommands.java` (`import <file> dryrun`, `importall <file> dryrun`, no `MilestoneData` writes; `importPreview` + live plan diff), lang + help test | `CommandAnalysisModelTest` (`importPreview`), `MilestoneCommandsHelpTest`, `LangFilesTest`; existing import usage regression retained |
| F-150-31 target metadata (N155) | §1110 | New `TargetMeta.java`/`TargetMetaTest.java`, five `MilestoneData.java`/`MilestoneDataTest.java` (optional `targetMeta`, lenient per element, no schemaVersion bump), five `MilestoneService.java` (cleanup on target removal), five `MilestoneCommands.java` (`targets meta ...`, `targets meta clear`, `pinned`), lang + help test | `TargetMetaTest` (7), `MilestoneDataTest` (4 new per tree: missing field, round-trip, mixed malformed siblings, downgrade strip), `MilestoneCommandsHelpTest`, `LangFilesTest` |

Shared-file discipline: 26.1.2/26.2/26.3 copies of `MilestoneCommands.java`, `MilestoneService.java`, `MilestoneData.java`, `MilestoneDataTest.java` are byte-identical (copied from 26.3); `MilestoneCommandsHelpTest.java` is byte-identical across all five trees. `.github/scripts/check-identical-files.sh` passed **all 116 checks**. `git diff --check` clean.

## Real verification counts (hand-built JUnit launcher; no `./gradlew`)

Eight affected/new classes run once per tree: `CommandAnalysisModelTest`, `UndoLogicTest`, `TargetMetaTest`, `CommandHelpTest`, `MilestoneCommandsHelpTest`, `LangFilesTest`, `ServerLangTest`, `MilestoneDataTest`.

| Tree | Found | Successful | Failed | Exit |
|---|---:|---:|---:|---:|
| 1.21.1 | 145 | 145 | 0 | 0 |
| 1.20.1-forge | 145 | 145 | 0 | 0 |
| 26.1.2 | 154 | 154 | 0 | 0 |
| 26.2 | 154 | 154 | 0 | 0 |
| 26.3 | 154 | 154 | 0 | 0 |
| **Total** | **752** | **752** | **0** | — |

Logs: `.scratch/test-150-pr3-<tree>.log`; summary: `.scratch/verify-150-pr3-summary.log`. All five trees compiled main+test cleanly with the Gradle-equivalent javac/classpath (JDK 21 / 17 / 25 as appropriate); FML classloader used for 26.x, manual Minecraft bootstrap for 1.21.1/Forge.

Subagent pre-integration real counts (also all green): `CommandAnalysisModelTest` 45/45, `UndoLogicTest` 20/20, `TargetMetaTest` 7/7 plus 4/4 new `MilestoneDataTest` cases per tree (19/19 executed by the agent; the full five-tree suite above re-ran them).

## Language keys

- 47 new keys added to **each** of `en_us.json` and `ja_jp.json` (94 new key lines total), covering all 45 keys required by the spec plus 2 auxiliary keys listed below. Final key count: 605 per language. Placeholder counts and `%s`-only format verified by `LangFilesTest`; key sets identical across languages and trees.
- `CommandHelp.ENTRIES` gained 5 rows: `copytargets` (LOOKUP), `sharetargets` (LOOKUP), `addsteps` (NONE), `bulk` (NONE), `pinned` (NONE). Six auxiliary `help.*` keys were added to the help-test key set: `admin_copyfrom`, `admin_addtag`, `admin_removetag`, `admin_granttag`, `import_dryrun`, `undo_list`.

## Deviations and reasons

1. **`<steps>` uses `StringArgumentType.string()`, not the spec's `word()`.** Brigadier 1.3.10's `word()` parses with `StringReader.readUnquotedString()`, whose allowed-character set excludes `,`; a comma-separated list such as `10,25,50` cannot be parsed at all with `word()` (verified with `javap` against the exact brigadier jar used by all five trees). `string()` is still a standard, vanilla-decodable Brigadier argument type and keeps the same syntax when the step list is quoted (`"10,25,50"`). Tests use the quoted form. No custom ArgumentType or new dependency was introduced.
2. **Two extra lang keys** (`target_meta_query`, `bulk_cleanup_preview`) beyond the spec's 45. They provide the read-only current-state display for bare `targets meta <item> [<count>]` and the mandatory two-step preview for `bulk cleanup`, for which the spec defines no message key. Both are present in en_us and ja_jp in every tree.
3. **Undo snapshots for pure additions are not stored.** The spec asks every `bulk` operation to leave a snapshot, but the existing `UndoLogic.Snapshot` only models resets and removals (`RESET`, `BULK_REMOVAL`) and the spec forbids a new Snapshot kind. `bulk cleanup` stores a `BULK_REMOVAL` snapshot; `addsteps`, `bulk addsteps`, and `bulk expandtag` are additive only and have no reversible removal to capture. No undo hint is emitted for them.
4. **`sharetargets accept` preview reuses `copytargets_preview`** (with the share ID as the source label) because the spec defines no dedicated preview key; the completion message uses the specified `sharetargets_accepted`.
5. **`bulk cleanup` leaves `progressNotified`, `records`, `worldFirsts`, and `completedPresets` untouched**, per D-150-76, even though those entries relate to the removed targets; its snapshot therefore carries no progress map. Undo restores the target list but not `targetMeta` entries removed with the targets (the snapshot model has no metadata field; the spec does not require metadata restoration).
6. **NBT-tree metadata parsing detail:** in 1.21.1/1.20.1-forge a `targetMeta` value missing any of `priority`/`label`/`pinned` is treated as a malformed element (warn + skip one entry), while 26.x codec fields are optional with defaults. Whole-field absence still loads as an empty map in all trees, and a malformed element never fails the player record or drops siblings.

No other deviations. SavedData schemaVersion unchanged; export/import JSON and backup formats unchanged; no new permission nodes; vanilla-client join compatibility and GUI freeze preserved.

## Copy to tools workspace

Attempted: `cp progress-150-commands-3.md /home/hatch/workspace/iml-tools/` — **failed**: `Read-only file system` (copy exit 1; destination file absent). The report therefore exists only in the repository work directory.
