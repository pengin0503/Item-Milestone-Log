# Item Milestone Log

**Records the cumulative playtime at which you first obtain each of your goal items.**

Ever wondered *"how many hours into this world did I finally make that item?"* — for example the components of the **ATM Star** in All the Mods 10? Item Milestone Log answers exactly that.

You choose your own target items, per player. The first time a target item enters your inventory, the mod records your **cumulative playtime** (the vanilla *Time Played* statistic — not world time, so sleeping doesn't inflate it) and notifies you in chat:

> ★ Milestone recorded: ATM Star at playtime 87h 42m 10s

Detection is possession-based, not crafting-based, so items produced by machines, automation, AE2 autocrafting, smelting or mob drops are recorded too — not just hand-crafted ones.

## Features

- Per-player target items: every player tracks their own goals
- Records cumulative playtime (`minecraft:play_time`) at first obtainment, stored per world
- **In-game GUI** (assign a key under Controls; there is no default key): view playtime and progress, browse targets and recorded milestones, search and add items, add your held item, apply presets, remove personal targets, and reset records with confirmation
- Chat notification the moment a milestone is recorded (configurable, optional server-wide broadcast)
- Nothing is recorded in creative or spectator mode (configurable)
- Built-in presets: `atm_star` (All the Mods 10 endgame) and `vanilla_endgame`
- Server-wide default targets via config file
- **Optional client installation** — on a dedicated server, vanilla clients can still join without installing the mod and use all `/milestonelog` commands; installing the mod on the client unlocks the GUI
- Languages: English, Japanese (日本語)

## GUI & Commands

### GUI

Assign a key to **Open Milestone Log** under *Options → Controls → Key Binds → Item Milestone Log*, then press it to open the Item Milestone Log screen. There is no default key: the obvious one, `M`, already opens the full-screen map of FTB Chunks, which All the Mods 10 includes. Everything available via commands can also be done from the GUI:

- View your current cumulative playtime and overall progress in the header
- Switch between **Targets** (distinguishing personal targets and server-configured default targets) and **Recorded** milestones (earliest first)
- Search all registered items and add targets, or add the item currently in your main hand
- Remove personal targets (server default targets are marked and cannot be removed)
- Apply built-in presets (`atm_star`, `vanilla_endgame`) with one click
- Reset individual records or all records (with confirmation dialogs; targets are kept)

> **Note:** Opening the GUI requires the mod to be installed on the **client** as well as the server. On a dedicated server, vanilla clients without the mod can still join normally and use all commands below.

### Commands

All commands work for your own data, no OP required. `/iml` is a short alias for `/milestonelog`.

| Command | Description |
|---|---|
| `/milestonelog` | Summary: your playtime and recorded / total targets |
| `/milestonelog playtime` | Show your current cumulative playtime |
| `/milestonelog add <item>` | Add a target item |
| `/milestonelog addhand` | Add the item in your main hand as a target |
| `/milestonelog remove <item>` | Remove a personal target |
| `/milestonelog targets` | List targets with recorded playtime or "not yet obtained" |
| `/milestonelog list` | List recorded milestones, earliest first |
| `/milestonelog preset <name>` | Apply a preset (`atm_star`, `vanilla_endgame`) |
| `/milestonelog reset <item>` | Clear one record (it will be recorded again on next obtainment) |
| `/milestonelog resetall` | Clear all your records (targets are kept) |

## Configuration

`config/itemmilestonelog-common.toml` (Minecraft 1.21.1, 26.1.2, 26.2) or
`config/itemmilestonelog-local.toml` (Minecraft 26.3, where NeoForge renamed the
config type). On a dedicated server, edit the file on the server.

- `defaultTargets` — item IDs every player tracks by default
- `scanIntervalTicks` — inventory scan interval in ticks (default 20 ticks = 1 s, 1–1200)
- `notifyPlayer` — chat notification on record (default true)
- `broadcastMilestones` — announce records to all players (default false)
- `ignoreCreativeAndSpectator` — record nothing in creative or spectator mode (default true)

Items picked up from the ground, crafted by hand or taken out of a furnace are recorded
right away; items that arrive in other ways (machines, AE2, chests, `/give`) are found
by the inventory scan, so their timestamp can be up to `scanIntervalTicks` late.

With `ignoreCreativeAndSpectator`, items taken in creative mode do not count either:
items you still carry when you switch back to survival or adventure are only recorded
once they have left your inventory and you obtain them again. This works per item
type, so putting such an item in a chest and taking it out later does count; use
`/milestonelog reset <item>` if that was not meant as a milestone.

## Saved data

Targets and records are stored per world, for all players in one file:

| Minecraft | File |
|---|---|
| 1.21.1 | `<world>/data/itemmilestonelog_data.dat` |
| 26.1.2 / 26.2 / 26.3 | `<world>/data/itemmilestonelog/milestones.dat` |

When a 1.21.1 world is opened with a 26.x version of the mod, the records are read
from the 1.21.1 file and saved in the new one; the 1.21.1 file stays in place and is
not modified.

## Supported Versions & Requirements

| Minecraft | NeoForge (minimum) | Java | JAR Artifact |
|---|---|---|---|
| **1.21.1** | `21.1.1`+ | Java 21 | `itemmilestonelog-neoforge-1.21.1-1.0.0.jar` |
| **26.1.2** | `26.1.2.71`+ | Java 25 | `itemmilestonelog-neoforge-26.1.2-1.0.0.jar` |
| **26.2** | `26.2.0.57`+ | Java 25 | `itemmilestonelog-neoforge-26.2-1.0.0.jar` |
| **26.3** | `26.3.0.76`+ | Java 25 | `itemmilestonelog-neoforge-26.3-1.0.0.jar` |

Each jar is compiled against the minimum NeoForge version above and is tested on both
that version and the newest NeoForge build at release time (1.21.1: `21.1.267`,
26.1.2: `26.1.2.129`, 26.2: `26.2.0.95`, 26.3: `26.3.0.115`). Use the jar that
matches your Minecraft version; each one only loads on its own Minecraft version.

Built for long modpack playthroughs such as All the Mods 10 (1.21.1), as well as modern 26.x NeoForge worlds and servers.

### Building from Source

Each Minecraft version lives under `versions/<mc_version>` and shares translation assets from `common/resources`:

```bash
./gradlew -p versions/1.21.1 build   # Java 21
./gradlew -p versions/26.1.2 build   # Java 25
./gradlew -p versions/26.2 build     # Java 25
./gradlew -p versions/26.3 build     # Java 25
```

The jar is written to `versions/<mc_version>/build/libs/`. `build` also runs the unit
tests. To check a newer NeoForge build, pass `-Pneo_version=<version>`.

### Tests and CI

GitHub Actions (`.github/workflows/main.yml`) runs, for every Minecraft version and
for both the minimum and the latest NeoForge in `gradle.properties`:

1. `build` with the unit tests,
2. a check of the jar contents (`.github/scripts/verify-jar.sh`),
3. the release jar on a real NeoForge dedicated server
   (`.github/scripts/run-server-tests.sh`): command registration over RCON and, on
   1.21.1 and 26.1.2, a vanilla-protocol client ([mineflayer](https://github.com/PrismarineJS/mineflayer))
   that joins without the mod and runs the commands, gets items recorded by pickup
   and by the inventory scan, and checks that the records survive a server restart.
   It also checks that nothing is recorded in creative mode.
   mineflayer does not support 26.2 and 26.3 yet, so those get the RCON checks only.

A further job opens a world made on a 1.21.1 server, with records, on a 26.1.2 server
and checks that the records come over (`.github/scripts/run-migration-test.sh`).

The jars from the minimum-NeoForge jobs are uploaded as the workflow artifacts
`itemmilestonelog-<mc_version>`. The GUI is not covered by the automated tests.

### Releasing

1. Set `mod_version` in all four `versions/*/gradle.properties` and add a
   `## <version>` section to [CHANGELOG.md](CHANGELOG.md).
2. Once CI is green on `main`, push the tag `v<version>` (for example `v1.0.0`).
   The workflow runs all tests again and publishes a GitHub Release with the four jars
   and that changelog section. It stops if the tag, the versions and the jars do not
   match (`.github/scripts/check-release.sh`, which also runs on every pull request).
3. Upload the jars to CurseForge as described in [CURSEFORGE.md](CURSEFORGE.md).

## License

Custom License (All Rights Reserved) — see the [LICENSE](LICENSE) file.
Personal use and inclusion in free modpacks (with credit and a link back) are
allowed; redistribution, re-uploading, selling and distributing modified
versions require the author's permission.
