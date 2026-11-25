# Changelog

The section for a version is used as it is for the GitHub Release (created when a
`v<version>` tag is pushed) and for the CurseForge file changelog.

## 1.4.0

### Data & Compatibility
- **Saved-data schema version (N88)**: `MilestoneData` now has a root `schemaVersion` field (int, current value `1`, always written) in the 1.21.1 NBT, 26.x Codec, and 1.20.1 Forge editions. Data written up to 1.3.1 (no field) is read as version 0 and saved with version 1 on the next write; a value from a newer release is loaded best-effort and preserved on save instead of being downgraded. The field is not part of the JSON export/import format, and the automatic backups include it because they reuse the saved-data root tag.
- **Downgrading from 1.4.0 to 1.3.1**: 1.3.1 ignores `schemaVersion` and drops it on the next save. Targets, records, world-firsts, and the other saved fields are unaffected.

## 1.3.1

This is a bug-fix release for the 1.3.0 version-range jars, and the first release that includes the Minecraft 1.20.1 (Forge) edition. There are no gameplay changes.

### Fixes
- **1.21.5–1.21.8 jar crashes**: The merged jar for Minecraft 1.21.5–1.21.8 crashed on 1.21.6–1.21.8 when opening the milestone screens (text rendering) and when clicking in the item picker (sound), and on 1.21.7 when opening the GUI from the key (networking). The broken 1.3.0 file for 1.21.5–1.21.8 was withdrawn from the stores; this release replaces it.
- **1.21.4 list scrollbar**: On Minecraft 1.21.4, the list scrollbar in the three screens sat at the vanilla position instead of the intended one.

### Version Support
- **Minecraft 1.20.1 (Forge)**: The Minecraft 1.20.1 Forge edition joins the release from 1.3.1 (`itemmilestonelog-forge-1.20.1-1.3.1.jar`, requires Forge 47.3.0 or newer).

### Notes
- **Binary-linkage audit in CI**: Every merged jar is now checked in CI against every Minecraft version it covers, so a call that does not link on one of those versions fails the build instead of crashing in game.

## 1.3.0

This release is about Minecraft version support. There are no gameplay changes: every 1.2.0 feature is included unchanged.

### Version Support
- **Single jar per version range**: The download list shrinks from one jar per Minecraft version to one jar per version range. The 26.x series (26.1.2, 26.2, 26.3) now shares one jar, and the 1.21.x line is covered by four jars: 1.21–1.21.4, 1.21.5–1.21.8, 1.21.9–1.21.10, and 1.21.11.
- **New Minecraft versions supported**: 1.21, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, and 1.21.11 join the previously supported 1.21.1, so the whole 1.21.x line is now covered.

### Compatibility
- **Existing worlds and data**: Existing worlds and milestone data are fully compatible. The data format is shared across the whole 1.21.x line, so a world can move between 1.21.x versions with the matching jar.

## 1.2.0

### New Commands
- **Tag targets**: Added `/milestonelog addtag <tag> [<count>]`, `/milestonelog removetag <tag> [<count>]`, `/milestonelog resettag <tag> [<count>]`, `/milestonelog firsts resettag <tag> [<count>]`, `/milestonelog admin <player> resettag <tag> [<count>]`, and `/milestonelog toptag <tag> [<count>]`. Tags are written without the leading `#` in commands, but as `#namespace:path` in `defaultTargets`, presets, and exports; possession of all items in the tag is summed.
- **Item info**: Added `/milestonelog info <item> [<count>]` and `/milestonelog info hand` (player only). Shows your target/record status, the world-first holder and time, how many players recorded the item and your rank (when `allowLookup` permits it), and related tag targets.
- **Item ID renaming**: Added OP `/milestonelog renameitem <oldId> <newId> [confirm]` to move targets, records, `progressNotified`, `carriedFromCreative`, and world-firsts from an old item ID to a new registered item. Without `confirm` it only previews; it writes a backup first and never edits `defaultTargets` or preset files.
- **Discord webhook test target**: Added `/milestonelog discordtest worldfirst` to test the separate world-first webhook.

### Targets, Detection & Display
- **Item tag targets**: Tag targets work with quantity targets, progress alerts, world-firsts, presets, `defaultTargets`, command execution, scoreboard counts, and export/import. Names use NeoForge tag translations where available, falling back to `#namespace:path`; the GUI resolves the first item in the tag as its icon. Tag targets cannot be added from the GUI item picker, but can be viewed, removed, and reset there. 1.0.1/1.1.0 clients simply do not show tag entries in the GUI.
- **Chat item hover (enabled by default, `chatItemHover`)**: Item names in the mod's chat messages now show the item on hover (`show_item`). The text itself is unchanged.
- **Item tooltip status (enabled by default, `itemTooltipStatus`)**: On modded clients, item tooltips gain up to three gray lines showing whether the item (or one of its tag targets) is a target or already recorded, including playtime.
- **Localized playtime and Day formatting (default behavior change)**: Chat and GUI now format playtime and Day/date through the player's language (e.g. 「27時間03分05秒（5日目、2026-11-23）」). English output is unchanged. Discord, server logs, exports, and the API deliberately keep the English format.
- **GUI world-first mark (enabled by default, `guiWorldFirstMark`)**: Recorded rows show `✔ ★ …` when you hold the world first. The extra sync payload is only sent to 1.2.0+ clients; 1.0.1/1.1.0 clients keep the old display.
- **Scoreboard integration (F17)**: Optional `scoreboardObjective` and `scoreboardWorldFirstObjective` mirror each player's record count and world-first count to vanilla scoreboard objectives, updated on records, resets, imports, renames, and login.
- **Login world-first summary (F52)**: Optional `loginWorldFirstSummary` lists up to five world-firsts recorded while you were away, with a remaining count.
- **Hovered-item key (F41)**: A new, unbound-by-default key lets you add the item under the mouse cursor as a target from inventory/container screens.
- **Advancement trigger (F2)**: Datapacks can use the `itemmilestonelog:milestone_recorded` trigger with `player`, `item`, `count`, and `world_first` conditions. Existing records are replayed on login and `/reload`; resets do not revoke advancements.
- **`info` command (F51)**: See a single item's personal status, world first, recorder count/rank, and related tag targets without opening the GUI.
- **Milestone command execution (F7)**: Optional `milestoneCommand` runs a server command when a milestone is recorded, with `%player%`, `%uuid%`, `%item%`, `%count%`, `%playtime%`, and `%worldfirst%` placeholders; use `function <namespace>:<path>` for multiple commands.

### Data, Safety & Administration
- **Automatic world-data backups (enabled by default, `autoBackupEnabled`)**: `MilestoneData` is written to `<world>/itemmilestonelog/backups/milestones-YYYYMMDD-HHmmss.dat` on world save (at most once per hour) and on server stop, keeping `autoBackupGenerations` files (default 5). Backups can be restored manually by stopping the server and copying the chosen file back over the real data file.
- **Datapack presets (F21)**: Presets can be shipped in `data/<namespace>/itemmilestonelog/presets/<id>.json` and are read on `/reload`. Precedence is built-in > config folder > datapack; datapack presets are read-only and `preset reload` only re-reads the config folder.
- **Per-language preset names (F54)**: A preset's `name` may be an object such as `{"en_us": "Starter Kit", "ja_jp": "スターターキット"}`; each viewer gets their language, then `en_us`, then the first entry, then the preset ID.
- **Permission nodes (F43)**: NeoForge permission nodes can grant individual admin commands to non-OP staff (`itemmilestonelog.admin`, `firsts.reset`, `export.all`, `import.all`, `prunedata`, `renameitem`, `discordtest`, `presets.manage`, `viewhidden`). Nodes can add access but cannot take OP access away.
- **NeoForge config screen (F44)**: The mod list's **Config** screen now edits all settings except the two webhook URLs.
- **API tag support**: `TargetKey.isTag()` and tag-aware `ItemMilestoneLogApi` helpers; `getItemId()` can return a `#` tag ID, so check `isTag()` before parsing it as an item ID.

### Discord Improvements
- **Weekly digest (F9)**: Optional `discordDigestEnabled` sends a periodic summary of new records and world-firsts using `discordDigestIntervalDays` (default 7, range 1–30). The first run only records the current time; the digest does not resend a delivery that failed with HTTP 429 and follows `discordNotifyScope`.
- **Separate world-first webhook (F46)**: `discordWorldFirstWebhookUrl` sends world-first posts there only, while records, preset completions, and digests use `discordWebhookUrl`. Either URL can use `env:VAR_NAME`.
- **Silent record posts (F53)**: `discordSilentRecords` adds Discord's `SUPPRESS_NOTIFICATIONS` flag (`flags: 4096`) to normal record posts and grouped posts without a world first; world-first posts, preset completions, digests, and tests still notify.
- **World-first webhook test (F49)**: `/milestonelog discordtest worldfirst` verifies the second URL without printing it.

### Compatibility & Downgrade Notes
- Fully backward-compatible with 1.0.1 and 1.1.0 worlds and network-compatible with 1.0.1/1.1.0 clients and servers. Tag entries are invisible to 1.0.1/1.1.0 clients but do not disconnect them.
- **Downgrading from 1.2.0 to 1.1.0 or 1.0.1**: older versions do not read tag targets/records/world-firsts or the new `lastDigestEpoch` field, so tag-target data and `lastDigestEpoch` are dropped on the next save. Make a backup (or use the automatic 1.2.0 backups) before downgrading. Downgrading from 1.1.0 to 1.0.1 additionally drops the 1.1.0 per-player fields described below.
- New defaults worth noting: automatic backups, chat item hover, GUI world-first marks, item tooltip status, and localized playtime/Day formatting are on by default; the weekly digest, login summary, scoreboard objectives, silent Discord posts, and `milestoneCommand` are off until configured.


### Also included: all changes from 1.1.0
Version 1.1.0 was completed but never published on its own. This 1.2.0 release is the first public release after 1.0.1 and includes every 1.1.0 change listed below.

#### New Commands
- **Lookup, rankings & server stats**: Added `/milestonelog lookup <player> [<page>]`, `/milestonelog top [<item> [<count>]]`, `/milestonelog recent [<n>]` (1–20, default 10), and `/milestonelog stats` (controlled by `allowLookup`, default `true`; when `false`, restricted to OPs and the singleplayer/LAN host, while looking up yourself always works).
- **Privacy & notification opt-outs**: Added `/milestonelog privacy` to hide your milestones from non-privileged players in `lookup`, `top`, `recent`, and `stats`; `/milestonelog muteprogress` to mute quantity-target progress alerts; and `/milestonelog discordoptout` to exclude your milestones from Discord webhooks (`allowDiscordOptOut`, default `true`).
- **Preset progress, bulk target removal & undo**: Added `/milestonelog presetprogress <name>`, `/milestonelog presetremove <name>`, `/milestonelog removeall`, and `/milestonelog undo` (restores your last personal `reset`, `resetall`, GUI reset, `presetremove`, or `removeall` within 10 minutes).
- **Pagination, filtering & help**: Added optional `[<page>]` pagination (15 entries per page with clickable `[< Prev]` / `[Next >]` buttons) to `list`, `targets` (plus `targets pending` / `targets done`), `firsts`, `lookup`, `admin players`, and `admin <player> list|targets`; added `/milestonelog help [<subcommand>]` showing only commands available to the caller with clickable suggestions.
- **HTML & CSV exports, full import & world-first preservation**: Added `/milestonelog exporthtml` / `exporthtmlall` (standalone single-file HTML summary), `/milestonelog exportcsv` / `exportcsvall` (UTF-8 BOM, RFC 4180, formula-injection safe), and OP `/milestonelog importall <fileName>`. `exportall` now includes `worldFirsts`, which `importall` restores and merges (earlier known timestamp wins).
- **OP admin & maintenance commands**: Added `/milestonelog admin <player> add|remove|preset|grant` (`grant` records a milestone for an online player without checking inventory or creating world-firsts), `/milestonelog prunedata <days> [confirm]` (cleans up offline players with no records, personal targets, creative-carried items, or non-default preferences using tracked `lastSeen` timestamps), and `/milestonelog discordtest` (sends a test message and reports the HTTP result without exposing the webhook URL).

#### Notifications, Display & Configuration
- **Quantity-target progress alerts (enabled by default)**: Sends a personal chat alert when a quantity target crosses configured possession thresholds (`progressAlerts`, default `true`; `progressAlertThresholds`, default `[50, 90]`; `progressAlertMinCount`, default `10`). Uses a baseline on first evaluation so existing inventory does not spam alerts; opt out anytime with `/milestonelog muteprogress`.
- **Simultaneous notification grouping (default behavior change)**: Added `groupSimultaneousRecords` (default `true`). When 3 or more milestones are recorded for the same player in the same tick, chat messages, broadcasts, world-first announcements, and Discord posts are grouped into a single message per channel (sound plays once per tick). Set to `false` to restore 1.0.1 per-record messages.
- **Creative/spectator carry notices (enabled by default)**: When `ignoreCreativeAndSpectator` is enabled, leaving Creative or Spectator mode while holding untracked target items now notifies you in chat, marks affected entries in `/milestonelog targets` with `(carried from creative)`, and shows a paused-recording notice in `/milestonelog`.
- **Current player names in `/milestonelog firsts` (enabled by default)**: World-first entries now display each holder's current known name, formatted as `<current name> (was <old name>)` after a rename. Stored world-first records, exports, and `WorldFirstRecord.playerName` keep the original name; rename history in `firsts` is not covered by `/milestonelog privacy`.
- **Preset completion & starter presets**: Added preset-completion detection and announcements (`announcePresetCompletion`, default `false`) and `firstJoinPresets` (default empty) to automatically grant removable personal targets to new players on first join.
- **Display timezone & feature switches**: Added `displayTimeZone` (default `""` = server default) for server-formatted dates in chat, logs, Discord, and exports (client GUI continues to use the player's local timezone), plus top-level switches `presetsEnabled`, `exportImportEnabled`, and `worldFirstEnabled` (all default `true`).

#### Discord Improvements
- **Rich embeds & language**: Added `discordUseEmbeds` (default `false`) with Markdown escaping and Discord field length clamping, `discordLanguage` (default `"en_us"`, supports `"ja_jp"`), and `discordNotifyPresetCompletion` (default `false`).
- **Secret-safe `env:` URLs**: `discordWebhookUrl` now accepts `env:VAR_NAME` to read the URL from an environment variable; logs a one-time reminder in singleplayer/LAN that the config file is shared across worlds.
- **Delivery resilience**: Retries HTTP 429 once when `Retry-After` is 5 seconds or less, and pauses delivery after HTTP 401/404 with a single warning until the resolved webhook URL changes or `/milestonelog discordtest` succeeds.

#### Public Server API
- **Events**: Added `PresetCompletedEvent`, `MilestoneRecordedEvent#isWorldFirst()`, and `MilestoneRecordedEvent#getCause()` with `RecordCause` (`GAMEPLAY`, `API`, `ADMIN_GRANT`).
- **Write helpers**: Added server-thread `ItemMilestoneLogApi.addTarget`, `removeTarget`, and `recordNow` (with nesting depth limit 16 and automatic GUI re-sync).

#### Fixes & Internal Improvements
- **LAN preset permissions**: Fixed LAN guests without cheats being able to save, delete, or reload the host's presets by unifying privilege checks across commands and GUI.
- **Preset overwrite safety**: Overwriting a custom preset now preserves its JSON `name` display field instead of replacing it with the ID; the GUI Save Preset screen now indicates when saving will overwrite an existing custom preset and disables saving over built-in preset IDs.
- **Pickup fast-path & translation tooling**: Skipped unnecessary inventory counting on item pickups that do not match untracked targets or creative-carried items; simplified adding community translations via `ServerLang.BUNDLED_LANGUAGES` with automated test coverage.

#### Compatibility & Downgrade Notes
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
