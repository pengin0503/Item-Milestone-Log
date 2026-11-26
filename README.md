# Item Milestone Log

**Records the cumulative playtime (plus in-game Day and real-world date) at which you first obtain each of your goal items.**

Ever wondered *"how many hours into this world did I finally make that item?"* — for example the components of the **ATM Star** in All the Mods 10? Item Milestone Log answers exactly that.

You choose your own target items, per player. The first time a target item enters your inventory, the mod records your **cumulative playtime** (the vanilla *Time Played* statistic — not world time, so sleeping doesn't inflate it), the **in-game Day**, and the **real-world date**, and notifies you in chat:

> ★ Milestone recorded: ATM Star at playtime 87h 42m 10s

Detection is possession-based, not crafting-based, so items produced by machines, automation, AE2 autocrafting, smelting or mob drops are recorded too — not just hand-crafted ones.

> **Detection scope:** Recording uses **simultaneous possession**: it triggers the first time the total count of a target item in your **player inventory (main inventory, offhand, and cursor stack)** reaches the target quantity (1–1,000,000; default 1). Items placed directly into an **Ender Chest**, inside a **Shulker Box**, or into **another mod's storage system** are not counted or recorded until you take them out into your inventory.

## Features

- Per-player target items with optional **quantities (1–1,000,000)**: track e.g. `minecraft:diamond` ×1 and ×100 as separate milestones (simultaneous inventory possession), with configurable **progress alerts** (default 50% and 90% for quantities ≥ 10; opt out with `/milestonelog muteprogress`)
- **Item tag targets**: track a whole tag such as `#c:ingots/iron` (or `#c:ingots/iron#100`); the possession counts of all items in the tag are summed. Tags work in `defaultTargets`, presets, imports/exports, quantity targets, world-firsts, and the `addtag` / `removetag` / `resettag` / `toptag` commands
- Records cumulative playtime (`minecraft:play_time`), in-game overworld Day, and real-world date (`yyyy-MM-dd`) at first obtainment, stored per world, with configurable server display timezone (`displayTimeZone`)
- **World-first records**: tracks who first obtained each item (and quantity) across the whole world (`/milestonelog firsts`, showing current usernames with `(was <old name>)` after renames, with optional server-wide announcement)
- **Away summary**: on login, optionally list the world firsts recorded while you were away (`loginWorldFirstSummary`, default `false`; newest 5 plus a remaining count, skipped if you muted broadcasts)
- **Lookup, rankings, server stats & item info**: inspect other players (`/milestonelog lookup`), browse leaderboards (`/milestonelog top`, `/milestonelog toptag`, `/milestonelog recent`) and server statistics (`/milestonelog stats`), or inspect one item with `/milestonelog info <item>`, with per-player privacy opt-out (`/milestonelog privacy`)
- **Chat, GUI & tooltip display**: item names in chat show the item on hover (`chatItemHover`, default on); modded clients see target/recorded status at the end of item tooltips (`itemTooltipStatus`, default on); chat and GUI playtime/Day text follows the player's language (English output is unchanged; Discord, server logs, exports, and the API stay in English); recorded rows show a ★ when you hold the world first (`guiWorldFirstMark`, default on)
- **In-game GUI** (assign a key under Controls; there is no default key): view playtime and progress, browse targets and recorded milestones, search and add items (with quantity input), add your held item, add the item under the cursor with a separate optional key, browse/apply/save/delete presets (with overwrite warning), and reset records with confirmation
- **NeoForge config screen**: edit all settings from the mod list's **Config** button, except the two webhook URLs (deliberately hidden so they cannot leak in screenshots)
- Chat notification, simultaneous-record grouping (`groupSimultaneousRecords`), and configurable sound effect (`milestoneSound`) the moment a milestone is recorded
- **Advancement trigger**: datapacks can grant advancements through `itemmilestonelog:milestone_recorded` (conditions: `player`, `item`, `count`, `world_first`), including records that already existed when the datapack was added
- **Milestone commands**: optionally run a server command on each confirmed record with `milestoneCommand` and the `%player%`, `%uuid%`, `%item%`, `%count%`, `%playtime%`, and `%worldfirst%` placeholders (or call a `function`)
- **Scoreboard integration**: optionally mirror each player's record count and world-first count into vanilla scoreboard objectives (`scoreboardObjective`, `scoreboardWorldFirstObjective`)
- **Automatic world-data backups** (default on): keeps up to `autoBackupGenerations` compressed snapshots under `<world>/itemmilestonelog/backups/`, written on world save (at most hourly) and on server stop
- Optional server-wide broadcast and preset-completion announcements with per-player opt-out (`/milestonelog mutebroadcast`)
- Optional asynchronous **Discord Webhook** notifications: plain text or rich embeds (`discordUseEmbeds`), English or Japanese (`discordLanguage`), preset-completion posts, `env:VAR_NAME` URL support, `Retry-After` retry, 401/404 auto-pause, per-player opt-out (`/milestonelog discordoptout`), OP `/milestonelog discordtest`, a periodic digest (`discordDigestEnabled`), a separate world-first webhook (`discordWorldFirstWebhookUrl`), and optional silent normal-record posts (`discordSilentRecords`)
- **Export / Import** in JSON, standalone HTML, and CSV per world, plus OP `exportall` (including world-firsts), `exporthtmlall`, `exportcsvall`, and `importall`
- **Custom, datapack & starter presets**: define presets as JSON files under `config/itemmilestonelog/presets/` or ship them in a datapack at `data/<namespace>/itemmilestonelog/presets/<id>.json` (read-only, reloaded with `/reload`), save your current targets in-game, use per-language preset names, check progress (`/milestonelog presetprogress`), bulk-remove targets (`/milestonelog presetremove`, `removeall`), and distribute removable starter targets on first join (`firstJoinPresets`)
- **Rename old item IDs**: OP `/milestonelog renameitem <oldId> <newId> [confirm]` moves targets, records, progress markers, and world-firsts after writing a backup
- **Safety & navigation**: 10-minute `/milestonelog undo` for personal resets and bulk removals, optional `[<page>]` pagination with clickable navigation, and `/milestonelog help [<subcommand>]`
- **OP admin & maintenance commands** to inspect, add/remove targets, apply presets, grant milestones, reset, purge, or prune long-absent empty player data (`prunedata`), with optional NeoForge **permission nodes** so non-OP staff can be granted only the commands they need
- Nothing is recorded in creative or spectator mode (configurable), with in-chat notices when items carried out of creative mode are temporarily skipped
- Built-in presets: `atm_star` (All the Mods 10 endgame) and `vanilla_endgame`
- Server-wide default targets via config file, plus top-level switches to disable presets, export/import, or world-first recording
- **Optional client installation** — on a dedicated server, vanilla clients can still join without installing the mod and use all `/milestonelog` commands; chat messages automatically fall back to the player's client language (`en_us` / `ja_jp`, or English for other languages)
- **Server-side API** (`MilestoneRecordedEvent`, `PresetCompletedEvent`, `RecordCause`, and `ItemMilestoneLogApi` read/write helpers, now including tag keys and `TargetKey.isTag()`) for other mods and KubeJS
- **NeoForge Update Checker** integration (`updateJSONURL`)
- Languages: English, Japanese (日本語)

## GUI & Commands

### GUI

Assign a key to **Open Milestone Log** under *Options → Controls → Key Binds → Item Milestone Log*, then press it to open the Item Milestone Log screen. There is no default key: the obvious one, `M`, already opens the full-screen map of FTB Chunks, which All the Mods 10 includes. Everything available for personal milestones can also be done from the GUI:

- View your current cumulative playtime and overall progress in the header
- Switch between **Targets** (distinguishing personal targets and server-configured default targets) and **Recorded** milestones (earliest first, showing `(Day N, yyyy-MM-dd)`)
- Search all registered items and add targets (with an optional quantity box, 1–1,000,000), or add the item currently in your main hand
- Remove personal targets (server default targets are marked and cannot be removed)
- Open **Presets...** to browse built-in, custom, and datapack presets, apply them with one click, and (when permitted) save your current personal targets as a custom preset or delete custom presets; datapack presets are read-only
- Reset individual records or all records (with confirmation dialogs; targets are kept)
- Assign an optional key to **Add Hovered Item to Targets** (same key category) to add the item under the mouse in inventory, chest, or creative screens; the default is unbound, it ignores text fields, and the result is shown in the action bar
- A ★ in a recorded row's status (`✔ ★ …`) means **you hold the world first** for that milestone (requires a 1.2.0+ server; the server can turn it off with `guiWorldFirstMark = false`)
- Item tooltips on modded clients show up to three gray target/recorded status lines when `itemTooltipStatus` is on. On a 1.0.1/1.1.0 server, tooltips appear only after you open the GUI once or record a milestone (those servers do not sync on login).
- The mod list's **Config** button opens NeoForge's generic settings screen for this PC (see [Configuration](#configuration)); webhook URLs are intentionally omitted

> **Note:** Opening the GUI requires the mod to be installed on the **client** as well as the server. On a dedicated server, vanilla clients without the mod can still join normally and use all commands below.

### Commands

`/iml` is a short alias for `/milestonelog`.

#### Player commands (no OP required)

| Command | Description |
|---|---|
| `/milestonelog` | Summary: your playtime and recorded / total targets (plus a pause notice while in Creative/Spectator) |
| `/milestonelog help [<subcommand>]` | List commands available to you (with clickable suggestions), or show usage for one subcommand |
| `/milestonelog playtime` | Show your current cumulative playtime |
| `/milestonelog add <item> [<count>]` | Add a target item (optional quantity 1–1,000,000, default 1) |
| `/milestonelog addhand [<count>]` | Add the item in your main hand as a target (optional quantity, default 1) |
| `/milestonelog addtag <tag> [<count>]` | Add an item tag target; write the tag without `#` (e.g. `c:ingots/iron`) |
| `/milestonelog removetag <tag> [<count>]` | Remove a personal tag target |
| `/milestonelog resettag <tag> [<count>]` | Clear one of your tag records (undoable for 10 minutes) |
| `/milestonelog remove <item> [<count>]` | Remove a personal target (default quantity 1) |
| `/milestonelog removeall` | Remove all your personal targets (records and `defaultTargets` are kept; undoable for 10 minutes) |
| `/milestonelog targets [<page>]` | List targets with recorded playtime, possession progress, or "not yet obtained" |
| `/milestonelog targets pending [<page>]` | List only targets not yet recorded |
| `/milestonelog targets done [<page>]` | List only targets already recorded |
| `/milestonelog list [<page>]` | List recorded milestones, earliest first, with Day and date |
| `/milestonelog info <item> [<count>]` | Show your status, world first, recorder count/rank, and related tag targets for one item |
| `/milestonelog info hand` | Same as `info <item>` for the item in your main hand |
| `/milestonelog preset list` | List all available presets (built-in, config-folder custom, and datapack) |
| `/milestonelog preset <name>` | Apply a preset (`atm_star`, `vanilla_endgame`, a config-folder preset, or a datapack preset) |
| `/milestonelog presetprogress <name>` | Show how many targets of a preset you have recorded and list remaining targets (up to 50) |
| `/milestonelog presetremove <name>` | Remove a preset's targets from your personal targets (records are kept; undoable for 10 minutes) |
| `/milestonelog reset <item> [<count>]` | Clear one record (it will be recorded again on next obtainment; undoable for 10 minutes) |
| `/milestonelog resetall` | Clear all your records (targets and world-firsts are kept; undoable for 10 minutes) |
| `/milestonelog undo` | Restore your last personal `reset`, `resetall`, GUI reset, `presetremove`, or `removeall` within 10 minutes |
| `/milestonelog firsts [<page>]` | List server-wide world-first milestones (shows `<current> (was <old>)` after a player rename) |
| `/milestonelog lookup <player> [<page>]` | View another player's recorded milestones (controlled by `allowLookup`; self always works) |
| `/milestonelog top [<item> [<count>]]` | Show top 10 by total milestones, or top 10 fastest for `<item> [<count>]`, plus your rank (`allowLookup`) |
| `/milestonelog toptag <tag> [<count>]` | Top 10 fastest for a tag target (`<tag>` without `#`; `allowLookup`) |
| `/milestonelog recent [<n>]` | Show the most recent dated milestones across the server (`n` = 1–20, default 10; `allowLookup`) |
| `/milestonelog stats` | Show server-wide totals and top 5 most-recorded targets (`allowLookup`) |
| `/milestonelog privacy` | Toggle hiding your records from non-privileged players in `lookup`, `top`, `recent`, and `stats` |
| `/milestonelog mutebroadcast` | Toggle muting other players' milestone / world-first / preset-completion broadcasts |
| `/milestonelog muteprogress` | Toggle muting your quantity-target progress alerts |
| `/milestonelog discordoptout` | Toggle excluding your milestones from Discord webhook posts (controlled by `allowDiscordOptOut`) |
| `/milestonelog export` | Export your targets and records to `itemmilestonelog/exports/<player>-<uuid>.json` |
| `/milestonelog exporthtml` | Export your milestones as a standalone HTML page (`<player>-<uuid>.html`) |
| `/milestonelog exportcsv` | Export your recorded milestones as a UTF-8 CSV file (`<player>-<uuid>.csv`) |
| `/milestonelog import <fileName>` | Merge targets (union) and records (smaller playtime wins) from an export file |

> **Pagination & filtering notes:** Omitting `[<page>]` prints the full list, matching 1.0.1 behavior. Passing a page number (starting at `1`) shows 15 entries per page with clickable `[< Prev]` and `[Next >]` buttons. On Minecraft 26.x clients, clicking `[< Prev]` / `[Next >]` on OP `admin` commands displays a vanilla command-confirmation prompt (expected client behavior; non-OP commands run directly).
>
> **Undo notes:** `/milestonelog undo` holds one in-memory snapshot per player for **10 minutes** (cleared when the server stops; empty/failed operations do not overwrite a previous snapshot). It restores records (keeping the smaller playtime if re-recorded in the meantime), `completedPresets`, `progressNotified`, or bulk-removed personal targets without re-firing events or notifications. Admin resets cannot be undone with `/milestonelog undo`.
>
> **Privacy & world-first name notes:** `/milestonelog privacy` hides you from non-privileged players in `lookup` (and its tab-completions), `top`, `recent`, and `stats`. It does **not** hide your data from yourself, OPs / the singleplayer or LAN host / holders of `itemmilestonelog.viewhidden`, `admin` commands, `exportall` / `exporthtmlall` / `exportcsvall`, broadcasts, world-first announcements, `firsts`, or Discord (use `/milestonelog discordoptout` separately for Discord). In `/milestonelog firsts`, each holder is shown with their current known username — formatted as `<current name> (was <old name>)` after a rename — while saved world-first records, exports, and `WorldFirstRecord.playerName` retain the original name; because Minecraft usernames are public, rename history in `firsts` is not covered by `/milestonelog privacy`.

#### Preset management & OP commands

Preset management (`preset save`, `preset delete`, `preset reload`) requires **permission level 2 (OP)** on a dedicated server, and is allowed for the **host** (or players with cheats/OP) in singleplayer and LAN — LAN guests without cheats cannot save, delete, or reload presets unless a permission node below grants it. All `firsts reset*`, `export*all`, `importall`, importing another player's file with `import`, `prunedata`, `renameitem`, `discordtest`, and `admin` commands require **permission level 2 (OP)** unless the matching permission node is granted.

#### Permission nodes (NeoForge permission API, 1.2.0)

Admin commands also accept NeoForge boolean permission nodes, so a permission mod (LuckPerms and similar) can grant individual commands without giving OP. Every node defaults to **"the player has permission level 2"**, so behavior on a server without a permission mod is unchanged.

| Node | Grants |
|---|---|
| `itemmilestonelog.admin` | Everything under `/milestonelog admin` (including F33 and `admin <player> resettag`) |
| `itemmilestonelog.firsts.reset` | `firsts reset`, `firsts resetall`, `firsts resettag` |
| `itemmilestonelog.export.all` | `exportall`, `exporthtmlall`, `exportcsvall` |
| `itemmilestonelog.import.all` | `importall`, and importing another player's export file with `import` |
| `itemmilestonelog.prunedata` | `prunedata` |
| `itemmilestonelog.renameitem` | `renameitem` |
| `itemmilestonelog.discordtest` | `discordtest` (including `discordtest worldfirst`) |
| `itemmilestonelog.presets.manage` | Saving/deleting (and reloading) custom presets on a dedicated server (G1, including the GUI) |
| `itemmilestonelog.viewhidden` | Using `lookup`, `top`, `recent`, `stats`, and the recorder count/rank line of `info` when `allowLookup = false`; viewing records hidden with `/milestonelog privacy` |

- The check is **legacy OR node**: OP / permission level 2 (or the singleplayer/LAN host) keeps access even if a permission mod denies a node. A node can only add access; it cannot take OP access away.
- **Console, RCON, command blocks and functions** keep using the vanilla permission-level check only. The NeoForge permission API needs an online `ServerPlayer`, so it is never queried for them.
- To use a permission mod, set NeoForge's own `permissionHandler` setting to that mod's handler (the default is `neoforge:default_handler`); follow the permission mod's instructions for the exact value. On 1.21.1, 26.1.2, and 26.2 this is the per-world server config `neoforge-server.toml` (world `serverconfig/`, with the global `config/` file as fallback). On 26.3 it is NeoForge's synced config `neoforge-synced.toml` (world `syncedconfig/`, with the global `config/` file as fallback). No configuration is needed without a permission mod.
- Command completion uses the same checks and the command tree is sent to clients around login. A permission change may not show up in tab completion until the player rejoins; execution is checked when the command runs, so it takes effect immediately. Whether a permission mod refreshes the tree is up to that mod.
- `/milestonelog help` uses the same checks, so a command appears there only when the player can use it.

| Command | Description |
|---|---|
| `/milestonelog preset save <name>` | Save your current personal targets as `config/itemmilestonelog/presets/<name>.json` |
| `/milestonelog preset delete <name>` | Delete a custom config-folder preset (built-in and datapack presets cannot be deleted) |
| `/milestonelog preset reload` | Reload `config/itemmilestonelog/presets/` and refresh open GUIs (datapack presets reload with `/reload`) |
| `/milestonelog firsts reset <item> [<count>]` | Clear the world-first record for `<item>` (optional quantity, default 1) |
| `/milestonelog firsts resettag <tag> [<count>]` | Clear the world-first record for a tag target |
| `/milestonelog firsts resetall` | Clear all world-first records |
| `/milestonelog exportall` | Export all players' data and world-firsts to `itemmilestonelog/exports/all-players.json` |
| `/milestonelog exporthtmlall` | Export all players' milestones to `itemmilestonelog/exports/all-players.html` |
| `/milestonelog exportcsvall` | Export all players' recorded milestones to `itemmilestonelog/exports/all-players.csv` |
| `/milestonelog importall <fileName>` | Merge all players' data (and `worldFirsts` if present) from an `exportall` JSON file (up to 32 MiB) |
| `/milestonelog prunedata <days> [confirm]` | Preview (`<days>` 1–3650) or delete (`confirm`) empty, inactive offline player entries |
| `/milestonelog renameitem <oldId> <newId> [confirm]` | Move targets, records, progress markers, and world-firsts from an old item ID to a new registered item; previews without `confirm` and writes a backup first |
| `/milestonelog discordtest [worldfirst]` | Send a test message to `discordWebhookUrl`, or with `worldfirst` to `discordWorldFirstWebhookUrl`, and report the HTTP status (never prints the URL) |
| `/milestonelog admin players [<page>]` | List all players with saved data (name, UUID, target/record counts) |
| `/milestonelog admin <player> list [<page>]` | View a player's recorded milestones (works offline) |
| `/milestonelog admin <player> targets [<page>]` | View a player's targets (works offline) |
| `/milestonelog admin <player> add <item> [<count>]` | Add a personal target to a player (works offline) |
| `/milestonelog admin <player> remove <item> [<count>]` | Remove a personal target from a player (works offline) |
| `/milestonelog admin <player> preset <name>` | Apply a preset to a player (works offline) |
| `/milestonelog admin <player> grant <item> [<count>]` | Record a milestone for an **online** player without checking possession |
| `/milestonelog admin <player> reset <item> [<count>]` | Clear a specific record for a player (works offline) |
| `/milestonelog admin <player> resettag <tag> [<count>]` | Clear a specific tag record for a player (works offline) |
| `/milestonelog admin <player> resetall` | Clear all records for a player (works offline) |
| `/milestonelog admin <player> purge` | Delete all milestone data for a player (logged to server log) |

> **Admin `grant` notes:** `/milestonelog admin <player> grant` requires the target player to be **online**, adds the item as a personal target if not already tracked, ignores creative/spectator pause, notifies the player, fires `MilestoneRecordedEvent` with `RecordCause.ADMIN_GRANT` (and `PresetCompletedEvent` if a preset finishes), and logs to the server log. It does **not** register a world-first, play a sound, broadcast, or post to Discord. This is an explicit administrator action, distinct from automatic retroactive/inferred milestone detection (which the mod intentionally does not perform).
>
> **`prunedata` notes:** `/milestonelog prunedata <days> [confirm]` only targets **offline** players who have 0 records, 0 personal targets, no `carriedFromCreative` items, and all personal preferences at default (`muteBroadcasts`, `hideFromLookup`, `muteProgressAlerts`, `excludeFromDiscord` all `false`), and whose `lastSeen` (or vanilla playerdata file timestamp for pre-1.1.0 entries) is older than `<days>` days. World-first records are never deleted. If a pruned player rejoins later, fresh data is created and they will see `showFirstJoinHint` (and receive `firstJoinPresets`) again.

#### Tag target notes

- **Command syntax:** write tags without the leading `#` in `addtag`, `removetag`, `resettag`, `toptag`, `firsts resettag`, and `admin <player> resettag` (for example `/milestonelog addtag c:ingots/iron 100`). In `defaultTargets`, preset JSON, and imports/exports, write the full key with `#`, e.g. `#c:ingots/iron` or `#c:ingots/iron#100`.
- **Detection:** a tag target is recorded when the summed count of all items in that tag reaches the target quantity. Tag entries are stored under the tag key (`#namespace:path`), so tag targets, records, progress, and world-firsts stay separate from item-ID targets.
- **Display:** names use NeoForge's tag translation when the tag (or a mod) provides one, otherwise the tag ID is shown. The GUI uses the first item in the tag as the icon and falls back to a barrier icon if the tag cannot be resolved. Tag targets cannot be added from the item picker; use the commands, a preset, `defaultTargets`, or an import.
- **Older clients:** 1.0.1 and 1.1.0 clients silently ignore tag entries in the sync payload, so they simply do not show tag targets or records in the GUI; they still connect normally. A 1.1.0 server has no tag support at all.
- **API:** `TargetKey.isTag()` tells you whether an `itemId` from `MilestoneRecordedEvent#getItemId()`, `getTargets`, or `getTargetKeys` is a tag. Check it before parsing the string as an item ID. The 1.2.0 write helpers accept tag keys.

#### `info` and `renameitem` notes

- `info <item>` accepts IDs that are no longer registered (so old records remain inspectable). It shows your recorded/target status, the world-first holder and time, and the recorder count plus your rank. The count/rank line follows `allowLookup`: when `allowLookup = false`, only privileged viewers (OP, host, or `itemmilestonelog.viewhidden`) see it. Related tag targets are listed separately (up to 10).
- `renameitem` only rewrites saved player data and world-firsts. It does **not** rewrite `defaultTargets`, preset JSON files, datapack presets, or export files; it prints a reminder if an old ID remains in `defaultTargets` or a loaded preset. It refuses to run if a backup cannot be written, and it writes that backup regardless of the hourly automatic-backup limit.

#### Advancement, command & scoreboard hooks

- **Advancement trigger (F2):** a datapack advancement can use `"trigger": "itemmilestonelog:milestone_recorded"` with optional `player`, `item`, `count` (`MinMaxBounds.Ints`), and `world_first` conditions. For tag targets, `item` must be the exact tag string (e.g. `"#c:ingots/iron"`); this checks the key, not tag membership. Existing records are replayed when the player logs in and on `/reload`, so adding a datapack later still grants advancements. Resetting a record does **not** revoke an advancement; use `/advancement revoke` or the datapack's own reset if needed. Right after a datapack is added, `announce_to_chat` advancements may all announce at once; turn that off in the advancement JSON if it is noisy. Example:

  ```json
  { "criteria": { "diamonds_100": {
    "trigger": "itemmilestonelog:milestone_recorded",
    "conditions": { "item": "minecraft:diamond", "count": { "min": 100 } } } } }
  ```

- **`milestoneCommand` (F7):** runs without a leading `/` for each confirmed record (including the API's `recordNow`, but not imports, resets, or admin `grant`). Available placeholders: `%player%`, `%uuid%`, `%item%` (a tag ID for tag targets), `%count%`, `%playtime%` (formatted, may contain spaces), and `%worldfirst%` (`true` / `false`). Use `%uuid%` when player names may contain unusual characters. For multiple actions use a datapack function, e.g. `milestoneCommand = "function mypack:milestone_reward"`. The command runs at the end of the tick with server-console permission and suppressed output; failures are logged and never block the record. Only the server owner can edit this value. `%player%` is substituted only when the name matches `^[A-Za-z0-9_.*+-]{1,20}$` (Geyser/Floodgate names and longer names are covered by this pattern, but `@` is not); otherwise the command is skipped with a warning. `%item%` is always a validated item or tag ID.
- **Scoreboard (F17):** set `scoreboardObjective` and/or `scoreboardWorldFirstObjective` to write each player's record count and world-first count. The mod creates a missing objective with the `dummy` criterion (display names "Milestones" / "World Firsts") and updates it on login, records, resets, imports, renames, and world-first changes. Use a dedicated name such as `iml_records`; pointing it at an objective another datapack uses will overwrite those scores. Hidden records (`/milestonelog privacy`) are still counted. Clearing the setting stops updates but does not delete existing objectives or scores; remove them with vanilla `/scoreboard`.

## Custom Presets

Presets can come from three places: the built-in presets, UTF-8 JSON files under `config/itemmilestonelog/presets/<id>.json` (on the server for dedicated servers), and datapacks under `data/<namespace>/itemmilestonelog/presets/<id>.json`. The config folder is created automatically on server startup; no sample file is generated.

Example (`config/itemmilestonelog/presets/my_pack.json`):

```json
{
  "name": "My Pack Goals",
  "items": [
    "minecraft:diamond",
    { "item": "minecraft:diamond", "count": 100 },
    "#c:ingots/iron#64",
    "allthetweaks:atm_star"
  ]
}
```

- `name` (optional): display name shown in the GUI, `/milestonelog preset list`, preset completion messages, and Discord notifications. Either a single string used for every language (`"name": "Starter Kit"`), or an object mapping a language code to a name (`"name": {"en_us": "Starter Kit", "ja_jp": "スターターキット"}`). Language keys are matched case-insensitively; entries with blank keys or blank/non-string values are ignored, and each name is capped at 256 characters. For each viewer the mod picks the entry matching their client language, then `"en_us"`, then the first entry in file order, and finally the file ID (console/RCON resolve with `en_us` priority; Discord notifications use `discordLanguage`). An object with no valid entry behaves like a missing `name`. **Compatibility:** 1.0.1 and 1.1.0 cannot read the object form — they fall back to the file ID as the display name (the file itself still loads fine).
- `items` (required): array of item IDs (`"namespace:path"` for quantity 1), tag keys (`"#namespace:path"` or `"#namespace:path#count"`), or objects (`{"item": "namespace:path", "count": 100}`, `{"item": "#namespace:path", "count": 100}`, count 1–1,000,000). IDs and tags that are not currently available are preserved in the preset and simply skipped when applied, so modpack presets work across packs.
- Preset IDs (`<id>`) must be 1–32 characters using `a-z`, `0-9`, `_`, or `-`. Files matching built-in preset IDs (`atm_star`, `vanilla_endgame`) are ignored. Subfolders inside the datapack preset directory are ignored with a warning.
- Because `list`, `save`, `delete`, and `reload` are subcommands under `/milestonelog preset`, a custom preset literally named `list`, `save`, `delete`, or `reload` cannot be applied via `/milestonelog preset <name>` (use the GUI or a different ID).
- **Precedence:** built-in > `config/itemmilestonelog/presets/` > datapack. If two datapacks provide the same ID, the alphabetically first namespace wins; the losing file is logged and ignored.
- **Datapack presets are read-only:** `preset save`, `preset delete`, and the GUI refuse to overwrite or delete a datapack-only ID (`Preset '<id>' comes from a datapack and cannot be overwritten or deleted.`). To customize one, save under a new ID or put a file with the same ID in the config folder (the config file then takes precedence). Datapack presets are re-read by `/reload`, not by `preset reload` (which reads only the config folder); they are cleared when a singleplayer world closes.
- **Overwriting custom presets:** When you overwrite an existing config-file preset via `/milestonelog preset save <name>` or the GUI, its existing JSON `name` display field is preserved — a plain string or a per-language object alike (any other unknown JSON fields are not preserved). The GUI Save Preset screen changes the button to **Overwrite** with a notice when matching an existing custom preset ID, and disables saving over built-in or datapack preset IDs.
- **Preset completion:** Completing all resolvable targets of any built-in, config-file, or datapack preset is tracked per player by preset ID (`completedPresets`). Editing, deleting, or recreating a preset with the same ID does not un-complete it; `/milestonelog resetall` clears `completedPresets`, while a single `/milestonelog reset` does not.
- **Trade-off**: Config-folder presets are shared across all worlds on that client/server and do not travel automatically if you copy only a world folder. Datapack presets travel with the world/datapack and are the easiest way for a modpack to ship goals; JSON in the datapack's `data/` folder can also be produced by tools such as KubeJS data generation.

## Export / Import Format

All export commands write to `<world>/itemmilestonelog/exports/`:

- **JSON**: `<player>-<uuid>.json` (`export`) and `all-players.json` (`exportall`).
- **HTML**: `<player>-<uuid>.html` (`exporthtml`) and `all-players.html` (`exporthtmlall`) — self-contained UTF-8 HTML with inline CSS, no JavaScript, and no external resources.
- **CSV**: `<player>-<uuid>.csv` (`exportcsv`) and `all-players.csv` (`exportcsvall`) — UTF-8 with BOM, CRLF, RFC 4180 quoting, spreadsheet formula-injection protection, one row per recorded milestone.

> **Dedicated server note:** HTML and CSV export files are written to the **server's** world folder (`<world>/itemmilestonelog/exports/`), where regular players cannot open them directly — HTML/CSV exports are mainly intended for singleplayer and server owners.

JSON export example (`<player>-<uuid>.json`):

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

When importing with `/milestonelog import <fileName>`, targets are merged by union, and for each `(item, count)` entry in `records`, the entry with the smaller `ticks` wins (keeping that side's `day` and `epoch`). Export files without `count` fields are imported as quantity 1. Tag keys round-trip in the same `#namespace:path` / `#namespace:path#count` form; tag targets and records from 1.2.0+ exports are preserved. Imports do not trigger `MilestoneRecordedEvent`, `PresetCompletedEvent`, the advancement trigger, `milestoneCommand`, world-first registration from player records, or Discord notifications.

For server migrations, `/milestonelog exportall` writes all players plus a `worldFirsts` array into `all-players.json`, and OP `/milestonelog importall <fileName>` (up to 32 MiB) merges all players and restores `worldFirsts` (if a world-first already exists for a key, the earlier known `epoch` wins; if either timestamp is unknown, the existing entry is kept). Notes:
- `importall` reads synchronously on the server thread, so very large export files may briefly pause the server tick.
- `all-players.json` files produced by 1.0.1 do not contain `worldFirsts`, so world-first records are not restored from them.
- Moving between online-mode and offline-mode servers changes player UUIDs, so players will be imported under their exported UUIDs as separate entries.

## Configuration

`config/itemmilestonelog-common.toml` (Minecraft 1.21.1, 26.1.2, 26.2) or
`config/itemmilestonelog-local.toml` (Minecraft 26.3, where NeoForge renamed the
config type). On a dedicated server, edit the file on the server.

Settings can also be changed from the mod list's **Config** screen, which uses NeoForge's
generic configuration screen. The two webhook URLs (`discordWebhookUrl` and
`discordWorldFirstWebhookUrl`) are deliberately not shown there, because their values
would be exposed in screenshots and streams; edit them in the file instead.

> **Multiplayer note:** the common (26.3: local) config file belongs to each PC. Changing
> it while connected to a multiplayer server does not change the server's settings; only
> settings the client itself uses (such as `itemTooltipStatus`) take effect locally. In
> singleplayer the same file applies to all worlds.

- `defaultTargets` — item IDs (and/or tag keys) every player tracks by default (append `#<count>`, e.g. `"minecraft:diamond#100"` or `"#c:ingots/iron#64"`, for quantities > 1)
- `scanIntervalTicks` — inventory scan interval in ticks (default `20` ticks = 1 s, `1–1200`)
- `notifyPlayer` — chat notification on record (default `true`)
- `broadcastMilestones` — announce records to all players (default `false`)
- `ignoreCreativeAndSpectator` — record nothing in creative or spectator mode (default `true`)
- `milestoneSoundEnabled` — play a sound to the player on record, independent of `notifyPlayer` (default `true`)
- `milestoneSound` — vanilla sound event ID to play on record (default `"minecraft:ui.toast.challenge_complete"`)
- `announceWorldFirst` — announce server-wide world-first records to all online players (default `false`)
- `loginWorldFirstSummary` — send a chat summary of world firsts recorded while a player was away when they log in (default `false`; newest 5 plus `...and N more`, skipped when the player has `muteBroadcasts` enabled or no stored `lastSeen`). If the server crashes before the logout `lastSeen` is saved, world firsts since the previous login can be included again; the duplicate is harmless.
- `showFirstJoinHint` — show a one-time usage tip when a player first joins a world (default `true`)
- `presetsEnabled` — enable built-in, config-folder, and datapack presets in commands and GUI (default `true`; disabling does not delete existing preset files)
- `exportImportEnabled` — enable all export and import commands, including for OPs (default `true`; automatic backups are unaffected)
- `worldFirstEnabled` — record new world-first milestones (default `true`; existing world-first records remain viewable)
- `displayTimeZone` — timezone ID for server-formatted dates in chat, server logs, Discord, and HTML/CSV exports, e.g. `"UTC"`, `"Asia/Tokyo"`, `"+09:00"` (default `""` = server default). The client GUI always uses the player's local PC timezone.
- `progressAlerts` — send chat alerts when a quantity target crosses possession thresholds, independent of `notifyPlayer` (default `true`)
- `progressAlertThresholds` — percentage thresholds (`1–99`) for progress alerts (default `[50, 90]`; empty disables)
- `progressAlertMinCount` — minimum target quantity required for progress alerts (`2–1,000,000`, default `10`)
- `groupSimultaneousRecords` — group notifications per channel when 3 or more milestones are recorded for the same player in the same tick (default `true`; set to `false` for 1.0.1 per-record messages)
- `announcePresetCompletion` — announce preset completions to all online players instead of only the completing player (default `false`)
- `allowLookup` — allow all players to use `lookup`, `top`, `toptag`, `recent`, `stats`, and the count/rank part of `info` (default `true`; when `false`, restricted to OPs, the singleplayer/LAN host, and holders of the `itemmilestonelog.viewhidden` permission node, though looking up yourself always works)
- `firstJoinPresets` — list of preset IDs automatically applied as removable personal targets on a player's very first join (default `[]` = disabled). Only applies when a player has no saved data yet — existing players (or players created by `importall`) are unaffected (use `/milestonelog admin <player> preset <name>` for them); players removed by `prunedata` or `admin <player> purge` receive these presets again on rejoin.
- `milestoneCommand` — optional server command (without a leading `/`) run for each confirmed record, with `%player%`, `%uuid%`, `%item%`, `%count%`, `%playtime%`, and `%worldfirst%` placeholders (default `""` = disabled; see the command-hook notes above)
- `chatItemHover` — attach a `show_item` hover to item names in the mod's chat messages (default `true`; the text is unchanged)
- `scoreboardObjective` — optional scoreboard objective for each player's milestone record count (default `""` = disabled; `dummy` objective created on first write)
- `scoreboardWorldFirstObjective` — optional scoreboard objective for each player's world-first count (default `""` = disabled; if both settings use the same name, the world-first objective is ignored with a warning)
- `guiWorldFirstMark` — send world-first markers (★) for the GUI (default `true`; only 1.2.0+ clients receive them)
- `itemTooltipStatus` — show target/recorded lines in item tooltips on this client (default `true`; read from each player's own config file, not the server's)
- `autoBackupEnabled` — write automatic `MilestoneData` backups on world save (at most hourly) and on server stop (default `true`; independent of `exportImportEnabled`)
- `autoBackupGenerations` — number of automatic backup files to keep (`1–20`, default `5`)
- `discordWebhookUrl` — optional Discord Webhook URL for record, preset-completion, and digest notifications (default `""`; treated as a secret and never logged). Supports `"env:VAR_NAME"` to read the URL from an environment variable.
- `discordWorldFirstWebhookUrl` — optional second webhook used only for world-first posts (default `""` = use `discordWebhookUrl`). When set, world-firsts are sent only there, while records, preset completions, and digests continue to use `discordWebhookUrl`. Supports `"env:VAR_NAME"`.
- `discordNotifyScope` — Discord notification scope: `"all"` or `"world_first"` (default `"all"`; applies to normal posts and the digest)
- `discordUseEmbeds` — send Discord notifications as rich embeds instead of plain text (default `false`)
- `discordLanguage` — language for Discord messages: `"en_us"` or `"ja_jp"` (default `"en_us"`)
- `discordNotifyPresetCompletion` — post preset completions to Discord independently of `announcePresetCompletion` and `discordNotifyScope` (default `false`)
- `discordSilentRecords` — add Discord's `SUPPRESS_NOTIFICATIONS` flag (`flags: 4096`) to normal record posts and grouped posts without a world first (default `false`; world-first, preset-completion, digest, and test posts still notify)
- `discordDigestEnabled` — send a periodic digest of new records and world-firsts (default `false`)
- `discordDigestIntervalDays` — days between digests (`1–30`, default `7`). The first run records the current time without sending the world's history; failed 429 deliveries are not retried, and imported records with a timestamp inside the period are included.
- `allowDiscordOptOut` — allow players to opt out of Discord posts via `/milestonelog discordoptout` (default `true`; when `false`, saved preferences are preserved but ignored)

### Recording, Grouping & Creative Mode Behavior

Items picked up from the ground, crafted by hand or taken out of a furnace are recorded
right away; items that arrive in other ways (machines, AE2, chests, `/give`) are found
by the inventory scan, so their timestamp can be up to `scanIntervalTicks` late.

With `groupSimultaneousRecords = true`, if 3 or more milestones are recorded for the same player in the same tick (for example after applying a preset or moving items from a chest), notifications are grouped per channel at the end of the tick: chat shows up to 5 items plus `and N more`, Discord shows up to 20 lines plus `...and N more`, and the milestone sound plays once per tick. API events and server log entries still fire individually per record.

With `ignoreCreativeAndSpectator = true`, items taken in creative mode do not count either:
items you still carry when you switch back to survival or adventure are only recorded
once they have left your inventory and you obtain them again. When you leave creative or
spectator mode while carrying untracked target items, the mod notifies you in chat, tags
those entries with `(carried from creative)` in `/milestonelog targets`, and shows a
recording-paused reminder in `/milestonelog` while you remain in creative or spectator
mode. Carry tracking works per item type, so putting such an item in a chest and taking it
out later does count; use `/milestonelog reset <item>` if that was not meant as a milestone.

### Discord Webhook Details

- **Environment variables (`env:VAR_NAME`):** Set `discordWebhookUrl = "env:IML_DISCORD_WEBHOOK"` (and/or `discordWorldFirstWebhookUrl`) to avoid storing the webhook URLs in plaintext config files. Because environment variables are read from the server process, changing the variable requires restarting the server.
- **Singleplayer & modpack caution:** In singleplayer/LAN, the config file is shared across all worlds on that installation. If a webhook is configured in singleplayer/LAN, a warning is logged when opening a world. Modpack authors should always clear both webhook URLs before exporting `config/`.
- **Embed item names & language:** When `discordUseEmbeds = true`, item display names are resolved on the server; dedicated servers only load English item names, so embed item titles stay in English even when `discordLanguage = "ja_jp"`.
- **Threads / forum channels:** The configured webhook URL is posted as-is (so Discord's `?thread_id=<id>` query parameter for threads or forum channels is preserved; per Discord's webhook API, forum/media channels require a `thread_id`).
- **Rate limits & dead webhooks:** On HTTP 429, the sender retries once if `Retry-After` is 5 seconds or less (otherwise drops the message). On HTTP 401 or 404, delivery to that URL pauses with a single warning until the resolved URL changes or the matching `/milestonelog discordtest` succeeds. The two URLs are tracked independently, so one dead webhook does not stop the other.
- **World-first routing:** When `discordWorldFirstWebhookUrl` is set and resolvable, world-first posts go only there. `discordWebhookUrl` continues to receive normal records (subject to `discordNotifyScope`), preset completions, and digests. If `discordNotifyScope = "world_first"` and only the main URL is set, only world-firsts are posted; with the second URL set, the main URL gets no record posts at all (preset completions and digests still go there).
- **Weekly digest:** `discordDigestEnabled` sends at most one summary per `discordDigestIntervalDays` days, using the main webhook only. The first run just records the current time; records with an unknown timestamp (`epoch = 0`, e.g. very old 1.0.0 data) are skipped, and at most the first 20 lines plus `...and N more` are sent. The timestamp is updated when the digest is queued, so a digest lost to HTTP 429 or a network error is not retried; imported records with an in-period timestamp are included.
- **Silent records:** `discordSilentRecords = true` adds `"flags": 4096` (`SUPPRESS_NOTIFICATIONS`) to normal record payloads and grouped posts that contain no world first. World-first posts, preset completions, digests, and test posts are unaffected.
- **Testing & opt-out:** OPs can verify configuration at any time with `/milestonelog discordtest` (main webhook) or `/milestonelog discordtest worldfirst` (world-first webhook); neither exposes the URL. Players can exclude their milestones (including world-firsts, grouped posts, and preset completions) with `/milestonelog discordoptout`; this does **not** stop in-game broadcasts, world-first announcements, `firsts`, server logs, exports, or API events.

## Mod & KubeJS API

The `com.pengin0503.itemmilestonelog.api` package provides server-side events, read-only query helpers, and write helpers:

- `MilestoneRecordedEvent` fires when a record is confirmed during normal gameplay, via `ItemMilestoneLogApi.recordNow`, or via an admin `grant`. `event.isWorldFirst()` indicates whether the record established the world-first, and `event.getCause()` returns a `RecordCause` (`GAMEPLAY` / `API` / `ADMIN_GRANT`) so reward scripts can tell them apart.
- `PresetCompletedEvent` fires (non-cancellable, after `MilestoneRecordedEvent`) when a player completes all resolvable targets of a preset for the first time, exposing `getPlayer()`, `getPresetId()`, and `isBuiltin()`.
- `ItemMilestoneLogApi.addTarget(server, playerUuid, targetKey)` / `removeTarget(server, playerUuid, targetKey)` add or remove a personal target, with the same validation, 1024-target limit, and cleanup as `/milestonelog add` / `remove` (`defaultTargets` keys cannot be removed). They return `true` when something changed.
- `ItemMilestoneLogApi.recordNow(serverPlayer, targetKey)` records a target without checking possession, through the normal record path (notifications, world-first, events, Discord, presets). It returns `false` if the key is not one of the player's effective targets, is already recorded, recording is paused (creative/spectator), or the player is a `FakePlayer`. Calling it from a `MilestoneRecordedEvent` listener is allowed; chains nested deeper than 16 return `false` with a warning.
- The write helpers must be called on the server thread (otherwise they throw `IllegalStateException`) and also re-sync the GUI of online players. Since 1.2.0 they accept tag keys; `addTarget` requires a tag that exists and is non-empty (as with tags applied from presets, unavailable tags are skipped).
- In 1.2.0, `MilestoneRecordedEvent#getItemId()` and the query helpers can return a tag key beginning with `#`. Call `TargetKey.isTag()` before treating the string as an item ID; `ItemMilestoneLogApi` and the event docs expose the same distinction. `TargetKey` also validates tag forms (`#namespace:path` and `#namespace:path#count`).

### Java (NeoForge Mod)

```java
import com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.api.PresetCompletedEvent;
import com.pengin0503.itemmilestonelog.api.RecordCause;
import net.neoforged.bus.api.SubscribeEvent;

public class MyModEvents {
    @SubscribeEvent
    public static void onMilestoneRecorded(MilestoneRecordedEvent event) {
        String itemId = event.getItemId();
        int count = event.getCount();
        int ticks = event.getPlayTicks();
        long day = event.getDay();
        boolean worldFirst = event.isWorldFirst();
        RecordCause cause = event.getCause();
        // Query read-only API (on MC 26.x, use event.getPlayer().level().getServer()):
        var wf = ItemMilestoneLogApi.getWorldFirst(
                event.getPlayer().getServer(), itemId, count);
    }

    @SubscribeEvent
    public static void onPresetCompleted(PresetCompletedEvent event) {
        String presetId = event.getPresetId();
        boolean builtin = event.isBuiltin();
    }
}
```

> Note: `ItemMilestoneLogApi.getTargets(server, playerUuid)` returns saved personal targets with quantity 1, and `getTargetKeys(server, playerUuid)` returns all saved personal `(itemId, count)` targets; resolving server-configured `defaultTargets` is the caller's responsibility.

### KubeJS (Server Script)

```js
// kubejs/server_scripts/milestones.js
NeoForgeEvents.onEvent('com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent', event => {
  const player = event.getPlayer()
  if (event.isWorldFirst() && event.getCause().name() == 'GAMEPLAY') {
    player.tell(`World first: ${event.getItemId()} x${event.getCount()} at ${event.getPlayTicks()} ticks!`)
  }
})

NeoForgeEvents.onEvent('com.pengin0503.itemmilestonelog.api.PresetCompletedEvent', event => {
  if (event.getPresetId() == 'vanilla_endgame') {
    event.getPlayer().tell('Completed vanilla_endgame!')
  }
})
```

Illustrative example of calling `ItemMilestoneLogApi.recordNow` from a quest-completion callback (adjust the quest mod's event name and player property to match your installed KubeJS / quest integration):

```js
// Illustrative example — check your quest mod's KubeJS event API for exact names
const ItemMilestoneLogApi = Java.loadClass('com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi')
const TargetKey = Java.loadClass('com.pengin0503.itemmilestonelog.api.TargetKey')

// Inside your quest mod's server-thread completion callback:
// Returns false unless minecraft:nether_star is one of the player's effective targets and not yet recorded
ItemMilestoneLogApi.recordNow(serverPlayer, TargetKey.of('minecraft:nether_star'))
```

## Saved data

Targets, records, and world-firsts are stored per world, for all players in one file:

| Minecraft | File |
|---|---|
| 1.21.1 | `<world>/data/itemmilestonelog_data.dat` |
| 26.1.2 / 26.2 / 26.3 | `<world>/data/itemmilestonelog/milestones.dat` |

When a 1.21.1 world is opened with a 26.x version of the mod, the records (including 1.0.0/1.0.1 saves, world-first records, and 1.1.0/1.2.0 fields) are read from the 1.21.1 file and saved in the new one; the 1.21.1 file stays in place and is not modified.

Tag targets and tag records are stored under the tag key itself: `#namespace:path` (quantity 1) or `#namespace:path#count` in `targets`, `records`, `worldFirsts`, and `progressNotified`. The 1.2.0 root field `lastDigestEpoch` (long, epoch milliseconds, omitted while 0) records the last Discord digest time; it is not the same as a player's `lastSeen`.

The 1.4.0 root field `schemaVersion` (int, always written; current value `1`) records the saved-data format version. Data written up to 1.3.1 has no such field and is read as version 0, then saved with version 1 on the next write. A value greater than the current version (data from a newer release) is read best-effort and preserved on save instead of being downgraded. The field is not part of the JSON export/import format.

> **Downgrading from 1.4.0 to 1.3.1:** 1.3.1 does not read `schemaVersion` and omits it on the next save. Targets, records, world-firsts, and the other saved fields are unaffected.

> **Downgrading from 1.2.0 to 1.1.0 or 1.0.1:** older versions do not accept the tag key form and do not read `lastDigestEpoch`. On the next save they drop all tag targets, tag records, tag world-firsts, and `lastDigestEpoch` (a return to 1.2.0 then treats the digest as a first run). Back up the world first — the automatic backups below are one option — because a downgrade is not reversible for tag data.

> **Downgrading from 1.1.0 to 1.0.1:** 1.0.1 ignores the 1.1.0 per-player fields (`progressNotified`, `completedPresets`, `hideFromLookup`, `muteProgressAlerts`, `lastSeen`, `excludeFromDiscord`) and omits them on the next save. Your targets, records, and world-firsts stay intact, but personal privacy/mute/Discord opt-out settings are reset and progress or preset-completion notifications may fire again if you later upgrade back to 1.1.0.

### Automatic backups

With `autoBackupEnabled = true` (the default), the mod writes compressed `MilestoneData` snapshots to `<world>/itemmilestonelog/backups/milestones-YYYYMMDD-HHmmss.dat` on world save (at most once per hour) and once on server stop, keeping the newest `autoBackupGenerations` files (default 5). Empty data is not written, and only files matching that name pattern are removed during rotation. `renameitem` also forces a backup before changing anything.

To restore a backup:

1. Stop the server (or close the world) so the live data is not overwritten.
2. Copy the chosen `milestones-*.dat` over the live file from the table above, using the live file's actual name.
3. Start the server again.

When moving a 1.21.1-era backup to a 26.x world, place it as `data/itemmilestonelog_data.dat` while `data/itemmilestonelog/milestones.dat` does not exist; the normal 26.x migration then reads it. Keep a copy of the original data before restoring.

## Supported Versions & Requirements

| Minecraft | NeoForge (minimum) | Java | JAR Artifact |
|---|---|---|---|
| **1.21.1** | `21.1.1`+ | Java 21 | `itemmilestonelog-neoforge-1.21.1-1.2.0.jar` |
| **26.1.2** | `26.1.2.71`+ | Java 25 | `itemmilestonelog-neoforge-26.1.2-1.2.0.jar` |
| **26.2** | `26.2.0.57`+ | Java 25 | `itemmilestonelog-neoforge-26.2-1.2.0.jar` |
| **26.3** | `26.3.0.76`+ | Java 25 | `itemmilestonelog-neoforge-26.3-1.2.0.jar` |

Each jar is compiled against the minimum NeoForge version above and is tested on both
that version and the newest NeoForge build at release time (1.21.1: `21.1.267`,
26.1.2: `26.1.2.129`, 26.2: `26.2.0.95`, 26.3: `26.3.0.115`). Use the jar that
matches your Minecraft version; each one only loads on its own Minecraft version.

Built for long modpack playthroughs such as All the Mods 10 (1.21.1), as well as modern 26.x NeoForge worlds and servers.

### Adding Translations

Translations are shared by all Minecraft versions under `common/resources/assets/itemmilestonelog/lang/`. To contribute a new language (human translations via pull request; machine translations are not bundled):

1. Add `common/resources/assets/itemmilestonelog/lang/<lang_code>.json` (e.g. `de_de.json`), copying keys from `en_us.json` and keeping placeholder count/order identical (escape literal `%` as `%%`). Missing keys automatically fall back to `en_us`.
2. Add `<lang_code>` to `ServerLang.BUNDLED_LANGUAGES` (in each `versions/<mc>/.../ServerLang.java`).
3. Run the unit tests (`LangFilesTest` and `ServerLangTest` verify that `BUNDLED_LANGUAGES` matches the lang directory and that all keys and placeholders are valid).

### Building from Source

Each Minecraft version lives under `versions/<mc_version>` and shares common pure-Java logic (`common/src/main/java`) and translation assets (`common/resources`):

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

1. `identical-files` check (`.github/scripts/check-identical-files.sh`),
2. `build` with unit tests and GameTests (`runGameTestServer`),
3. a check of the jar contents (`.github/scripts/verify-jar.sh`),
4. the release jar on a real NeoForge dedicated server
   (`.github/scripts/run-server-tests.sh`): command registration over RCON and, on
   1.21.1 and 26.1.2, a vanilla-protocol client ([mineflayer](https://github.com/PrismarineJS/mineflayer))
   that joins without the mod and runs the commands, gets items recorded by pickup
   and by the inventory scan, and checks that the records survive a server restart.
   It also checks creative mode handling, world-firsts, export/import, custom presets,
   admin commands, first-join hints, and broadcast muting.
   mineflayer does not support 26.2 and 26.3 yet, so those get the RCON checks only.

Additional jobs verify 1.21.1 → 26.1.2 world migration (`.github/scripts/run-migration-test.sh`)
and 1.0.1 ↔ development client/server network compatibility using a real Minecraft client
under Xvfb (`.github/scripts/run-compat-test.sh`). The Forge 1.20.1 port is built and
unit-tested with Java 17, and its release jar runs the same dedicated-server RCON and
vanilla-client checks on Forge 47.3.0 and 47.4.29.

The jars from the minimum-NeoForge jobs are uploaded as the workflow artifacts
`itemmilestonelog-<mc_version>`. The GUI layout is not covered by the automated tests.

### Releasing

1. Set `mod_version` in all five `versions/*/gradle.properties` (including
   `versions/1.20.1-forge`), update `docs/updates.json` (in the release commit after upload), and add a
   `## <version>` section to [CHANGELOG.md](CHANGELOG.md).
2. Once CI is green on `main`, push the tag `v<version>` (for example `v1.2.0`).
   The workflow runs all tests again and publishes a GitHub Release with the six jars
   listed in `publish-manifest.json` and that changelog section. It stops if the tag,
   the versions and the jars do not match (`.github/scripts/check-release.sh`, which
   also runs on every pull request).
3. Upload the jars to CurseForge as described in [CURSEFORGE.md](CURSEFORGE.md).

## License

Custom License (All Rights Reserved) — see the [LICENSE](LICENSE) file.
Personal use and inclusion in free modpacks (with credit and a link back) are
allowed; redistribution, re-uploading, selling and distributing modified
versions require the author's permission.
