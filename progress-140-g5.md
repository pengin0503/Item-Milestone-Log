# Progress — GitHub issue fixes G5: preset perf, tracker perf, export collisions (#102, #113, #137)

Status: DONE. One local commit on `fix/issues-g5-perf`; nothing pushed.
- Base: `221e369` (current `origin/main`, the PR #141 / G4 merge)
- Branch: `fix/issues-g5-perf` (created from `origin/main` after `git fetch origin`)
- Commit: one local commit on top of `221e369` (see `git log -1`); `./gradlew` was not run.
- 30 code/test/doc files (+1323 / −133) across `common`, all five version
  trees, README, and two feature specs, plus this progress file.

## #102 — `PresetIncludesLogic.resolve` exponential on diamond / duplicated includes

**Claim verified.** In base `221e369`, `common/.../PresetIncludesLogic.java`
`expand` (`:88-134` in the issue; `:125-159` in base) had only the `stack`
cycle check. It neither memoized already-expanded nodes nor skipped repeated
`includes` entries. A probe compiled against the base classes
(`.scratch/ProbeG5.java`, diamond DAG with 1 root + 2 presets per layer) gave
super-linear growth on the same machine:

| depth (presets) | base time |
|---:|---:|
| 18 (37) | 1,337 ms |
| 20 (41) | 2,380 ms |
| 25 (51, the issue's size) | ~76 s extrapolated; not allowed to finish |

The item result at depth 20 was already the expected 41-item list, but the DFS
re-visited shared branches exponentially.

**Fixed (one shared common file).**
- `resolve` now creates a **per-root clean memo** (`cleanMemo`,
  `PresetIncludesLogic.java:110-122`). An expansion that never cut a cycle is
  independent of the current `stack`, so reusing it inside the same root is
  exact. Cyclic expansions are never cached because their cut point depends on
  the stack (this preserves the old per-root cycle semantics that the old
  comment warned about).
- Each node's `includes` list is de-duplicated after trimming
  (`seenIncludes`, `:176-190`); repeated IDs add nothing to the ordered set and
  are expanded once.
- Diagnostics are de-duplicated **per root** by message text (`report`,
  `:208-212`). This keeps warning output polynomial on diamonds; strict
  validation still sees every distinct problem (see Deviations).
- No depth/visit cap was added. After memoization + dedup the tested graphs are
  polynomial, and a cap would make previously valid large presets change
  behavior.

**Equivalence evidence.** A differential harness
(`.scratch/DifferentialPresetProbe.java`) compared the new resolver with a
literal copy of the base algorithm on **20,000 random graphs** (2–8 nodes,
duplicates, self/ancestor cycles, unknown and blank includes). Results:
**all 20,000 trials matched exactly** on per-preset item lists, deduplicated
warning order, and deduplicated `problemsByPreset`. The existing cycle /
unknown / blank / order tests also pass unchanged.

**Regression tests added** (`common/src/test/.../PresetIncludesLogicTest.java`):
- `deepDiamondIncludesResolveFastWithTheExactOrderedItems`: 25 layers / 51
  presets, `assertTimeoutPreemptively(10 s)`, asserts the exact ordered list
  for every `a_i`, `b_i`, and the root (2·depth+1 items).
- `duplicatedIncludeListsResolveOncePerNodeWithoutChangingOrder`: 256-node
  chain, 128 repeated copies of every next ID, asserts the full descending
  order under a 10 s timeout.

**After the fix:** depth 25 resolves in ~58 ms and depth 40 (81 presets) in
~78 ms with identical output (probe run against the rebuilt classes).

## #113 — Tracker/TagIndex per-scan expansion and aggregation for tag targets

**Claim verified.** In base `221e369`, `Tracker.countPendingPossession`
(1.21.1 `:618-628`) called `tagIndex.expandForCounting(...)` and then
`withTagTotals(...)`. `TagIndex.expandForCounting` (common `:164-184`) walks
every member of every pending tag, and `withTagTotals` (`:191-216`) walks every
member again, on every 20-tick player scan. The reverse index
`tagsContaining(itemId)` already existed (`TagIndex.java:128-134`) but was not
used by the scan path. The same pattern existed in
`countRequestedPossession` (`:829-848`).

**Fixed (all five Tracker copies).** Both methods now walk the player's stacks
(≈46) and consult `tagIndex.tagsContaining(stack.itemId())`, accumulating only
the pending tag IDs directly. Tag totals use `long` and the same
`min(Integer.MAX_VALUE, total)` cap, and tag entries are written only when the
total is > 0, exactly like `withTagTotals`. The concrete item counts map is kept
identical too (a tag member that is held is still present in `counts`), and
`stillCarried` is updated for every carried stack before any filtering.
`countRequestedPossession` preserves its one different rule: a requested
non-tag item is counted even while carried, while a carried tag member is
excluded from the tag total. `TagIndex` itself needed no change — it already
provides the reverse lookup named in the issue.

**Regression test added** (`TrackerTest.largeTagTargetsAreCountedFromInventoryStacksWithUnchangedTotals`,
identical in all five trees): a 10,000-member tag plus overlapping and
non-requested tags, carried-member exclusion, concrete target counts,
`stillCarried`, and `countRequestedPossession` totals. It asserts the exact
expansion-path behavior.

## #137 — per-player export filenames collide after sanitization

**Claim verified.** In base `221e369`, a probe invoked the built
`MilestoneExport` (`jshell`-style, `.scratch/ProbeExport.java`) and reproduced
the issue's collisions:
`Bob!`, `Bob?`, `Bob_` → `Bob_.json`; `*Steve`, `_Steve` → `_Steve.json`;
`Steve.`, `Steve` → `Steve.json`; `a..b`, `a_b` → `a_b.json`;
`all-players`, `all-players-player` → `all-players-player.json`.
Writes use `REPLACE_EXISTING` (`MilestoneExport.java:238,240`), so the later
export silently replaced the earlier player's file.

**Fixed (shared `MilestoneExport`, copied byte-identically to all five trees).**
- New overloads append the owner UUID verbatim after the sanitized base:
  `sanitizePlayerFileBaseName(name, uuid)`, `sanitizePlayerFileName(name, uuid,
  ext)`, plus JSON/HTML/CSV variants (`:202-226`). Example:
  `Bob_-11111111-1111-1111-1111-111111111111.json`.
- The full UUID (not an 8-hex fragment) is used so distinct players can never
  share a name; the sanitized, human-recognizable prefix is retained and the
  suffix is stable across runs/restarts for the same player. Old
  sanitized-only overloads remain for compatibility, but production exports
  use the UUID overloads (`:316`, `:436`, `:583`).
- `exportSelf`'s import completion now derives the same UUID-aware name from
  `sp.getUUID()` in all five trees (`MilestoneCommands.java:325` in 1.21.1 /
  1.20.1-forge, `:326` in 26.x).
- Existing sanitized-only filenames remain importable by passing the filename
  to `/milestonelog import`; no migration is required.
- Docs updated: `README.md` export table/format section,
  `docs/FEATURE_SPEC_1.0.1.md:105`, `docs/FEATURE_SPEC_1.1.0.md:237`.

**Regression test added** (`MilestoneExportTest.playerExportNamesAreStableAndUniqueForCollidingSanitizedNames`,
identical in all five trees): proves the legacy bases collide, the UUID names
are unique/stable/safe, and actual JSON/HTML/CSV exports for `Bob!` and `Bob?`
produce distinct files with the correct JSON owner UUIDs (neither file is
overwritten).

## Verification

Script: `.scratch/verify-140-g5.sh`; summary
`.scratch/verify-140-g5-summary.log`. Each tree was compiled with its own
JDK/release/classpath (1.21.1 JDK 21, 1.20.1-forge JDK 17, 26.x JDK 25) and the
full JUnit Platform suite was launched with the JVM working directory set to
that version's `run` directory, as in G1–G4.

| Tree | JVM cwd | JDK | G4 baseline | G5 tests run | Successful | Failed | Exit |
|---|---|---|---:|---:|---:|---:|---:|
| 1.21.1 | `versions/1.21.1/run` | 21 | 654 | 658 | 658 | 0 | 0 |
| 1.20.1-forge | `versions/1.20.1-forge/run` | 17 | 652 | 656 | 656 | 0 | 0 |
| 26.1.2 | `versions/26.1.2/run` | 25 | 662 | 666 | 666 | 0 | 0 |
| 26.2 | `versions/26.2/run` | 25 | 662 | 666 | 666 | 0 | 0 |
| 26.3 | `versions/26.3/run` | 25 | 662 | 666 | 666 | 0 | 0 |

Deltas are +4 in every tree: two common `PresetIncludesLogicTest` cases, one
`TrackerTest` case, and one `MilestoneExportTest` case.

Shared-file check: `.github/scripts/check-identical-files.sh` → exit 0,
`All 91 file check(s) passed.` (the brief's `tools/check-identical-files.sh`
does not exist in this checkout; the script lives under `.github/scripts/`).
`git diff --check` is clean.

## Deviations / notes

- **#102 warning duplicates**: item lists, cycle cuts, cycle/unknown/blank
  messages, and per-root problem presence are unchanged. Repeated identical
  diagnostics within one root are now emitted once instead of once per DFS
  path (otherwise a diamond with an unknown include would itself produce an
  exponential warning list). The 20,000-graph differential test confirms the
  deduplicated warning order and per-root problems match the base algorithm.
- **#102 no cap**: the issue's optional max-depth/total-visit cap was not added,
  to avoid changing the result of any currently valid preset.
- **#113 TagIndex unchanged**: the reverse index required by the issue already
  existed; only the Tracker scan paths were changed to use it.
- **#137 full UUID suffix**: the issue suggested a short UUID fragment; the
  full UUID was chosen to make the filename collision-proof rather than
  probabilistically unique, while keeping the readable sanitized prefix.
- `./gradlew` was not run (per the brief). All compilation/tests used the
  existing per-version JDK/classpath harness.
