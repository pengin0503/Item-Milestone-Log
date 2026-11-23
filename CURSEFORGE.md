# CurseForge project page (paste-ready)

This file holds the text and settings for the CurseForge project and its files.
Sections marked *(paste)* go into CurseForge as they are.

## Release checklist

- [ ] CI is green on `main` (all jobs of the **Build** workflow).
- [ ] `docs/updates.json` is updated with the new version's entry and `promos` (`<mc>-latest` and `<mc>-recommended`).
- [ ] The four jars come from the GitHub Release of the version tag, or from that
      CI run (see [Getting the jars](#getting-the-jars)).
- [ ] Quick manual check in a client with the mod installed (not covered by CI):
      after assigning a key to *Open Milestone Log* under Controls, the key opens the
      screen, targets can be added from the item picker, presets (including the Presets
      list screen) and resets work, and connecting to a server without the mod shows
      no mismatch warning (`displayTest="IGNORE_ALL_VERSION"`).
- [ ] Optional but recommended for the ATM10 audience: run `/milestonelog preset atm_star`
      in ATM10 and check that it reports `0 skipped` (issue #15).

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

Upload one file per Minecraft version. Each jar only loads on its own Minecraft
version, so tag each file with exactly one game version.

| File | Display name | Game version | Mod loader | Java | Environment |
|---|---|---|---|---|---|
| `itemmilestonelog-neoforge-1.21.1-1.1.0.jar` | Item Milestone Log 1.1.0 (1.21.1) | 1.21.1 | NeoForge | Java 21 | Client, Server |
| `itemmilestonelog-neoforge-26.1.2-1.1.0.jar` | Item Milestone Log 1.1.0 (26.1.2) | 26.1.2 | NeoForge | Java 25 | Client, Server |
| `itemmilestonelog-neoforge-26.2-1.1.0.jar` | Item Milestone Log 1.1.0 (26.2) | 26.2 | NeoForge | Java 25 | Client, Server |
| `itemmilestonelog-neoforge-26.3-1.1.0.jar` | Item Milestone Log 1.1.0 (26.3) | 26.3 | NeoForge | Java 25 | Client, Server |

- Release type: **Release**.
- Related projects: none (the mod only needs NeoForge).
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
`v<version>` has the four jars attached. They do not expire.

Without a tag:

1. Open the **Actions** tab on GitHub → workflow **Build** → the latest successful run on `main`.
2. Under **Artifacts**, download `itemmilestonelog-1.21.1`, `itemmilestonelog-26.1.2`,
   `itemmilestonelog-26.2` and `itemmilestonelog-26.3`. Each zip holds one jar
   (built against the minimum NeoForge of that Minecraft version, and tested on both
   the minimum and the latest NeoForge).
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
- **Cumulative playtime, Day & date records**, stored per world, shown as e.g. `27h 15m 30s (Day 12, 2026-11-22)`, with configurable server display timezone (`displayTimeZone`)
- **World-first records** — tracks the first player to obtain each item (and quantity) in the world (`/milestonelog firsts`, showing current usernames with `(was <old name>)` after renames, with optional server-wide announcements)
- **Lookup, rankings & server stats** — browse other players' records (`/milestonelog lookup`), leaderboards (`/milestonelog top`, `/milestonelog recent`), and server statistics (`/milestonelog stats`), with personal privacy opt-out (`/milestonelog privacy`)
- **In-game GUI** — view progress, search and add targets (with quantity input), browse/apply/save custom presets (with overwrite warning), and manage records without typing commands. Assign a key to *Open Milestone Log* under Options → Controls → Key Binds first: there is no default key, because `M` already opens the FTB Chunks map in ATM10. (Requires the mod on the client; vanilla clients can still join and use commands.)
- **Chat notification, grouping & sound effect** — instant notifications with configurable sound, automatic grouping when 3+ milestones trigger in the same tick (`groupSimultaneousRecords`), optional server-wide & preset-completion broadcasts, and `/milestonelog mutebroadcast` opt-out
- **Discord Webhook notifications** — optional, asynchronous, supporting plain text or rich embeds (`discordUseEmbeds`), English or Japanese (`discordLanguage`), preset-completion posts, `env:VAR_NAME` URLs, `Retry-After` retry, 401/404 auto-pause, per-player `/milestonelog discordoptout`, and OP `/milestonelog discordtest`
- **JSON, HTML & CSV Export / Import** — export your milestones as JSON, standalone HTML, or CSV (`export`, `exporthtml`, `exportcsv`), import JSON (`import`), plus OP `exportall` (including world-firsts), `exporthtmlall`, `exportcsvall`, and `importall`
- **Custom presets, progress & starter presets** — create JSON presets in `config/itemmilestonelog/presets/`, save your current targets in-game, check preset progress (`/milestonelog presetprogress`), bulk-remove targets (`/milestonelog presetremove`, `removeall`), and distribute removable starter targets on first join (`firstJoinPresets`)
- **Undo, pagination & in-game help** — 10-minute `/milestonelog undo` for personal resets and bulk removals, optional `[<page>]` pagination with clickable navigation, and `/milestonelog help [<subcommand>]`
- **OP Admin & maintenance tools** — inspect, add/remove targets, apply presets, grant milestones (`grant`), reset, purge, or clean up empty long-absent player data (`prunedata`)
- **Creative mode doesn't count** — nothing is recorded in creative or spectator mode, including items you bring back to survival (configurable), with in-chat notices explaining skipped creative-carried items
- **Built-in presets**: `atm_star` (ATM Star + its 9 components) and `vanilla_endgame`
- **Server-wide default targets & feature switches** via the config file (`defaultTargets`, `presetsEnabled`, `exportImportEnabled`, `worldFirstEnabled`)
- **Optional client installation** — on a dedicated server, vanilla clients can join without installing anything and use all commands, with fallback messages matching their client language (English / Japanese)
- **Mod & KubeJS API** — subscribe to `MilestoneRecordedEvent` (with `isWorldFirst()` and `RecordCause`) and `PresetCompletedEvent`, or read/write via `ItemMilestoneLogApi`
- Languages: **English** and **Japanese**

#### Commands (no OP required, `/iml` works as an alias)
- `/milestonelog` — summary: your playtime and progress
- `/milestonelog help [<subcommand>]` — list commands available to you or show command usage
- `/milestonelog playtime` — your current cumulative playtime
- `/milestonelog add <item> [<count>]` / `addhand [<count>]` — add a goal item (with optional quantity 1–1,000,000)
- `/milestonelog remove <item> [<count>]` / `removeall` — remove a personal goal or all personal goals
- `/milestonelog targets [pending|done] [<page>]` — goals with recorded times, possession progress, or "not yet obtained"
- `/milestonelog list [<page>]` — recorded milestones, earliest first, with Day and date
- `/milestonelog preset list` / `preset <name>` / `presetprogress <name>` / `presetremove <name>` — list, apply, check progress on, or bulk-remove presets
- `/milestonelog firsts [<page>]` — list server-wide world-first milestones
- `/milestonelog lookup <player> [<page>]` / `top [<item> [<count>]]` / `recent [<n>]` / `stats` — view other players, leaderboards, recent activity, and server stats
- `/milestonelog privacy` / `mutebroadcast` / `muteprogress` / `discordoptout` — personal privacy and notification controls
- `/milestonelog export` / `exporthtml` / `exportcsv` / `import <fileName>` — export (JSON, HTML, CSV) or merge JSON records
- `/milestonelog reset <item> [<count>]` / `resetall` / `undo` — clear records (goals are kept) and undo your last reset or bulk removal within 10 minutes
- OP commands: `/milestonelog preset save|delete|reload`, `firsts reset|resetall`, `exportall`, `exporthtmlall`, `exportcsvall`, `importall <fileName>`, `prunedata <days> [confirm]`, `discordtest`, `admin players [<page>]`, `admin <player> list|targets|add|remove|preset|grant|reset|resetall|purge`

#### Configuration
`config/itemmilestonelog-common.toml` (1.21.1, 26.1.2, 26.2) or `config/itemmilestonelog-local.toml` (26.3):
- `defaultTargets`, `firstJoinPresets` — server-wide fixed defaults (`item#count`) and removable starter presets for first-time players
- `scanIntervalTicks` — inventory scan interval (default 20 = 1 second)
- `presetsEnabled`, `exportImportEnabled`, `worldFirstEnabled`, `allowLookup` — top-level feature switches
- `notifyPlayer`, `broadcastMilestones`, `announceWorldFirst`, `announcePresetCompletion`, `groupSimultaneousRecords` — notification & grouping behaviour
- `progressAlerts`, `progressAlertThresholds`, `progressAlertMinCount` — quantity-target progress alerts
- `milestoneSoundEnabled`, `milestoneSound` — obtainment sound effect
- `displayTimeZone` — server-side timezone for chat, logs, Discord, and exports (GUI uses the player's local timezone)
- `discordWebhookUrl` (supports `env:VAR_NAME`), `discordNotifyScope`, `discordUseEmbeds`, `discordLanguage`, `discordNotifyPresetCompletion`, `allowDiscordOptOut` — optional Discord webhook notifications
- `showFirstJoinHint` — one-time join tip
- `ignoreCreativeAndSpectator` — record nothing in creative or spectator mode (default true)

#### Requirements
| Minecraft | NeoForge | Java |
|---|---|---|
| 1.21.1 | 21.1.1 or newer (All the Mods 10 8.4 ships 21.1.265) | 21 |
| 26.1.2 | 26.1.2.71 or newer | 25 |
| 26.2 | 26.2.0.57 or newer | 25 |
| 26.3 | 26.3.0.76 or newer | 25 |

Download the file that matches your Minecraft version.

#### 日本語
累計プレイ時間（バニラ統計の「プレイ時間」）・ゲーム内経過日数（Day）・現実の日付（表示タイムゾーン設定可）で、目標アイテムを初めて手に入れた瞬間を記録するModです。プレイヤーごとに目標を自由に設定でき、個数つき目標（1〜1,000,000個、同時所持数で判定。例：ダイヤ ×1 と ×100 を別々に登録可能）や途中経過アラートにも対応しています。ATM Star用プリセットの内蔵に加え、JSONファイルやゲーム内操作でのカスタムプリセット作成、プリセット進捗確認・制覇アナウンス、初回参加プレイヤー向けの目標配布（`firstJoinPresets`）、一括削除、10分以内の取り消し（`undo`）も備えています。機械生産・自動クラフトで作ったアイテムも、インベントリに入った時点で記録されます（※エンダーチェスト・シュルカーボックスの中身・他Modのストレージ内に直接入ったアイテムは、プレイヤーのインベントリに取り出すまでカウント・記録されません）。達成時の効果音、同時記録通知のまとめ、ワールド内「世界初」記録（改名後の現在名表示対応）、他プレイヤー閲覧・ランキング・サーバー統計（`lookup` / `top` / `recent` / `stats`）と非公開設定（`privacy`）、Discord Webhook通知（Embed・日本語・環境変数URL・制覇通知・個人除外・テスト送信対応）、JSON／HTML／CSVエクスポートおよびインポート（全員分・世界初含む）、ページ分け・`help`コマンド、OP向け管理・整理コマンド（`grant`・`prunedata`等）、他Mod/KubeJS向け読み書きAPI・イベントも備えています。クリエイティブ／スペクテイターモードで手に入れたアイテムは記録しません（設定で変更可能・持ち出し時の案内表示あり）。コマンドだけでなく、専用GUI（操作設定の「Item Milestone Log」でキーを割り当てて開きます。初期状態ではキーは割り当てられていません）からも目標の検索・個数指定での追加・削除や記録確認などの全機能を利用できます（専用サーバーではMOD未導入のクライアントもコマンドで利用でき、クライアントの言語設定に応じた英語または日本語で表示されます）。

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
