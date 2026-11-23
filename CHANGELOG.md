# Changelog

The section for a version is used as it is for the GitHub Release (created when a
`v<version>` tag is pushed) and for the CurseForge file changelog.

## 1.1.0

### New Commands
- **Lookup, rankings & server stats**: Added `/milestonelog lookup <player> [<page>]`, `/milestonelog top [<item> [<count>]]`, `/milestonelog recent [<n>]` (1–20, default 10), and `/milestonelog stats` (controlled by `allowLookup`, default `true`; when `false`, restricted to OPs and the singleplayer/LAN host, while looking up yourself always works).
- **Privacy & notification opt-outs**: Added `/milestonelog privacy` to hide your milestones from non-privileged players in `lookup`, `top`, `recent`, and `stats`; `/milestonelog muteprogress` to mute quantity-target progress alerts; and `/milestonelog discordoptout` to exclude your milestones from Discord webhooks (`allowDiscordOptOut`, default `true`).
- **Preset progress, bulk target removal & undo**: Added `/milestonelog presetprogress <name>`, `/milestonelog presetremove <name>`, `/milestonelog removeall`, and `/milestonelog undo` (restores your last personal `reset`, `resetall`, GUI reset, `presetremove`, or `removeall` within 10 minutes).
- **Pagination, filtering & help**: Added optional `[<page>]` pagination (15 entries per page with clickable `[< Prev]` / `[Next >]` buttons) to `list`, `targets` (plus `targets pending` / `targets done`), `firsts`, `lookup`, `admin players`, and `admin <player> list|targets`; added `/milestonelog help [<subcommand>]` showing only commands available to the caller with clickable suggestions.
- **HTML & CSV exports, full import & world-first preservation**: Added `/milestonelog exporthtml` / `exporthtmlall` (standalone single-file HTML summary), `/milestonelog exportcsv` / `exportcsvall` (UTF-8 BOM, RFC 4180, formula-injection safe), and OP `/milestonelog importall <fileName>`. `exportall` now includes `worldFirsts`, which `importall` restores and merges (earlier known timestamp wins).
- **OP admin & maintenance commands**: Added `/milestonelog admin <player> add|remove|preset|grant` (`grant` records a milestone for an online player without checking inventory or creating world-firsts), `/milestonelog prunedata <days> [confirm]` (cleans up offline players with no records, personal targets, creative-carried items, or non-default preferences using tracked `lastSeen` timestamps), and `/milestonelog discordtest` (sends a test message and reports the HTTP result without exposing the webhook URL).

### Notifications, Display & Configuration
- **Quantity-target progress alerts (enabled by default)**: Sends a personal chat alert when a quantity target crosses configured possession thresholds (`progressAlerts`, default `true`; `progressAlertThresholds`, default `[50, 90]`; `progressAlertMinCount`, default `10`). Uses a baseline on first evaluation so existing inventory does not spam alerts; opt out anytime with `/milestonelog muteprogress`.
- **Simultaneous notification grouping (default behavior change)**: Added `groupSimultaneousRecords` (default `true`). When 3 or more milestones are recorded for the same player in the same tick, chat messages, broadcasts, world-first announcements, and Discord posts are grouped into a single message per channel (sound plays once per tick). Set to `false` to restore 1.0.1 per-record messages.
- **Creative/spectator carry notices (enabled by default)**: When `ignoreCreativeAndSpectator` is enabled, leaving Creative or Spectator mode while holding untracked target items now notifies you in chat, marks affected entries in `/milestonelog targets` with `(carried from creative)`, and shows a paused-recording notice in `/milestonelog`.
- **Current player names in `/milestonelog firsts` (enabled by default)**: World-first entries now display each holder's current known name, formatted as `<current name> (was <old name>)` after a rename. Stored world-first records, exports, and `WorldFirstRecord.playerName` keep the original name; rename history in `firsts` is not covered by `/milestonelog privacy`.
- **Preset completion & starter presets**: Added preset-completion detection and announcements (`announcePresetCompletion`, default `false`) and `firstJoinPresets` (default empty) to automatically grant removable personal targets to new players on first join.
- **Display timezone & feature switches**: Added `displayTimeZone` (default `""` = server default) for server-formatted dates in chat, logs, Discord, and exports (client GUI continues to use the player's local timezone), plus top-level switches `presetsEnabled`, `exportImportEnabled`, and `worldFirstEnabled` (all default `true`).

### Discord Improvements
- **Rich embeds & language**: Added `discordUseEmbeds` (default `false`) with Markdown escaping and Discord field length clamping, `discordLanguage` (default `"en_us"`, supports `"ja_jp"`), and `discordNotifyPresetCompletion` (default `false`).
- **Secret-safe `env:` URLs**: `discordWebhookUrl` now accepts `env:VAR_NAME` to read the URL from an environment variable; logs a one-time reminder in singleplayer/LAN that the config file is shared across worlds.
- **Delivery resilience**: Retries HTTP 429 once when `Retry-After` is 5 seconds or less, and pauses delivery after HTTP 401/404 with a single warning until the resolved webhook URL changes or `/milestonelog discordtest` succeeds.

### Public Server API
- **Events**: Added `PresetCompletedEvent`, `MilestoneRecordedEvent#isWorldFirst()`, and `MilestoneRecordedEvent#getCause()` with `RecordCause` (`GAMEPLAY`, `API`, `ADMIN_GRANT`).
- **Write helpers**: Added server-thread `ItemMilestoneLogApi.addTarget`, `removeTarget`, and `recordNow` (with nesting depth limit 16 and automatic GUI re-sync).

### Fixes & Internal Improvements
- **LAN preset permissions**: Fixed LAN guests without cheats being able to save, delete, or reload the host's presets by unifying privilege checks across commands and GUI.
- **Preset overwrite safety**: Overwriting a custom preset now preserves its JSON `name` display field instead of replacing it with the ID; the GUI Save Preset screen now indicates when saving will overwrite an existing custom preset and disables saving over built-in preset IDs.
- **Pickup fast-path & translation tooling**: Skipped unnecessary inventory counting on item pickups that do not match untracked targets or creative-carried items; simplified adding community translations via `ServerLang.BUNDLED_LANGUAGES` with automated test coverage.

### Compatibility & Downgrade Notes
- Fully backward-compatible with 1.0.0 and 1.0.1 worlds (including 1.21.1 → 26.x migration) and network-compatible with 1.0.1 clients/servers.
- **Downgrading from 1.1.0 to 1.0.1**: 1.0.1 ignores the new per-player fields (`progressNotified`, `completedPresets`, `hideFromLookup`, `muteProgressAlerts`, `lastSeen`, `excludeFromDiscord`) and drops them on the next save. Personal privacy/mute/Discord opt-out settings will reset, and progress or preset-completion notifications may fire again if you later return to 1.1.0.

## 1.0.1

- **Quantity-based milestone targets**: Targets now support an optional quantity (1–1,000,000), allowing separate milestones for the same item (e.g. `Diamond` ×1 and `Diamond ×100`). Detection uses simultaneous inventory possession (main inventory, offhand, and cursor stack; excluding Ender Chests, Shulker Boxes, and external storage), and unachieved `count > 1` targets display current possession progress (e.g. `(current: 37)` / `(現在 37)`) in both `/milestonelog targets` and the GUI. Supported across commands (`[<count>]`), the Item Picker GUI, custom presets (`{"item": "...", "count": N}`), `defaultTargets` (`item#count`), world-firsts, notifications/Discord, JSON export/import, and the server API, with full backward compatibility for 1.0.0/1.0.1 saves.
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
