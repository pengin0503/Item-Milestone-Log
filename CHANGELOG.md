# Changelog

The section for a version is used as it is for the GitHub Release (created when a
`v<version>` tag is pushed) and for the CurseForge file changelog.

## 1.0.1

- **Quantity-based milestone targets**: Targets now support an optional quantity (1–1,000,000), allowing separate milestones for the same item (e.g. `Diamond` ×1 and `Diamond ×100`). Detection uses simultaneous inventory possession (main inventory, offhand, and cursor stack; excluding Ender Chests, Shulker Boxes, and external storage). Supported across commands (`[<count>]`), the Item Picker GUI, custom presets (`{"item": "...", "count": N}`), `defaultTargets` (`item#count`), world-firsts, notifications/Discord, JSON export/import, and the server API, with full backward compatibility for 1.0.0/1.0.1 saves.
- **In-game Day and real-world date in records**: Milestones now store the overworld day and wall-clock timestamp alongside cumulative playtime, shown as `(Day N, yyyy-MM-dd)` in `/milestonelog list`, `/milestonelog firsts`, and the GUI. Fully backward-compatible with 1.0.0 save files.
- **Custom presets**: Define custom presets as JSON files in `config/itemmilestonelog/presets/` or save/delete your current personal targets via `/milestonelog preset save|delete|list|reload` and the new **Presets...** screen in the GUI (OP level 2 on dedicated servers, always allowed in singleplayer).
- **World-first records**: Tracks the first player to obtain each item in the world (`/milestonelog firsts`, OP `firsts reset|resetall`, and optional `announceWorldFirst` broadcast).
- **Obtainment sound effect**: Plays a configurable vanilla sound (`milestoneSoundEnabled`, `milestoneSound`) to the achieving player on milestone completion, independent of chat notifications.
- **Discord Webhook notifications**: Asynchronous webhook posting (`discordWebhookUrl`, `discordNotifyScope` = `all` or `world_first`).
- **JSON Export / Import**: Back up or share targets and records with `/milestonelog export`, `/milestonelog import <fileName>`, and OP `/milestonelog exportall`.
- **Public Server API**: Added `MilestoneRecordedEvent` and `ItemMilestoneLogApi` under `com.pengin0503.itemmilestonelog.api` for NeoForge mods and KubeJS scripts.
- **OP Admin commands**: Added `/milestonelog admin players` and `/milestonelog admin <player> list|targets|reset|resetall|purge` (supports offline players).
- **First-join tip & broadcast opt-out**: Added a one-time join tip (`showFirstJoinHint`) tailored for modded vs. vanilla clients, and `/milestonelog mutebroadcast` to opt out of other players' broadcasts.
- **Per-player vanilla language fallback**: Vanilla clients now receive chat fallback messages in their configured client language (`en_us` / `ja_jp`, defaulting to English for others).
- **Inventory scan & networking polish**: Improved player scan phase distribution across `scanIntervalTicks`, set `displayTest="IGNORE_ALL_VERSION"`, and enabled the NeoForge update checker (`docs/updates.json`).

## 1.0.0

First release.

- Records your cumulative playtime (Time Played statistic) the first time each of your target items enters your inventory, and notifies you in chat.
- Per-player targets, server-wide default targets (config), presets: atm_star, vanilla_endgame.
- In-game GUI when the mod is also installed on the client. It has no default key: assign one under Controls → Item Milestone Log. Vanilla clients can join a server with the mod and use all /milestonelog (/iml) commands.
- Nothing is recorded in creative or spectator mode, and items you still carry when switching back to survival are only recorded once they have left your inventory and you obtain them again (config: ignoreCreativeAndSpectator, on by default).
- Minecraft 26.x versions bring over the records of a world played with the 1.21.1 version.
- English and Japanese.
