# CurseForge project page (paste-ready)

This file holds the text and settings for the CurseForge project and its files.
Sections marked *(paste)* go into CurseForge as they are.

## Release checklist

- [ ] CI is green on `main` (all jobs of the **Build** workflow).
- [ ] `docs/updates.json` is updated with the new version's entry and `promos` (`<mc>-latest` and `<mc>-recommended`).
- [ ] The six jars come from the GitHub Release of the version tag, or from that
      CI run (see [Getting the jars](#getting-the-jars)).
- [ ] Quick manual check in a client with the mod installed (not covered by CI):
      after assigning a key to *Open Milestone Log* under Controls, the key opens the
      screen, targets can be added from the item picker, presets (including the Presets
      list screen) and resets work, and connecting to a server without the mod shows
      no mismatch warning (`displayTest="IGNORE_ALL_VERSION"`).
- [ ] Optional but recommended for the ATM10 audience: run `/milestonelog preset atm_star`
      in ATM10 and check that it reports `0 skipped` (issue #15).
- [ ] 1.2.0 client check: tags appear with their display names/icons, chat item hover works,
      item tooltips show target/recorded lines, the mod list's **Config** screen opens without
      the webhook URLs, the hovered-item key works in a chest, and ★ appears where expected.

## Project settings

| Field | Value |
|---|---|
| Project name | Item Milestone Log |
| Project type | Mods |
| Summary *(paste)* | Records the cumulative playtime at which you first obtain each of your own goal items — perfect for ATM Star hunts in ATM10. |
| Categories (up to 3) | Utility & QoL / Server Utility / Adventure and RPG |
| Avatar | `common/resources/logo.png` (512×512 PNG, the same image the mod shows in the in-game mod list) |
| License | **Custom License** — paste the text of the repository's `LICENSE` file |
| Source / Issues links | `https://github.com/pengin0503/Item-Milestone-Log` and `…/issues` |

**Third-party distribution.** The project settings have a toggle for letting other
launchers and modpack tools (outside the CurseForge app) download the file for their
users — labelled *Allow Mod Distribution* at the time of writing; check the current
UI. The license allows inclusion in free modpacks, so turning it on matches the
license. Modpacks on CurseForge itself can include the mod either way.

## Files

Upload one file per version range. Each jar supports every game version in its row,
so tag each file with all of those game versions (not just the first one).

| File | Display name | Game versions | Mod loader | Java | Environment |
|---|---|---|---|---|---|
| `itemmilestonelog-neoforge-26.x-1.3.1.jar` | Item Milestone Log 1.3.1 (MC 26.1.2–26.3) | 26.1.2, 26.2, 26.3 | NeoForge | Java 25 | Client, Server |
| `itemmilestonelog-neoforge-1.21-1.21.4-1.3.1.jar` | Item Milestone Log 1.3.1 (MC 1.21–1.21.4) | 1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4 | NeoForge | Java 21 | Client, Server |
| `itemmilestonelog-neoforge-1.21.5-1.21.8-1.3.1.jar` | Item Milestone Log 1.3.1 (MC 1.21.5–1.21.8) | 1.21.5, 1.21.6, 1.21.7, 1.21.8 | NeoForge | Java 21 | Client, Server |
| `itemmilestonelog-neoforge-1.21.9-1.21.10-1.3.1.jar` | Item Milestone Log 1.3.1 (MC 1.21.9–1.21.10) | 1.21.9, 1.21.10 | NeoForge | Java 21 | Client, Server |
| `itemmilestonelog-neoforge-1.21.11-1.3.1.jar` | Item Milestone Log 1.3.1 (MC 1.21.11) | 1.21.11 | NeoForge | Java 21 | Client, Server |
| `itemmilestonelog-forge-1.20.1-1.3.1.jar` | Item Milestone Log 1.3.1 (MC 1.20.1) | 1.20.1 | Forge | Java 17 | Client, Server |

- Release type: **Release**.
- Related projects: none (the mod only needs NeoForge, or Forge for 1.20.1).
- Environment: the mod is needed on the server (or in singleplayer); on a dedicated
  server the client side is optional and only adds the GUI. If CurseForge offers only
  one choice, pick the setting that marks it as working on both client and server.
- If CurseForge does not list one of the 26.x game versions yet, upload that file
  once the version is available rather than tagging it with a different version.

### Changelog *(paste)*

Paste the section of the version from [CHANGELOG.md](CHANGELOG.md). The GitHub
Release of the version has the same text.

## Getting the jars

From a release (recommended): push the tag `v<version>` on `main` (see *Releasing* in
the README). When the **Build** workflow has passed, the GitHub Release
`v<version>` has the six jars listed in `publish-manifest.json` attached. They do not
expire.

Without a tag:

1. Open the **Actions** tab on GitHub → workflow **Build** → the latest successful run on `main`.
2. Under **Artifacts**, download `itemmilestonelog-26.x`, `itemmilestonelog-1.21-a`,
   `itemmilestonelog-1.21-b1`, `itemmilestonelog-1.21-b2`, `itemmilestonelog-1.21-c`
   and `itemmilestonelog-1.20.1-forge`. Each zip holds one jar (the NeoForge jars are
   built against the minimum NeoForge of their range and tested on the minimum and
   the latest; the Forge jar is tested on Forge 47.3.0 and 47.4.29).
3. Artifacts expire after 90 days. To rebuild locally instead:
   `./gradlew -p versions/<mc_version> build` → `versions/<mc_version>/build/libs/`.

## Description *(paste, English first as required by moderation)*

### Item Milestone Log

**How many hours into your world did you finally make that item?**

Item Milestone Log records the **cumulative playtime** (the vanilla *Time Played* statistic), **in-game Day**, and **real-world date** at the moment you **first obtain** each of your personal goal items, and tells you right away in chat:

> ★ Milestone recorded: ATM Star at playtime 87h 42m 10s

It was built for long modpack goals — above all the **ATM Star in All the Mods 10** — where a single endgame item takes dozens of hours and a shelf of automation to produce, and you want the receipt for exactly when each component first landed in your hands.

#### Why playtime, not world time?
The record uses your personal *Time Played* statistic, not the world clock. Sleeping through nights does not inflate it, and it is *your* time in the world, not the server's. Alongside playtime, the in-game Day and real-world date are saved too.

#### Why "first obtained", not "crafted"?
Detection is possession-based. Items produced by machines, automation, AE2 autocrafting, smelting, or dropped by mobs are recorded the first time they enter your inventory — not only items you craft by hand.
(Note: items placed directly into an Ender Chest, inside a Shulker Box, or into another mod's storage are recorded once you take them out into your player inventory.)

#### Features
- **Per-player targets with optional quantities (1–1,000,000)** — track e.g. `Diamond` ×1 and `Diamond ×100` as separate milestones based on simultaneous inventory possession, with configurable progress alerts (default 50% / 90% for quantities ≥ 10; opt out with `/milestonelog muteprogress`)
- **Item tag targets** — track a whole tag such as `#c:ingots/iron` or `#c:ingots/iron#100`; all items in the tag are summed. Tags work in `defaultTargets`, presets, imports/exports, progress, world-firsts, and the `addtag` / `removetag` / `resettag` / `toptag` commands
- **Cumulative playtime, Day & date records**, stored per world, shown as e.g. `27h 15m 30s (Day 12, 2026-11-22)`, with configurable server display timezone (`displayTimeZone`). Chat and GUI use the player's language for playtime/Day; Discord, logs, and exports stay in English
- **World-first records** — tracks the first player to obtain each item (and quantity) in the world (`/milestonelog firsts`, showing current usernames with `(was <old name>)` after renames, with optional server-wide announcements)
- **Login summary, lookup & item info** — optionally list world-firsts recorded while you were away (`loginWorldFirstSummary`), browse other players' records (`/milestonelog lookup`), leaderboards (`/milestonelog top`, `toptag`, `/milestonelog recent`), server statistics (`/milestonelog stats`), or inspect one item with `/milestonelog info <item>`, with personal privacy opt-out (`/milestonelog privacy`)
- **In-game GUI** — view progress, search and add targets (with quantity input), add the item under the mouse with an optional **Add Hovered Item to Targets** key (assigned under Controls), browse/apply/save custom and datapack presets (with overwrite warning), and manage records without typing commands. Assign a key to *Open Milestone Log* under Options → Controls → Key Binds first: there is no default key, because `M` already opens the FTB Chunks map in ATM10. Item tooltips can show target/recorded status, and a ★ marks records where you hold the world first. (Requires the mod on the client; vanilla clients can still join and use commands.)
- **NeoForge config screen** — edit all settings from the mod list's **Config** button except the webhook URLs, which are hidden so they cannot leak in screenshots
- **Chat notification, grouping & sound effect** — instant notifications with configurable sound, optional `show_item` hover on item names in chat, automatic grouping when 3+ milestones trigger in the same tick (`groupSimultaneousRecords`), optional server-wide & preset-completion broadcasts, and `/milestonelog mutebroadcast` opt-out
- **Advancement trigger** — datapacks can grant advancements with `itemmilestonelog:milestone_recorded` (`player`, `item`, `count`, `world_first` conditions), including records that already existed when the datapack was added
- **Milestone commands** — optionally run a server command on each confirmed record with `milestoneCommand` and `%player%`, `%uuid%`, `%item%`, `%count%`, `%playtime%`, and `%worldfirst%` placeholders (or call a `function`)
- **Scoreboard integration** — optionally mirror each player's record count and world-first count into vanilla objectives (`scoreboardObjective`, `scoreboardWorldFirstObjective`)
- **Automatic world-data backups** (default on) — keeps up to `autoBackupGenerations` compressed snapshots under `<world>/itemmilestonelog/backups/`, written on world save (at most hourly) and on server stop
- **Discord Webhook notifications** — optional, asynchronous, supporting plain text or rich embeds (`discordUseEmbeds`), English or Japanese (`discordLanguage`), preset-completion posts, `env:VAR_NAME` URLs, `Retry-After` retry, 401/404 auto-pause, per-player `/milestonelog discordoptout`, OP `/milestonelog discordtest [worldfirst]`, a periodic digest (`discordDigestEnabled`), a separate world-first webhook (`discordWorldFirstWebhookUrl`), and optional silent normal-record posts (`discordSilentRecords`)
- **JSON, HTML & CSV Export / Import** — export your milestones as JSON, standalone HTML, or CSV (`export`, `exporthtml`, `exportcsv`), import JSON (`import`), plus OP `exportall` (including world-firsts), `exporthtmlall`, `exportcsvall`, and `importall`
- **Custom, datapack & starter presets** — create JSON presets in `config/itemmilestonelog/presets/` or ship read-only presets in a datapack at `data/<namespace>/itemmilestonelog/presets/<id>.json`, save your current targets in-game, use per-language preset names, check preset progress (`/milestonelog presetprogress`), bulk-remove targets (`/milestonelog presetremove`, `removeall`), and distribute removable starter targets on first join (`firstJoinPresets`)
- **Rename old item IDs** — OP `/milestonelog renameitem <oldId> <newId> [confirm]` moves targets, records, progress markers, and world-firsts after writing a backup
- **Undo, pagination & in-game help** — 10-minute `/milestonelog undo` for personal resets and bulk removals, optional `[<page>]` pagination with clickable navigation, and `/milestonelog help [<subcommand>]`
- **OP Admin & maintenance tools** — inspect, add/remove targets, apply presets, grant milestones (`grant`), rename item IDs, reset, purge, or clean up empty long-absent player data (`prunedata`), with optional NeoForge permission nodes for non-OP staff
- **Creative mode doesn't count** — nothing is recorded in creative or spectator mode, including items you bring back to survival (configurable), with in-chat notices explaining skipped creative-carried items
- **Built-in presets**: `atm_star` (ATM Star + its 9 components) and `vanilla_endgame`
- **Server-wide default targets & feature switches** via the config file (`defaultTargets`, `presetsEnabled`, `exportImportEnabled`, `worldFirstEnabled`)
- **Optional client installation** — on a dedicated server, vanilla clients can join without installing anything and use all commands, with fallback messages matching their client language (English / Japanese)
- **Mod & KubeJS API** — subscribe to `MilestoneRecordedEvent` (with `isWorldFirst()` and `RecordCause`) and `PresetCompletedEvent`, or read/write via `ItemMilestoneLogApi` (tag keys supported; check `TargetKey.isTag()`)
- Languages: **English** and **Japanese**

#### Commands (no OP required, `/iml` works as an alias)
- `/milestonelog` — summary: your playtime and progress
- `/milestonelog help [<subcommand>]` — list commands available to you or show command usage
- `/milestonelog playtime` — your current cumulative playtime
- `/milestonelog add <item> [<count>]` / `addhand [<count>]` — add a goal item (with optional quantity 1–1,000,000)
- `/milestonelog addtag <tag> [<count>]` / `removetag <tag> [<count>]` / `resettag <tag> [<count>]` — add, remove, or reset a tag target (write tags without `#`)
- `/milestonelog remove <item> [<count>]` / `removeall` — remove a personal goal or all personal goals
- `/milestonelog targets [pending|done] [<page>]` — goals with recorded times, possession progress, or "not yet obtained"
- `/milestonelog list [<page>]` — recorded milestones, earliest first, with Day and date
- `/milestonelog info <item> [<count>]` / `info hand` — your status, world first, recorder count/rank, and related tag targets for one item
- `/milestonelog preset list` / `preset <name>` / `presetprogress <name>` / `presetremove <name>` — list, apply, check progress on, or bulk-remove built-in, custom, and datapack presets
- `/milestonelog firsts [<page>]` — list server-wide world-first milestones
- `/milestonelog lookup <player> [<page>]` / `top [<item> [<count>]]` / `toptag <tag> [<count>]` / `recent [<n>]` / `stats` — view other players, leaderboards (including tag targets), recent activity, and server stats
- `/milestonelog privacy` / `mutebroadcast` / `muteprogress` / `discordoptout` — personal privacy and notification controls
- `/milestonelog export` / `exporthtml` / `exportcsv` / `import <fileName>` — export (JSON, HTML, CSV) or merge JSON records
- `/milestonelog reset <item> [<count>]` / `resetall` / `undo` — clear records (goals are kept) and undo your last reset or bulk removal within 10 minutes
- OP commands: `/milestonelog preset save|delete|reload`, `firsts reset|resettag|resetall`, `exportall`, `exporthtmlall`, `exportcsvall`, `importall <fileName>`, `prunedata <days> [confirm]`, `renameitem <oldId> <newId> [confirm]`, `discordtest [worldfirst]`, `admin players [<page>]`, `admin <player> list|targets|add|remove|preset|grant|reset|resettag|resetall|purge`
- Permission nodes: `itemmilestonelog.admin`, `firsts.reset`, `export.all`, `import.all`, `prunedata`, `renameitem`, `discordtest`, `presets.manage`, and `viewhidden` can grant individual commands to non-OP staff. Nodes only add access; OP still keeps access, and console/RCON keep using permission level 2.

#### Configuration
`config/itemmilestonelog-common.toml` (1.21.1, 26.1.2, 26.2) or `config/itemmilestonelog-local.toml` (26.3):
- `defaultTargets`, `firstJoinPresets` — server-wide fixed defaults (`item#count` and `#tag#count`) and removable starter presets for first-time players
- `scanIntervalTicks` — inventory scan interval (default 20 = 1 second)
- `presetsEnabled`, `exportImportEnabled`, `worldFirstEnabled`, `allowLookup` — top-level feature switches (backups are not affected by `exportImportEnabled`)
- `notifyPlayer`, `broadcastMilestones`, `announceWorldFirst`, `announcePresetCompletion`, `groupSimultaneousRecords` — notification & grouping behaviour
- `progressAlerts`, `progressAlertThresholds`, `progressAlertMinCount` — quantity-target progress alerts
- `milestoneSoundEnabled`, `milestoneSound` — obtainment sound effect
- `chatItemHover`, `guiWorldFirstMark`, `itemTooltipStatus` — chat hover, GUI ★ marks, and client-side item tooltip status (the tooltip setting is read from each player's own file)
- `displayTimeZone` — server-side timezone for chat, logs, Discord, and exports (GUI uses the player's local timezone)
- `scoreboardObjective`, `scoreboardWorldFirstObjective` — optional vanilla scoreboard objectives for record/world-first counts (created automatically with the `dummy` criterion; clearing the setting leaves existing scores)
- `milestoneCommand` — optional server command on each record, with `%player%` / `%uuid%` / `%item%` / `%count%` / `%playtime%` / `%worldfirst%` placeholders (use `function <namespace>:<path>` for multiple actions; `%player%` is validated, and failures only log)
- `loginWorldFirstSummary` — chat summary of world-firsts recorded while a player was away (newest 5 plus a remaining count; skipped for muted broadcasts or a missing `lastSeen`)
- `autoBackupEnabled`, `autoBackupGenerations` — automatic backups in `<world>/itemmilestonelog/backups/` (on world save at most hourly, plus server stop; 1–20 generations)
- `discordWebhookUrl` (supports `env:VAR_NAME`), `discordNotifyScope`, `discordUseEmbeds`, `discordLanguage`, `discordNotifyPresetCompletion`, `allowDiscordOptOut` — optional Discord webhook notifications
- `discordWorldFirstWebhookUrl` — optional separate world-first webhook; world-firsts go only there, while records/preset completions/digests use the main URL
- `discordDigestEnabled`, `discordDigestIntervalDays` — periodic digest of new records and world-firsts (default off; 1–30 days; the first run only records the time, and failed 429 deliveries are not retried)
- `discordSilentRecords` — add Discord's `SUPPRESS_NOTIFICATIONS` flag (`flags: 4096`) to normal records and grouped posts without a world first (world-firsts, preset completions, digests, and tests still notify)
- `showFirstJoinHint` — one-time join tip
- `ignoreCreativeAndSpectator` — record nothing in creative or spectator mode (default true)

#### Important notes

- **Tags:** write tags without `#` in the tag commands (e.g. `/milestonelog addtag c:ingots/iron 100`), but as `#namespace:path` / `#namespace:path#count` in `defaultTargets`, presets, and exports. Tag targets cannot be added from the GUI item picker. 1.0.1/1.1.0 clients hide tag entries but still connect; API consumers should check `TargetKey.isTag()` before treating a returned ID as an item ID.
- **Advancements:** the `itemmilestonelog:milestone_recorded` trigger accepts optional `player`, `item`, `count`, and `world_first` conditions. Existing records are replayed on login and `/reload`; a record reset does not revoke an advancement. Right after adding a datapack, several `announce_to_chat` advancements can announce at once; disable that in the advancement JSON if it is noisy.
- **`milestoneCommand`:** runs with server-console permission and suppressed output for each confirmed record (not for imports, resets, or admin `grant`). Use `%uuid%` when names may be unusual; `%player%` is substituted only for names matching `^[A-Za-z0-9_.*+-]{1,20}$`, otherwise the command is skipped with a warning. For several actions use a datapack function, e.g. `milestoneCommand = "function mypack:milestone_reward"`. Failures are only logged and never block the record.
- **Scoreboards:** `scoreboardObjective` / `scoreboardWorldFirstObjective` create a missing `dummy` objective on first write. Use a dedicated name such as `iml_records`; pointing at another datapack's objective overwrites its scores. Setting the option back to empty stops updates but leaves the objective and scores in place.
- **Backups:** automatic files are `<world>/itemmilestonelog/backups/milestones-YYYYMMDD-HHmmss.dat`. To restore, stop the server, copy the chosen file over the live data file (`data/itemmilestonelog_data.dat` on 1.21.1, `data/itemmilestonelog/milestones.dat` on 26.x), and start the server. For a 1.21.1-era backup in 26.x, place it as `data/itemmilestonelog_data.dat` while `milestones.dat` does not exist.
- **Datapack presets:** `data/<namespace>/itemmilestonelog/presets/<id>.json`; precedence is built-in > config folder > datapack. They are read-only in game and reload with `/reload`, not `preset reload`. Subfolders are ignored, and the alphabetically first namespace wins a duplicate ID.
- **`renameitem`:** previews without `confirm`, writes a backup before applying, and only rewrites saved player data and world-firsts — `defaultTargets`, preset files, datapack presets, and export files are never edited (the command prints a reminder if an old ID remains).
- **Discord:** the digest's first run only records the current time, and a digest lost to HTTP 429/network failure is not retried; imported records with an in-period timestamp are included. With `discordWorldFirstWebhookUrl` set, world-firsts go only there. `discordSilentRecords` suppresses notifications only for normal records and grouped posts without a world first. The two webhooks are rate-limited and paused independently, and `/milestonelog discordtest worldfirst` tests the second one.
- **Permissions:** nodes are an alternative to permission level 2 and can only add access; OP access cannot be removed by a node. Set NeoForge's `permissionHandler` to your permission mod's handler. Newly granted nodes may not appear in tab completion until the player rejoins, but execution is checked immediately. Console/RCON keep using permission level 2.
- **Client display & config screen:** `itemTooltipStatus`, `chatItemHover`, and `guiWorldFirstMark` are display settings; the tooltip value is read from each player's own config file. The mod list's **Config** screen edits the settings on that PC and does not change a server's settings; the two webhook URLs are deliberately not shown. On a 1.0.1/1.1.0 server, tooltips appear only after opening the GUI once or recording a milestone. Chat and GUI use localized playtime/Day text; Discord, logs, exports, and the API stay in English.
- **Login summary & info:** `loginWorldFirstSummary` shows at most five entries. If the server crashes before the logout `lastSeen` is saved, world-firsts since the previous login can be repeated harmlessly. The count/rank line of `/milestonelog info` follows `allowLookup`.
- **Preset names & downgrade:** object-form `name` falls back to `en_us`, then the first entry, then the preset ID; 1.0.1/1.1.0 ignore the object form and show the ID. Downgrading from 1.2.0 to 1.1.0/1.0.1 drops tag targets/records/world-firsts and `lastDigestEpoch` on the next save, and 1.2.0 then treats the digest as a first run.

#### Requirements
| Minecraft | NeoForge | Java |
|---|---|---|
| 1.21.1 | 21.1.1 or newer (All the Mods 10 8.4 ships 21.1.265) | 21 |
| 26.1.2 | 26.1.2.71 or newer | 25 |
| 26.2 | 26.2.0.57 or newer | 25 |
| 26.3 | 26.3.0.76 or newer | 25 |

Download the file that matches your Minecraft version.

#### 日本語
累計プレイ時間（バニラ統計の「プレイ時間」）・ゲーム内経過日数（Day）・現実の日付（表示タイムゾーン設定可）で、目標アイテムを初めて手に入れた瞬間を記録するModです。プレイヤーごとに目標を自由に設定でき、個数つき目標（1〜1,000,000個、同時所持数で判定。例：ダイヤ ×1 と ×100 を別々に登録可能）や途中経過アラートに加え、アイテムタグ目標（`#c:ingots/iron` など。タグ内の全アイテムの所持数を合算。コマンドは `addtag` / `removetag` / `resettag` / `toptag`）にも対応しています。ATM Star用プリセットの内蔵に加え、JSONファイルやゲーム内操作でのカスタムプリセット作成、データパック配布プリセット（`data/<namespace>/itemmilestonelog/presets/`、読み取り専用、`/reload` で再読込）、言語ごとのプリセット表示名、プリセット進捗確認・制覇アナウンス、初回参加プレイヤー向けの目標配布（`firstJoinPresets`）、一括削除、10分以内の取り消し（`undo`）も備えています。機械生産・自動クラフトで作ったアイテムも、インベントリに入った時点で記録されます（※エンダーチェスト・シュルカーボックスの中身・他Modのストレージ内に直接入ったアイテムは、プレイヤーのインベントリに取り出すまでカウント・記録されません）。達成時の効果音、同時記録通知のまとめ、チャットのアイテムホバー、アイテムツールチップの目標／記録状態、GUIの世界初★、プレイ時間とDayの言語別表記、ワールド内「世界初」記録（改名後の現在名表示対応）、ログイン時の留守中世界初サマリー、`info` コマンド、他プレイヤー閲覧・ランキング・サーバー統計（`lookup` / `top` / `toptag` / `recent` / `stats`）と非公開設定（`privacy`）、Discord Webhook通知（Embed・日本語・環境変数URL・制覇通知・個人除外・テスト送信・週次ダイジェスト・世界初専用Webhook・通知音なし投稿）、JSON／HTML／CSVエクスポートおよびインポート（全員分・世界初含む）、ページ分け・`help`コマンド、データパック用 advancement トリガー、`milestoneCommand`、スコアボード連携、セーブデータ自動バックアップ、`renameitem`、権限ノード、NeoForge設定画面、OP向け管理・整理コマンド（`grant`・`prunedata`等）、他Mod/KubeJS向け読み書きAPI・イベントも備えています。クリエイティブ／スペクテイターモードで手に入れたアイテムは記録しません（設定で変更可能・持ち出し時の案内表示あり）。コマンドだけでなく、専用GUI（操作設定の「Item Milestone Log」でキーを割り当てて開きます。初期状態ではキーは割り当てられていません）からも目標の検索・個数指定での追加・削除や記録確認などの全機能を利用できます（専用サーバーではMOD未導入のクライアントもコマンドで利用でき、クライアントの言語設定に応じた英語または日本語で表示されます）。なお、1.2.0から1.1.0／1.0.1へダウングレードすると、タグの目標・記録・世界初と `lastDigestEpoch` は旧バージョンに読まれず、次の保存で失われます。

## Screenshots to add after the first in-game test
CurseForge does not require screenshots for a utility mod, but 2–3 images help the page a lot:
1. Chat showing "★ Milestone recorded: … at playtime …" right after picking up a goal item
2. The GUI with a mix of recorded and pending targets
3. Output of `/milestonelog preset atm_star` followed by `/milestonelog targets` in ATM10

They can be added under the project's Images section after the file is approved, so they do not block the first upload.

## Moderation notes
- Project avatar: `common/resources/logo.png` (512×512, not blank, not text-only)
- Summary and full description above, English first
- Files are plain JARs, one per Minecraft version
- New projects and files go through CurseForge moderation; approval can take some time after upload.
