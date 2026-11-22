# CurseForge project page (paste-ready)

This file holds the text and settings for the CurseForge project and its files.
Sections marked *(paste)* go into CurseForge as they are.

## Before the first upload

- [ ] CI is green on `main` (all 8 jobs of the **Build** workflow).
- [ ] The four jars come from that run (see [Getting the jars](#getting-the-jars)).
- [ ] Quick manual check in a client with the mod installed (not covered by CI):
      the `M` key opens the screen, targets can be added from the item picker,
      presets and resets work.
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
| `itemmilestonelog-neoforge-1.21.1-1.0.0.jar` | Item Milestone Log 1.0.0 (1.21.1) | 1.21.1 | NeoForge | Java 21 | Client, Server |
| `itemmilestonelog-neoforge-26.1.2-1.0.0.jar` | Item Milestone Log 1.0.0 (26.1.2) | 26.1.2 | NeoForge | Java 25 | Client, Server |
| `itemmilestonelog-neoforge-26.2-1.0.0.jar` | Item Milestone Log 1.0.0 (26.2) | 26.2 | NeoForge | Java 25 | Client, Server |
| `itemmilestonelog-neoforge-26.3-1.0.0.jar` | Item Milestone Log 1.0.0 (26.3) | 26.3 | NeoForge | Java 25 | Client, Server |

- Release type: **Release**.
- Related projects: none (the mod only needs NeoForge).
- Environment: the mod is needed on the server (or in singleplayer); on a dedicated
  server the client side is optional and only adds the GUI. If CurseForge offers only
  one choice, pick the setting that marks it as working on both client and server.
- If CurseForge does not list one of the 26.x game versions yet, upload that file
  once the version is available rather than tagging it with a different version.

### Changelog for 1.0.0 *(paste)*

```
First release.
- Records your cumulative playtime (Time Played statistic) the first time each of your target items enters your inventory, and notifies you in chat.
- Per-player targets, server-wide default targets (config), presets: atm_star, vanilla_endgame.
- In-game GUI (default key M) when the mod is also installed on the client; vanilla clients can join a server with the mod and use all /milestonelog (/iml) commands.
- English and Japanese.
```

## Getting the jars

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

Item Milestone Log records the **cumulative playtime** (the vanilla *Time Played* statistic) at the moment you **first obtain** each of your personal goal items, and tells you right away in chat:

> ★ Milestone recorded: ATM Star at playtime 87h 42m 10s

It was built for long modpack goals — above all the **ATM Star in All the Mods 10** — where a single endgame item takes dozens of hours and a shelf of automation to produce, and you want the receipt for exactly when each component first landed in your hands.

#### Why playtime, not world time?
The record uses your personal *Time Played* statistic, not the world clock. Sleeping through nights does not inflate it, and it is *your* time in the world, not the server's.

#### Why "first obtained", not "crafted"?
Detection is possession-based. Items produced by machines, automation, AE2 autocrafting, smelting, or dropped by mobs are recorded the first time they enter your inventory — not only items you craft by hand.

#### Features
- **Per-player targets** — every player sets and tracks their own goal items
- **Cumulative playtime records**, stored per world, shown as e.g. `27h 15m 30s`
- **In-game GUI** — press `M` (rebindable) to view progress, search and add targets, apply presets, and manage records without typing commands (requires the mod on the client; vanilla clients can still join and use commands)
- **Chat notification** the moment a goal item is first obtained (configurable; optional server-wide broadcast)
- **Built-in presets**: `atm_star` (ATM Star + its 9 components) and `vanilla_endgame`
- **Server-wide default targets** via the config file
- **Optional client installation** — on a dedicated server, vanilla clients can join without installing anything and use all commands, with messages shown in English
- Languages: **English** and **Japanese**

#### Commands (no OP required, `/iml` works as an alias)
- `/milestonelog` — summary: your playtime and progress
- `/milestonelog playtime` — your current cumulative playtime
- `/milestonelog add <item>` / `addhand` — add a goal item (or the one in your hand)
- `/milestonelog remove <item>` — remove a personal goal
- `/milestonelog targets` — goals with their recorded times, or "not yet obtained"
- `/milestonelog list` — recorded milestones, earliest first
- `/milestonelog preset atm_star` — apply a preset in one command
- `/milestonelog reset <item>` / `resetall` — clear records (goals are kept)

#### Configuration
`config/itemmilestonelog-common.toml` (1.21.1, 26.1.2, 26.2) or `config/itemmilestonelog-local.toml` (26.3):
- `defaultTargets` — items everyone tracks by default
- `scanIntervalTicks` — inventory scan interval (default 20 = 1 second)
- `notifyPlayer`, `broadcastMilestones` — notification behaviour

#### Requirements
| Minecraft | NeoForge | Java |
|---|---|---|
| 1.21.1 | 21.1.1 or newer (All the Mods 10 8.4 ships 21.1.265) | 21 |
| 26.1.2 | 26.1.2.71 or newer | 25 |
| 26.2 | 26.2.0.57 or newer | 25 |
| 26.3 | 26.3.0.76 or newer | 25 |

Download the file that matches your Minecraft version.

#### 日本語
累計プレイ時間（バニラ統計の「プレイ時間」）で、目標アイテムを初めて手に入れた瞬間を記録するModです。プレイヤーごとに目標を自由に設定でき、ATM Star用プリセットも内蔵しています。機械生産・自動クラフトで作ったアイテムも、インベントリに入った時点で記録されます。コマンドだけでなく、キー入力（初期設定 `M`）で開く専用GUIからも目標の検索・追加・削除や記録確認などの全機能を利用できます（専用サーバーではMOD未導入のクライアントもコマンドで利用でき、メッセージは英語で表示されます）。

## Screenshots to add after the first in-game test
CurseForge does not require screenshots for a utility mod, but 2–3 images help the page a lot:
1. Chat showing "★ Milestone recorded: … at playtime …" right after picking up a goal item
2. The GUI (`M`) with a mix of recorded and pending targets
3. Output of `/milestonelog preset atm_star` followed by `/milestonelog targets` in ATM10

They can be added under the project's Images section after the file is approved, so they do not block the first upload.

## Moderation notes
- Project avatar: `common/resources/logo.png` (512×512, not blank, not text-only)
- Summary and full description above, English first
- Files are plain JARs, one per Minecraft version
- New projects and files go through CurseForge moderation; approval can take some time after upload.
