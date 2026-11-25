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
