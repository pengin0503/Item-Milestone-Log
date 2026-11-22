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
| `itemmilestonelog-neoforge-1.21.1-1.0.1.jar` | Item Milestone Log 1.0.1 (1.21.1) | 1.21.1 | NeoForge | Java 21 | Client, Server |
| `itemmilestonelog-neoforge-26.1.2-1.0.1.jar` | Item Milestone Log 1.0.1 (26.1.2) | 26.1.2 | NeoForge | Java 25 | Client, Server |
| `itemmilestonelog-neoforge-26.2-1.0.1.jar` | Item Milestone Log 1.0.1 (26.2) | 26.2 | NeoForge | Java 25 | Client, Server |
| `itemmilestonelog-neoforge-26.3-1.0.1.jar` | Item Milestone Log 1.0.1 (26.3) | 26.3 | NeoForge | Java 25 | Client, Server |

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
- **Per-player targets** — every player sets and tracks their own goal items
- **Cumulative playtime, Day & date records**, stored per world, shown as e.g. `27h 15m 30s (Day 12, 2026-11-22)`
- **World-first records** — tracks the first player to obtain each item in the world (`/milestonelog firsts`, with optional server-wide announcements)
- **In-game GUI** — view progress, search and add targets, browse/apply/save custom presets, and manage records without typing commands. Assign a key to *Open Milestone Log* under Options → Controls → Key Binds first: there is no default key, because `M` already opens the FTB Chunks map in ATM10. (Requires the mod on the client; vanilla clients can still join and use commands.)
- **Chat notification & sound effect** the moment a goal item is first obtained (configurable sound, optional server-wide broadcast with `/milestonelog mutebroadcast` opt-out)
- **Discord Webhook notifications** (optional, asynchronous)
- **JSON Export / Import** — back up or transfer records with `/milestonelog export` and `import`
- **Custom presets** — create JSON presets in `config/itemmilestonelog/presets/` or save your current targets in-game
- **OP Admin tools** — inspect, reset, or purge online and offline players' data
- **Creative mode doesn't count** — nothing is recorded in creative or spectator mode, including items you bring back to survival (configurable)
- **Built-in presets**: `atm_star` (ATM Star + its 9 components) and `vanilla_endgame`
- **Server-wide default targets** via the config file
- **Optional client installation** — on a dedicated server, vanilla clients can join without installing anything and use all commands, with fallback messages matching their client language (English / Japanese)
- **Mod & KubeJS API** — subscribe to `MilestoneRecordedEvent` or query `ItemMilestoneLogApi`
- Languages: **English** and **Japanese**

#### Commands (no OP required, `/iml` works as an alias)
- `/milestonelog` — summary: your playtime and progress
- `/milestonelog playtime` — your current cumulative playtime
- `/milestonelog add <item>` / `addhand` — add a goal item (or the one in your hand)
- `/milestonelog remove <item>` — remove a personal goal
- `/milestonelog targets` — goals with their recorded times, or "not yet obtained"
- `/milestonelog list` — recorded milestones, earliest first, with Day and date
- `/milestonelog preset list` / `preset <name>` — list or apply built-in and custom presets
- `/milestonelog firsts` — list server-wide world-first milestones
- `/milestonelog export` / `import <fileName>` — export or merge JSON records
- `/milestonelog mutebroadcast` — toggle muting other players' milestone broadcasts
- `/milestonelog reset <item>` / `resetall` — clear records (goals are kept)
- OP commands: `/milestonelog preset save|delete|reload`, `firsts reset|resetall`, `exportall`, `admin players|<player> list|targets|reset|resetall|purge`

#### Configuration
`config/itemmilestonelog-common.toml` (1.21.1, 26.1.2, 26.2) or `config/itemmilestonelog-local.toml` (26.3):
- `defaultTargets` — items everyone tracks by default
- `scanIntervalTicks` — inventory scan interval (default 20 = 1 second)
- `notifyPlayer`, `broadcastMilestones`, `announceWorldFirst` — notification behaviour
- `milestoneSoundEnabled`, `milestoneSound` — obtainment sound effect
- `discordWebhookUrl`, `discordNotifyScope` — optional Discord webhook notifications
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
累計プレイ時間（バニラ統計の「プレイ時間」）・ゲーム内経過日数（Day）・現実の日付で、目標アイテムを初めて手に入れた瞬間を記録するModです。プレイヤーごとに目標を自由に設定でき、ATM Star用プリセットの内蔵に加え、JSONファイルやゲーム内操作でのカスタムプリセット作成にも対応しています。機械生産・自動クラフトで作ったアイテムも、インベントリに入った時点で記録されます（※エンダーチェスト・シュルカーボックスの中身・他Modのストレージ内に直接入ったアイテムは、プレイヤーのインベントリに取り出すまで記録されません）。達成時の効果音、ワールド内「世界初」記録、Discord Webhook通知、JSONエクスポート／インポート、OP向け管理コマンド、他Mod/KubeJS向けAPIも備えています。クリエイティブ／スペクテイターモードで手に入れたアイテムは記録しません（設定で変更できます）。コマンドだけでなく、専用GUI（操作設定の「Item Milestone Log」でキーを割り当てて開きます。初期状態ではキーは割り当てられていません）からも目標の検索・追加・削除や記録確認などの全機能を利用できます（専用サーバーではMOD未導入のクライアントもコマンドで利用でき、クライアントの言語設定に応じた英語または日本語で表示されます）。

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
