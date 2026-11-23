# Item Milestone Log

**Records the cumulative playtime (plus in-game Day and real-world date) at which you first obtain each of your goal items.**

Ever wondered *"how many hours into this world did I finally make that item?"* — for example the components of the **ATM Star** in All the Mods 10? Item Milestone Log answers exactly that.

You choose your own target items, per player. The first time a target item enters your inventory, the mod records your **cumulative playtime** (the vanilla *Time Played* statistic — not world time, so sleeping doesn't inflate it), the **in-game Day**, and the **real-world date**, and notifies you in chat:

> ★ Milestone recorded: ATM Star at playtime 87h 42m 10s

Detection is possession-based, not crafting-based, so items produced by machines, automation, AE2 autocrafting, smelting or mob drops are recorded too — not just hand-crafted ones.

> **Detection scope:** Recording uses **simultaneous possession**: it triggers the first time the total count of a target item in your **player inventory (main inventory, offhand, and cursor stack)** reaches the target quantity (1–1,000,000; default 1). Items placed directly into an **Ender Chest**, inside a **Shulker Box**, or into **another mod's storage system** are not counted or recorded until you take them out into your inventory.

## Features

- Per-player target items with optional **quantities (1–1,000,000)**: track e.g. `minecraft:diamond` ×1 and ×100 as separate milestones (simultaneous inventory possession)
- Records cumulative playtime (`minecraft:play_time`), in-game overworld Day, and real-world date (`yyyy-MM-dd`) at first obtainment, stored per world
- **World-first records**: tracks who first obtained each item (and quantity) across the whole world (`/milestonelog firsts`, with optional server-wide announcement)
- **In-game GUI** (assign a key under Controls; there is no default key): view playtime and progress, browse targets and recorded milestones, search and add items (with quantity input), add your held item, browse/apply/save/delete presets, and reset records with confirmation
- Chat notification and configurable sound effect (`milestoneSound`) the moment a milestone is recorded
- Optional server-wide broadcast with per-player opt-out (`/milestonelog mutebroadcast`)
- Optional asynchronous **Discord Webhook** notifications (`all` or `world_first` scope)
- **Export / Import** targets and records as JSON per world, plus OP `exportall`
- **Custom presets**: define presets as JSON files under `config/itemmilestonelog/presets/` or save your current targets in-game
- **OP admin commands** to inspect, reset, or purge offline and online players' data
- Nothing is recorded in creative or spectator mode (configurable)
- Built-in presets: `atm_star` (All the Mods 10 endgame) and `vanilla_endgame`
- Server-wide default targets via config file
- **Optional client installation** — on a dedicated server, vanilla clients can still join without installing the mod and use all `/milestonelog` commands; chat messages automatically fall back to the player's client language (`en_us` / `ja_jp`, or English for other languages)
- **Server-side API** (`MilestoneRecordedEvent` & `ItemMilestoneLogApi`) for other mods and KubeJS
- **NeoForge Update Checker** integration (`updateJSONURL`)
- Languages: English, Japanese (日本語)

## GUI & Commands

### GUI

Assign a key to **Open Milestone Log** under *Options → Controls → Key Binds → Item Milestone Log*, then press it to open the Item Milestone Log screen. There is no default key: the obvious one, `M`, already opens the full-screen map of FTB Chunks, which All the Mods 10 includes. Everything available for personal milestones can also be done from the GUI:

- View your current cumulative playtime and overall progress in the header
- Switch between **Targets** (distinguishing personal targets and server-configured default targets) and **Recorded** milestones (earliest first, showing `(Day N, yyyy-MM-dd)`)
- Search all registered items and add targets (with an optional quantity box, 1–1,000,000), or add the item currently in your main hand
- Remove personal targets (server default targets are marked and cannot be removed)
- Open **Presets...** to browse built-in and custom presets, apply them with one click, and (when permitted) save your current personal targets as a custom preset or delete custom presets
- Reset individual records or all records (with confirmation dialogs; targets are kept)

> **Note:** Opening the GUI requires the mod to be installed on the **client** as well as the server. On a dedicated server, vanilla clients without the mod can still join normally and use all commands below.

### Commands

`/iml` is a short alias for `/milestonelog`.

#### Player commands (no OP required)

| Command | Description |
|---|---|
| `/milestonelog` | Summary: your playtime and recorded / total targets |
| `/milestonelog playtime` | Show your current cumulative playtime |
| `/milestonelog add <item> [<count>]` | Add a target item (optional quantity 1–1,000,000, default 1) |
| `/milestonelog addhand [<count>]` | Add the item in your main hand as a target (optional quantity, default 1) |
| `/milestonelog remove <item> [<count>]` | Remove a personal target (default quantity 1) |
| `/milestonelog targets` | List targets with recorded playtime or "not yet obtained" |
| `/milestonelog list` | List recorded milestones, earliest first, with Day and date |
| `/milestonelog preset list` | List all available presets (built-in and custom) |
| `/milestonelog preset <name>` | Apply a preset (`atm_star`, `vanilla_endgame`, or custom preset ID) |
| `/milestonelog reset <item> [<count>]` | Clear one record (it will be recorded again on next obtainment) |
| `/milestonelog resetall` | Clear all your records (targets are kept; world-firsts are kept) |
| `/milestonelog firsts` | List server-wide world-first milestones |
| `/milestonelog export` | Export your targets and records to `itemmilestonelog/exports/<player>.json` |
| `/milestonelog import <fileName>` | Merge targets (union) and records (smaller playtime wins) from an export file |
| `/milestonelog mutebroadcast` | Toggle muting other players' milestone / world-first broadcasts |

#### Preset management & OP commands

Preset management (`preset save`, `preset delete`, `preset reload`) requires **permission level 2 (OP)** on a dedicated server, and is always allowed in singleplayer. All `firsts reset*`, `exportall`, and `admin` commands require **permission level 2 (OP)**.

| Command | Description |
|---|---|
| `/milestonelog preset save <name>` | Save your current personal targets as `config/itemmilestonelog/presets/<name>.json` |
| `/milestonelog preset delete <name>` | Delete a custom file preset (built-in presets cannot be deleted) |
| `/milestonelog preset reload` | Reload `config/itemmilestonelog/presets/` and refresh open GUIs |
| `/milestonelog firsts reset <item> [<count>]` | Clear the world-first record for `<item>` (optional quantity, default 1) |
| `/milestonelog firsts resetall` | Clear all world-first records |
| `/milestonelog exportall` | Export all players' data to `itemmilestonelog/exports/all-players.json` |
| `/milestonelog admin players` | List all players with saved data (name, UUID, target/record counts) |
| `/milestonelog admin <player> list` | View a player's recorded milestones (works offline) |
| `/milestonelog admin <player> targets` | View a player's targets (works offline) |
| `/milestonelog admin <player> reset <item> [<count>]` | Clear a specific record for a player (works offline) |
| `/milestonelog admin <player> resetall` | Clear all records for a player (works offline) |
| `/milestonelog admin <player> purge` | Delete all milestone data for a player (logged to server log) |

## Custom Presets

Custom presets are stored as UTF-8 JSON files under `config/itemmilestonelog/presets/<id>.json` (on the server for dedicated servers). The folder is created automatically on server startup; no sample file is generated.

Example (`config/itemmilestonelog/presets/my_pack.json`):

```json
{
  "name": "My Pack Goals",
  "items": [
    "minecraft:diamond",
    { "item": "minecraft:diamond", "count": 100 },
    "allthetweaks:atm_star"
  ]
}
```

- `name` (optional): display name shown in the GUI and `/milestonelog preset list` (defaults to the file ID).
- `items` (required): array of item IDs (`"namespace:path"` for quantity 1) or objects (`{"item": "namespace:path", "count": 100}`, count 1–1,000,000). IDs from mods that are not currently installed are preserved in the preset and simply skipped when applied, so modpack presets work across packs.
- Preset IDs (`<id>`) must be 1–32 characters using `a-z`, `0-9`, `_`, or `-`. Files matching built-in preset IDs (`atm_star`, `vanilla_endgame`) are ignored.
- Because `list`, `save`, `delete`, and `reload` are subcommands under `/milestonelog preset`, a custom preset literally named `list`, `save`, `delete`, or `reload` cannot be applied via `/milestonelog preset <name>` (use the GUI or a different ID).
- **Trade-off**: Presets live in the `config/` folder rather than world SavedData, so they are shared across all worlds on that client/server and do not travel automatically if you copy only a world folder to another server.

## Export / Import Format

Exports are written to `<world>/itemmilestonelog/exports/<player>.json` (or `all-players.json` for `exportall`):

```json
{
  "format": 1,
  "player": { "uuid": "…", "name": "…" },
  "targets": [
    "minecraft:diamond",
    { "item": "minecraft:diamond", "count": 100 }
  ],
  "records": {
    "minecraft:diamond": { "ticks": 123456, "day": 11, "epoch": 1795600000000 },
    "minecraft:diamond#100": { "ticks": 456789, "day": 25, "epoch": 1795700000000, "count": 100 }
  }
}
```

When importing with `/milestonelog import <fileName>`, targets are merged by union, and for each `(item, count)` entry in `records`, the entry with the smaller `ticks` wins (keeping that side's `day` and `epoch`). Export files without `count` fields are imported as quantity 1. Imports do not trigger `MilestoneRecordedEvent`, world-first registration, or Discord notifications.

## Configuration

`config/itemmilestonelog-common.toml` (Minecraft 1.21.1, 26.1.2, 26.2) or
`config/itemmilestonelog-local.toml` (Minecraft 26.3, where NeoForge renamed the
config type). On a dedicated server, edit the file on the server.

- `defaultTargets` — item IDs every player tracks by default (append `#<count>`, e.g. `"minecraft:diamond#100"`, for quantities > 1)
- `scanIntervalTicks` — inventory scan interval in ticks (default `20` ticks = 1 s, `1–1200`)
- `notifyPlayer` — chat notification on record (default `true`)
- `broadcastMilestones` — announce records to all players (default `false`)
- `ignoreCreativeAndSpectator` — record nothing in creative or spectator mode (default `true`)
- `milestoneSoundEnabled` — play a sound to the player on record, independent of `notifyPlayer` (default `true`)
- `milestoneSound` — vanilla sound event ID to play on record (default `"minecraft:ui.toast.challenge_complete"`)
- `announceWorldFirst` — announce server-wide world-first records to all online players (default `false`)
- `discordWebhookUrl` — optional Discord Webhook URL for asynchronous `{ "content": "..." }` notifications (default `""`; treated as a secret and never logged)
- `discordNotifyScope` — Discord notification scope: `"all"` or `"world_first"` (default `"all"`)
- `showFirstJoinHint` — show a one-time usage tip when a player first joins a world (default `true`)

Items picked up from the ground, crafted by hand or taken out of a furnace are recorded
right away; items that arrive in other ways (machines, AE2, chests, `/give`) are found
by the inventory scan, so their timestamp can be up to `scanIntervalTicks` late.

With `ignoreCreativeAndSpectator`, items taken in creative mode do not count either:
items you still carry when you switch back to survival or adventure are only recorded
once they have left your inventory and you obtain them again. This works per item
type, so putting such an item in a chest and taking it out later does count; use
`/milestonelog reset <item>` if that was not meant as a milestone.

## Mod & KubeJS API

The `com.pengin0503.itemmilestonelog.api` package provides a server-side event, read-only query helpers, and write helpers:

- `MilestoneRecordedEvent` fires when a record is confirmed during normal gameplay, via `ItemMilestoneLogApi.recordNow`, or via an admin `grant`. `event.getCause()` returns a `RecordCause` (`GAMEPLAY` / `API` / `ADMIN_GRANT`), so reward scripts can tell them apart.
- `ItemMilestoneLogApi.addTarget(server, playerUuid, targetKey)` / `removeTarget(server, playerUuid, targetKey)` add or remove a personal target, with the same validation, 1024-target limit, and cleanup as `/milestonelog add` / `remove` (`defaultTargets` keys cannot be removed). They return `true` when something changed.
- `ItemMilestoneLogApi.recordNow(serverPlayer, targetKey)` records a target without checking possession, through the normal record path (notifications, world-first, events, Discord, presets). It returns `false` if the key is not one of the player's effective targets, is already recorded, recording is paused (creative/spectator), or the player is a `FakePlayer`. Calling it from a `MilestoneRecordedEvent` listener is allowed; chains nested deeper than 16 return `false` with a warning.
- The write helpers must be called on the server thread (otherwise they throw `IllegalStateException`) and also re-sync the GUI of online players. In 1.1.0 they return `false` for tag keys.

### Java (NeoForge Mod)

```java
import com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import net.neoforged.bus.api.SubscribeEvent;

public class MyModEvents {
    @SubscribeEvent
    public static void onMilestoneRecorded(MilestoneRecordedEvent event) {
        String itemId = event.getItemId();
        int count = event.getCount();
        int ticks = event.getPlayTicks();
        long day = event.getDay();
        // Query read-only API (on MC 26.x, use event.getPlayer().level().getServer()):
        var worldFirst = ItemMilestoneLogApi.getWorldFirst(
                event.getPlayer().getServer(), itemId, count);
    }
}
```

> Note: `ItemMilestoneLogApi.getTargets(server, playerUuid)` returns saved personal targets with quantity 1, and `getTargetKeys(server, playerUuid)` returns all saved personal `(itemId, count)` targets; resolving server-configured `defaultTargets` is the caller's responsibility.

### KubeJS (Server Script)

```js
// kubejs/server_scripts/milestones.js
NeoForgeEvents.onEvent('com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent', event => {
  const player = event.getPlayer()
  const itemId = event.getItemId()
  const count = event.getCount()
  const ticks = event.getPlayTicks()
  player.tell(`Recorded ${itemId} x${count} at ${ticks} ticks!`)
})
```

Recording a milestone when a quest is completed (e.g. with FTB Quests' KubeJS integration):

```js
// kubejs/server_scripts/quest_milestones.js
const ItemMilestoneLogApi = Java.loadClass('com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi')
const TargetKey = Java.loadClass('com.pengin0503.itemmilestonelog.api.TargetKey')

FTBQuestsEvents.completed('0123456789ABCDEF', event => {
  // Returns false unless minecraft:nether_star is one of the player's targets and not yet recorded
  ItemMilestoneLogApi.recordNow(event.player, TargetKey.of('minecraft:nether_star'))
})
```

## Saved data

Targets, records, and world-firsts are stored per world, for all players in one file:

| Minecraft | File |
|---|---|
| 1.21.1 | `<world>/data/itemmilestonelog_data.dat` |
| 26.1.2 / 26.2 / 26.3 | `<world>/data/itemmilestonelog/milestones.dat` |

When a 1.21.1 world is opened with a 26.x version of the mod, the records (including 1.0.0 saves and world-first records) are read from the 1.21.1 file and saved in the new one; the 1.21.1 file stays in place and is not modified.

## Supported Versions & Requirements

| Minecraft | NeoForge (minimum) | Java | JAR Artifact |
|---|---|---|---|
| **1.21.1** | `21.1.1`+ | Java 21 | `itemmilestonelog-neoforge-1.21.1-1.0.1.jar` |
| **26.1.2** | `26.1.2.71`+ | Java 25 | `itemmilestonelog-neoforge-26.1.2-1.0.1.jar` |
| **26.2** | `26.2.0.57`+ | Java 25 | `itemmilestonelog-neoforge-26.2-1.0.1.jar` |
| **26.3** | `26.3.0.76`+ | Java 25 | `itemmilestonelog-neoforge-26.3-1.0.1.jar` |

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
   It also checks creative mode handling, world-firsts, export/import, custom presets,
   admin commands, first-join hints, and broadcast muting.
   mineflayer does not support 26.2 and 26.3 yet, so those get the RCON checks only.

A further job opens a world made on a 1.21.1 server, with records, on a 26.1.2 server
and checks that the records come over (`.github/scripts/run-migration-test.sh`).

The jars from the minimum-NeoForge jobs are uploaded as the workflow artifacts
`itemmilestonelog-<mc_version>`. The GUI is not covered by the automated tests.

### Releasing

1. Set `mod_version` in all four `versions/*/gradle.properties`, update `docs/updates.json`, and add a
   `## <version>` section to [CHANGELOG.md](CHANGELOG.md).
2. Once CI is green on `main`, push the tag `v<version>` (for example `v1.0.1`).
   The workflow runs all tests again and publishes a GitHub Release with the four jars
   and that changelog section. It stops if the tag, the versions and the jars do not
   match (`.github/scripts/check-release.sh`, which also runs on every pull request).
3. Upload the jars to CurseForge as described in [CURSEFORGE.md](CURSEFORGE.md).

## License

Custom License (All Rights Reserved) — see the [LICENSE](LICENSE) file.
Personal use and inclusion in free modpacks (with credit and a link back) are
allowed; redistribution, re-uploading, selling and distributing modified
versions require the author's permission.
