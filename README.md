# Item Milestone Log

**Records the cumulative playtime at which you first obtain each of your goal items.**

Ever wondered *"how many hours into this world did I finally make that item?"* — for example the components of the **ATM Star** in All the Mods 10? Item Milestone Log answers exactly that.

You choose your own target items, per player. The first time a target item enters your inventory, the mod records your **cumulative playtime** (the vanilla *Time Played* statistic — not world time, so sleeping doesn't inflate it) and notifies you in chat:

> ★ Milestone recorded: ATM Star at playtime 87h 42m 10s

Detection is possession-based, not crafting-based, so items produced by machines, automation, AE2 autocrafting, smelting or mob drops are recorded too — not just hand-crafted ones.

## Features

- Per-player target items: every player tracks their own goals
- Records cumulative playtime (`minecraft:play_time`) at first obtainment, stored per world
- **In-game GUI** (default key `M`, rebindable in Controls): view playtime and progress, browse targets and recorded milestones, search and add items, add your held item, apply presets, remove personal targets, and reset records with confirmation
- Chat notification the moment a milestone is recorded (configurable, optional server-wide broadcast)
- Built-in presets: `atm_star` (All the Mods 10 endgame) and `vanilla_endgame`
- Server-wide default targets via config file
- **Optional client installation** — on a dedicated server, vanilla clients can still join without installing the mod and use all `/milestonelog` commands; installing the mod on the client unlocks the GUI
- Languages: English, Japanese (日本語)

## GUI & Commands

### GUI

Press **`M`** (configurable under *Controls → Item Milestone Log*) to open the Item Milestone Log screen. Everything available via commands can also be done from the GUI:

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

`config/itemmilestonelog-common.toml`:

- `defaultTargets` — item IDs every player tracks by default
- `scanIntervalTicks` — inventory scan interval in ticks (default 20 ticks = 1 s)
- `notifyPlayer` — chat notification on record (default true)
- `broadcastMilestones` — announce records to all players (default false)

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21

Built for long modpack playthroughs such as All the Mods 10, but works in any 1.21.1 NeoForge pack or vanilla-plus world.

## License

Custom License (All Rights Reserved) — see the [LICENSE](LICENSE) file.
Personal use and inclusion in free modpacks (with credit and a link back) are
allowed; redistribution, re-uploading, selling and distributing modified
versions require the author's permission.
