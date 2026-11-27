# Changelog

The section for a version is used as it is for the GitHub Release (created when a
`v<version>` tag is pushed) and for the CurseForge file changelog.

## 1.5.0

### Permissions
- **Permission node subdivision (N89)**: Seven boolean permission nodes (`doctor`, `debug`, `discordstatus`, `discorddigest`, `backup.manage`, `backup.view`, `audit.view`) are registered alongside the existing nine, with human-readable names and descriptions in English and Japanese. The existing nodes, checks and command permissions are unchanged: a node can only add access, and console / RCON / command blocks / functions still use the vanilla permission level. Registering the nodes does not by itself change command behaviour.

### Diagnostics
- **One-shot doctor summary (N47)**: `/milestonelog doctor` prints a read-only, at most 20-line summary of the world/server, saved-data counts, backup directory, Discord configuration state and the I/O queue. It reports counts and status names only: no player names, UUIDs or webhook URLs, and no automatic repair, save or schema change. Console and players can run it: players are granted by the legacy permission or the new `doctor` node, while console / RCON / command blocks / functions use only the vanilla permission level.
- **Runtime statistics dump (N147)**: `/milestonelog admin debug` adds a read-only runtime dump of at most 20 lines beside a condensed `doctor` core: current thread, pending notification/Discord-send counts, dropped posts, dead-webhook and Discord-thread state, memory usage, and per-kind I/O queue depth. It shows numbers and status names only — no raw exceptions, stack traces, player names or webhook URLs. Optional APIs (average TPS) fall back to a fixed "unavailable" wording instead of crashing on any version. Non-player sources can run it through the vanilla permission level. Read-only snapshot getters were added to the existing notification and Discord send queues; no tasks are submitted and no data is dirtied.

### Discord diagnostics
- **Payload preview (N35)**: `/milestonelog discordtest payload` and `/milestonelog discordtest worldfirst payload` print the exact JSON those test messages would send, without sending or queueing anything. The preview reuses the existing payload builder (including its truncation rules), passes no timestamp override, fits on one line and never exposes the webhook URL. The existing `discordtest` executions and result messages are unchanged.
- **Status and manual digest (N32)**: `/milestonelog discordstatus` prints a read-only summary of at most 12 lines: webhook resolution status (or the environment variable name only), embed mode, notification scope, digest configuration, last/next digest epoch and the pending-send count; no resolved URL is shown. `/milestonelog discordstatus digest` runs the existing digest path once, ignoring only the enabled/interval gate; opt-out, scope, the main webhook and the periodic throttler are unchanged. It advances `lastDigestEpoch` after the attempt (including when no new entries are found, so the next scheduled digest does not resend them), but not when the webhook is unconfigured. The two subcommands use separate permission nodes, and neither reveals a webhook URL.

### Backup operations
- **On-demand backups and read-only inspection (N39)**: `/milestonelog backup now` writes one backup immediately through the existing synchronous writer (ignoring `autoBackupEnabled` and the hourly rate limit, but refused while the server is stopping so the stop drain is not extended); `backup list [page <page>]` lists at most 15 matching backups per page with size and timestamp, newest first; `backup verify [fileName]` checks the newest matching backup when no name is given, decompresses one backup and checks its schema and player/world-first counts without restoring it. A supplied name must match the backup naming pattern and stay directly inside the backup directory. Verification never reads or dirties live saved data, never prunes, and reports only six fixed outcomes with no exception details. `backup.manage` gates `now`, `backup.view` gates `list` and `verify`, and the existing nine permission nodes are unchanged.

### Audit log
- **Structured administrator audit log (N90)**: Successful administrative changes append one UTF-8 JSONL line to `<world>/itemmilestonelog/audit.log` — target add/remove/reset/purge/copy-from, firsts resets, `prunedata`, item/tag renames, imports, preset mutations and `backup now`. A manual Discord digest is recorded when an attempt is made (including an empty one, which writes `detail.success = false`); failed, previewed or read-only operations and background events are not recorded. The file rotates at 1 MiB into five numbered generations (current plus five). Each record has a record version, UTC timestamp, fixed upper-case operation name, player UUID (or `#nonplayer` for console/RCON/command blocks/functions), target, a small detail object and `result: success` (the result field is always `success`; the digest's send outcome is in `detail.success`); no webhook URLs, environment values, exception messages or player display names are written. Writes are synchronous on the server thread and a write/rotation failure only warns in the server log — the operation still succeeds and is not retried. The five existing attribution INFO log lines are unchanged, and no in-game audit viewer is shipped (`audit.view` is reserved for a later release).

## 1.4.0

This release adds saved-data versioning and recovery, extends presets with composition and automatic add-ons, and adds new inspection, sharing and administration commands, alongside a broad wave of fixes from review and pre-release audits. Every supported Minecraft edition ships with the same version number and feature set.

### Data & Compatibility
- **Saved-data schema version (N88)**: `MilestoneData` now has a root `schemaVersion` field (int, current value `1`, always written) in the 1.21.1 NBT, 26.x Codec, and 1.20.1 Forge editions. Data written up to 1.3.1 (no field) is read as version 0 and saved with version 1 on the next write; a value from a newer release is loaded best-effort and preserved on save instead of being downgraded. The field is not part of the JSON export/import format, and the automatic backups include it because they reuse the saved-data root tag.
- **Downgrading from 1.4.0 to 1.3.1**: 1.3.1 ignores `schemaVersion` and drops it on the next save. Targets, records, world-firsts, and the other saved fields are unaffected.
- **Tolerant loading and skip accounting (N141)**: Loading milestone data now counts each skipped element and any whole-file decode failure, on both the NBT and 26.x Codec paths. A bad individual field no longer discards its valid siblings: readable records are kept and the skip count feeds the evacuation step below. Nothing is silently dropped.
- **Evacuation of unreadable saved data (N141)**: At server start, if the load skipped records or failed entirely (including an unreadable or wrongly shaped root container), the original saved-data file is copied to `<world>/itemmilestonelog/corrupt/` before the next normal save can overwrite it. A companion text file records only the time, skip count, failure flag and source `schemaVersion` — no player names or records. The newest five copies are kept, the original file is never removed, a clean load does nothing, and a failed copy only logs a warning.
- **Asynchronous imports and backups (N45/N149)**: `importall` file reading, JSON parsing and merge calculation, and the disk writes for automatic backups, now run on a dedicated single-threaded I/O executor instead of blocking the server thread. `importall <fileName>` keeps its syntax and permission but replies "started" first and then the result; a second import while one is running is rejected, and work falls back to synchronous I/O if the executor is unavailable. The backup taken before `renameitem`/`renametag` stays synchronous. On server stop, new tasks are refused and queued work is drained for up to 30 seconds before pending imports, reward commands and the final backup are completed; on timeout, unfinished tasks are interrupted and logged while the final backup is written synchronously. Gameplay recording is unchanged, and this feature adds no config option.

### Presets
- **Extended preset definitions (G-B)**: Preset JSON now accepts `includes`, `description`, `category`, `order`, `version`, `requiredMods`, `groups`, `nextPresets` and `unlockAfterHours` alongside `name` and `items`. `includes` recursively prepends other presets' items in listed order with duplicates removed; cycles and unknown IDs are warned about and skipped. Preset lists are sorted by `order` then ID, `description` supports the same per-language object as `name`, and `requiredMods` skips an entire apply and names the missing mods. Groups are validated against the expanded items but do not change completion. Saving a preset from the GUI preserves these hand-written fields; there is no new editing UI.
- **Preset chains and playtime unlocks (G-C)**: A per-player preset state is now saved with the milestone data. When a player newly completes a preset, the targets of its `nextPresets` are added automatically using the normal apply rules; already-completed follow-ups do not fire, so chains stop. A preset with `unlockAfterHours` is added once its cumulative playtime threshold is reached, and is not re-added if the player later removes those targets. Missing `requiredMods` skip the addition and are reconsidered on a later login; each addition is reported in chat. All three flows are disabled when `presetsEnabled` is false.
- **Preset update notices**: When a preset the player is involved with gains a higher `version`, a one-time login message names the preset, the number of newly added targets and the current total; targets are never changed automatically and removed targets are not counted. Preset state is cleared by `resetall`.

### New Commands
- **Preset preview and diff**: `/milestonelog preset show <name>` prints a preset's display name, ID and source kind, its metadata (description, category, version, order, required mods, unlock time and next presets), group sizes and its expanded item list (`ID ×count`), showing the first 50 items and a count of the rest. `/milestonelog preset diff <nameA> <nameB>` lists items found only in A, only in B, and items whose counts differ (`ID: A=×n / B=×m`), or reports "No differences." Both commands are read-only, need no extra permission, work from console/RCON, and are disabled when `presetsEnabled` is false.
- **Preset share codes**: `/milestonelog preset share <name>` prints a click-to-copy `IML1:` + Base64URL code containing the preset's ID, metadata and expanded items. `/milestonelog preset importcode <code>` validates the code and saves it as a config-folder custom preset; it needs the same permission as `preset save` (OP level 2 or `presets.manage` on dedicated servers). Codes may contain at most 512 items and 16,384 characters. A bad prefix, corrupt Base64/JSON, zero items, an invalid ID or a collision with an existing preset is rejected without saving anything; IDs are never auto-renamed.
- **Tag target bulk rename**: Admin `/milestonelog renametag <oldTag> <newTag> [confirm]` (same permission as `renameitem`) previews the affected players, targets, records and world-firsts. With `confirm` it writes a backup first, then moves the old `#namespace:path` key to the new one in targets, records, notification thresholds and world-firsts using `renameitem`'s collision rules, so completed targets and world-firsts do not fire again. Equal IDs are rejected, unregistered tags are renamed with a warning, and presets and config files are never rewritten.
- **Strict preset validation and JSON Schema**: The new `strictPresetValidation` config option (default `false`) rejects a config-folder or datapack preset as a whole when any element is invalid, an `includes` reference is missing or cyclic, or a group fails validation; the preset is then treated as unknown instead of being partly loaded. Built-in presets are never rejected, and the default lenient behaviour still warns and skips bad elements. A JSON Schema covering all custom-preset fields is available at `docs/preset-schema.json` and referenced from the custom-preset documentation; it is not bundled into the jars.

### Fixes
- **Saved data and presets (review fixes)**: Worlds written with an extra `data` wrapper by affected 1.21.5–1.21.11 builds are readable again; 26.x partial decodes now produce a load report so damaged data is evacuated rather than overwritten, and lenient field decoding counts every skipped element (#99, #110, #130). Field-level loading was brought to parity across editions (#147, #148). Preset share codes, `importcode` and `renametag` migration were repaired (#97, #98, #128, #129, #146); admin `resetall` now clears `presetState` like a player's own reset and `undo` restores it (#108, #132); and `includes` expansion no longer blows up on diamond or cyclic graphs (#102, #149).
- **Asynchronous I/O and shutdown (review fixes)**: The final backup no longer duplicates after stop, I/O threads no longer touch command sources, queue state checks are synchronized, and queued reward commands and notifications are flushed before shutdown (#106, #107, #109, #133, #135); reward commands run before the final backup so their records are included (#150). Discord webhook URLs without a usable host are rejected, dropped posts are counted, and in-flight HTTP calls are cancelled before the blocking close on Java 21+ editions (#111, #112). A concurrency review also removed an unbounded stop-time backup lock and guarded the parse/drain hand-off.
- **Commands, GUI and API (review fixes)**: `importcode` accepts a full share code and works from console/RCON (#97); Forge 1.20.1 list heights and backgrounds, tooltip indexes after tag updates, the mouse-bound hovered-item key, per-frame GUI allocations, Forge criterion registration and version detection were corrected (#100, #101, #115–#118, #125, #151, #154, #155). Read APIs normalize the implicit `minecraft:` namespace (#114), and per-player exports use full UUID suffixes so sanitized names cannot overwrite each other (#137).
- **Tooling and tests (review fixes)**: Release/publish validation and CI were hardened, including a dedicated Forge 1.20.1 server job (#103–#105, #119–#121, #126, #131, #134, #136), and remaining test gaps and defective tests were closed (#122, #123).
- **Pre-release audit — data preservation and recap**: In the 1.21.1 NBT and 1.20.1 Forge NBT editions, a wrong-typed player entry, `targets` list or `records` compound is now detected and counted as a skipped element, so the original file is evacuated instead of being overwritten with empty data while valid players and unrelated fields still load (RB-1). Preset files reject extreme nesting, accept integral decimals for numeric metadata, keep previously loaded presets when the preset directory fails, and no longer report success for a save that strict mode will reject on reload (SF-4–SF-7, SF-11–SF-14). `recap return` now uses the `lastSeen` value captured before login overwrites it, so world firsts earned while away are listed again; long `recap` output shows how many rows were omitted (RB-2, R2-MN-1).
- **Pre-release audit — shutdown, Discord and minors**: A completed `importall` is no longer applied from the I/O thread during server stop; it is held for the server-thread hand-off with rollback on failure, so a half-applied dataset cannot be saved (RB-3). Stop-time Discord posts are counted and given a bounded delivery window, and `discordtest` reports an explicit "abandoned" result instead of silently disappearing (SF-10, MN-3, R2-SF-2). Read-only `settings`/`recap` and no-op `settings <toggle> off` no longer create or dirty player data (SF-9, R2-SF-1); `whohas` ranks and footer links render correctly, `info … preview` shows untracked tags without duplicate sections, and playtime unlocks survive the vanilla playtime counter wrap (SF-1–SF-3, SF-8, MN-1, MN-2).

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
