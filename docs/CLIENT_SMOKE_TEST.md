# Layer-2 headless client smoke test

Boots the **real Minecraft client** of each supported version without a GPU or a
monitor, enters a singleplayer world, opens the mod's screens, and screenshots each
one. It complements Layer 1 (the static binary-compatibility / link audit): Layer 1
proves the classes link, Layer 2 proves the client actually starts, the screens open and
render frames, and nothing crashes along the way.

It runs on demand on a Linux box with a virtual display (in practice the GCE VM
`iml-builder`). It is not part of GitHub Actions and does not gate a release by itself.

## How to run

Prerequisites (Debian/Ubuntu): `apt-get install xvfb mesa-utils libgl1-mesa-dri
mesa-vulkan-drivers` (Mesa ≥ 25: llvmpipe for OpenGL, lavapipe for Vulkan), JDK 21 for
1.21.x and JDK 25 for 26.x (defaults `~/tools/jdk21`, `~/tools/jdk25`; override with
`JDK21=` / `JDK25=`).

```sh
# from the repo root; default = representative set
tools/run-client-smoke.sh
# 1.21 1.21.4 1.21.5 1.21.7 1.21.8 1.21.9 1.21.11 26.1.2 26.3

tools/run-client-smoke.sh 1.21.8 26.3        # chosen versions
SMOKE_FRESH_WORLD=1 tools/run-client-smoke.sh   # re-create the test world
```

The runner:

1. regenerates a throwaway harness per version under `versions/verify/<mc>`
   (`IML_SMOKETEST=1 tools/prepare-121x-harness.sh …` for 1.21.x,
   `IML_SMOKETEST=1 tools/prepare-26x-harness.sh <mc>` for 26.x), keeping the
   harness's `build/` and `run/` so Gradle / Minecraft caches stay warm;
2. pre-seeds `run/options.txt` (`onboardAccessibility:false`, `pauseOnLostFocus:false`,
   render distance 4, sound off) and, on 26.x, `run/config/fml.toml`
   `earlyWindowControl = false` (the FML early window cannot open under Xvfb);
3. starts one Xvfb display (1280x720) per parallel slot and runs the dev clients
   (`gradlew runClient --no-daemon -Piml.smoketest=1`) **two at a time**
   (`SMOKE_PARALLEL`), each under a hard timeout (`SMOKE_TIMEOUT`, default 600 s);
4. collects `result.json`, the three screenshots, `latest.log`, the Gradle log and any
   new crash report into `build/smoke/<mc>/`;
5. prints a PASS/FAIL table (also `build/smoke/summary.md`) and exits non-zero if any
   version failed.

A version passes when the hook reports PASS for every screen, the client exits with
status 0, and all three screenshots exist.

## The smoke hook

`tools/smoketest/java/…/smoketest/SmokeTestClient.java` is a second client-only `@Mod`
entry point. **It is never part of a production jar**: it lives outside the release
sources and `tools/smoketest/inject.sh` copies it only into `versions/verify/` harness
projects when `IML_SMOKETEST=1` (inject.sh refuses any other directory). Profile jars
and release jars built without that variable contain no `smoketest` classes.

Without `-Diml.smoketest=1` the hook does nothing (it registers no listener). With it,
on the client thread:

1. waits for the title screen (dismissing NeoForge's "Warning while loading mods"
   screen the same way its continue button does, and recording it in the result);
2. opens `saves/iml-smoke` or, if missing, creates it through `CreateWorldScreen`;
3. once in the world, opens `MilestoneScreen` via `ClientMilestoneHandler.requestOpenGui()`
   (the key-binding path, so the request/sync round trip through the integrated server is
   exercised), then `ItemPickerScreen` and `PresetListScreen` the way the
   MilestoneScreen buttons do, closing each through its own `onClose()`;
4. holds each screen 40 ticks, screenshots it with the game's `Screenshot.grab`
   (`run/screenshots/iml-smoke-<n>-<Screen>.png`);
5. writes `run/iml-smoke/result.json` (per-screen status, open time, JVM-uptime
   timestamps for title / world) and stops the client: exit 0 on success, 1 on failure,
   2 if its watchdog fires.

Version differences between 1.21 and 26.3 (current-screen access, `openFresh`,
`Screenshot.grab` signature, main render target) are bridged with reflection, so one
source compiles against every version.

The harness copy also drops the mod's GameTest registration listener. NeoForge only
registers GameTests in dev runs (`GameTestHooks.isGametestEnabled()` is false in
production), and on 1.21.5+ the inline `GameTestInstance` codec is not in the
`test_instance_type` registry, so with the listener present the registry sync to a joining
client fails and a dev client cannot enter any singleplayer world. Production never
registers the tests, so the harness matches production here.

## What it proves

- The client of each version starts with the mod loaded (no mixin/linkage/registration
  crash, no fatal mod-loading error) and reaches the title screen.
- A singleplayer world loads with the mod on both sides of the integrated server.
- The GUI-open request reaches the server and the sync reply opens `MilestoneScreen`.
- `MilestoneScreen`, `ItemPickerScreen` and `PresetListScreen` construct, lay out and
  render frames for at least 40 ticks without throwing, and close back to their parent.
- The screenshots show something was drawn. Look at them for "blank screen / missing
  rows" breakage.

## What it does not prove

- **Visual semantics.** Software rasterizers render the frames, but nobody checks colours,
  transparency, text clipping or layout pixel by pixel (for example the 1.21.6 alpha
  issue would not fail this test). The final visual check stays on a real device.
- **Real-device performance or driver behaviour.** llvmpipe/lavapipe are CPU renderers.
  Frame rate, GPU driver quirks and real Vulkan hardware paths are not exercised.
- **Interaction details.** The hook opens screens programmatically. It does not click
  through every button, type in search fields, or test key bindings, scrolling or
  dedicated-server play.
- **Production launcher setup.** The dev client (moddev `runClient`, dev user, Mojang
  mappings) is used, not the production launcher with an MS account.

## Manual server smoke checklist — 1.5.0 step 5

The checks below need a live world (and a permission mod for the first one); the headless
client hook above does not exercise server-side commands, file output or HTTP behaviour.
Run them once per supported Minecraft version before a release and tick each box. Expected
results are the user-facing behaviour documented in [README.md](../README.md) and
[CHANGELOG.md](../CHANGELOG.md).

- [ ] **Permission-mod grant.** Set NeoForge's `permissionHandler` to a permission mod
      (for example LuckPerms) and grant a non-OP player only some of the step-5 nodes,
      such as `itemmilestonelog.doctor` and `itemmilestonelog.backup.view`. Confirm the
      granted commands run, a step-5 command whose node was not granted (for example
      `backup now`) is denied, and an OP / permission-level-2 player still runs everything
      — nodes add access and never take it away. If tab completion is stale after a grant,
      rejoin; execution is checked immediately.
- [ ] **Manual backup on a live world.** Run `/milestonelog backup now`, then
      `backup list` and `backup verify` (both with and without a file name). Confirm a new
      `milestones-YYYYMMDD-HHmmss.dat` appears under
      `<world>/itemmilestonelog/backups/`, that `now` also works with
      `autoBackupEnabled = false`, that `now` is refused while the server is stopping, and
      that `verify` reports `OK` with counts without changing live data.
- [ ] **Audit lines after an admin op.** After a successful admin operation (for example
      `admin <player> grant`), inspect `<world>/itemmilestonelog/audit.log`: expect one
      JSONL line with a fixed `op`, a UTC `ts`, the actor UUID (or `#nonplayer` when run from
      console) and `result: success`. Confirm no line is written for a preview/dry-run or a
      failed operation, and that the file rotates into `audit.1.log` … `audit.5.log` once it
      passes 1 MiB. A deliberate write failure should leave the operation successful with
      only a WARN in the server log.
- [ ] **`discordtest payload` sends nothing.** Run `discordtest payload` and
      `discordtest worldfirst payload`. Confirm a single line of JSON is printed, no test
      message arrives in Discord (no HTTP POST), no normal `discordtest` result/state line
      appears, and no webhook URL is shown.

## Manual client checklist — 1.5.0 step 6 (client experience)

The headless hook above proves only that the client starts and that `MilestoneScreen`,
`ItemPickerScreen` and `PresetListScreen` render for 40 ticks. It does **not** exercise any
step-6 client feature: it runs no real mod pack, has no GPU, no clipboard, no OBS and no
human at the keyboard. Every row below is therefore **UNVERIFIED** at PR-8 and needs pen's
real client. Do not convert a row to "verified" from a source-level check, a unit test or a
headless run; record the Minecraft version, mod pack, OS/display setup and the observed
result when a row is actually performed.

- [ ] **UNVERIFIED — JEI / EMI / REI tooltip coexistence.** In a real pack with each of
      JEI, EMI and REI installed (separately and together where possible), open each mod's
      item list, hover a target item and use the hovered-item shortcut. Confirm the mod's own
      tooltip still draws, at most one milestone status line is appended, `hoverModIntegration`
      makes no difference when the event is not emitted, and no mod's GUI is broken. Also
      **UNVERIFIED**: Jade / WTHIT / TOP actually emitting `ItemTooltipEvent` to their info
      panels with `externalTooltipIntegration` on (off by default); if they do not, the
      feature is a silent no-op, not a failure.
- [ ] **UNVERIFIED — clipboard and client commands, per version.** Verify the fourth key
      (`copy_recent`) writes the newest local record to the OS clipboard and shows the action-bar
      feedback, and that `/imlclient pin`, `carry`, `export`, `history`, `backup` and `diag`
      behave as documented. Repeat on Forge 1.20.1, on 1.21.1, on the 1.21.5–1.21.11
      compat-variant trees (whose key event differs) and on 26.1.2 / 26.2 / 26.3.
      **UNVERIFIED** on every one of those versions: native clipboard linkage, key-binding
      conflicts (M conflicts with FTB Chunks) and the Brigadier/Copper client-command
      registration path.
- [ ] **UNVERIFIED — OBS status-file consumption.** With `streamStatusEnabled` explicitly
      enabled, confirm `config/itemmilestonelog-client/stream/status.txt` and `status.json`
      appear, update on the documented debounce, contain no player name, UUID, address or URL,
      and can actually be read by OBS as a text source. Atomic replacement and the off-by-default
      state were checked in source only; the OBS side is **UNVERIFIED**.
- [ ] **UNVERIFIED — screenshot capture, per version.** With `screenshotOnRecord` explicitly
      enabled, confirm one PNG lands in `config/itemmilestonelog-client/screenshots/` on a new
      record and on preset completion, that open screens are skipped, and that the 20-file
      prune keeps only the newest. Exercise every supported version, especially **1.21.5,
      1.21.6, 1.21.7, 1.21.8, 1.21.9 and 1.21.11**, where the `Screenshot.grab` / main render
      target / current-screen APIs differ and are bridged by reflection. All of those runtime
      API paths are **UNVERIFIED**.
- [ ] **UNVERIFIED — high-DPI / display-scale / display-binding / Alt behaviour.** With
      `pinHudScale`, `hudHighContrast`, `reducedMotion` and `pinHudAutoHide` enabled, check the
      HUD at high DPI and at several GUI scale factors, after a display-scale change and after a
      Windows display-binding (DST) change, and when Alt is consumed by the OS menu key. Font
      matrix scaling and the actual read-after-damage/chat/screen fade are **UNVERIFIED**;
      the light-power mode's perceived cadence is **UNVERIFIED** too.
- [ ] **UNVERIFIED — narration.** With `accessibleNarration` enabled and a screen reader
      running, confirm the pinned-target HUD rows are actually spoken (content, order and the
      20-line cap). Screen-reader output is **UNVERIFIED**; only the registration of narration
      targets was checked in source.
- [ ] **UNVERIFIED — HUD / effects visuals.** With `pinHudEnabled` and `clientEffectEnabled`
      explicitly enabled, confirm the four HUD anchors, row cap, colours and the particle
      effect look correct on a real GPU (the headless software rasterizer cannot judge colour,
      transparency, clipping or timing). Both features are off by default; their in-game
      appearance is **UNVERIFIED** on all five trees.
- [ ] **UNVERIFIED — vanilla-client join.** Join a modded server with a plain vanilla client
      of each supported version, confirm no registry/packet/channel error, that the mod's GUI
      request degrades gracefully, and that screenshots/OBS/clipboard features stay dormant.
      This needs a real second client and is **UNVERIFIED** locally.

The 40-tick headless screen smoke and the vanilla-join coverage are CI jobs on this PR; they
are **not** checks performed by this document's on-demand runner during PR-8 verification.
