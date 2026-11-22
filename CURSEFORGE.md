# CurseForge project page (paste-ready)

## Project name
Item Milestone Log

## Summary (one line)
Records the cumulative playtime at which you first obtain each of your own goal items — perfect for ATM Star hunts in ATM10.

## Categories
Mods → (choose up to 3) Utility & QoL / Server Utility / Adventure and RPG

## Logo
logo-512.png (512×512, in this folder / repository root)

## Description (English first, required by moderation)

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
- **Optional client installation** — on a dedicated server, vanilla clients can join without installing anything and use all commands
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

#### Configuration (`config/itemmilestonelog-common.toml`)
- `defaultTargets` — items everyone tracks by default
- `scanIntervalTicks` — inventory scan interval (default 20 = 1 second)
- `notifyPlayer`, `broadcastMilestones` — notification behaviour

#### Requirements
Minecraft **1.21.1**, NeoForge **21.1.x**, Java 21. Works in any 1.21.1 NeoForge pack, including All the Mods 10.

#### 日本語
累計プレイ時間（バニラ統計の「プレイ時間」）で、目標アイテムを初めて手に入れた瞬間を記録するModです。プレイヤーごとに目標を自由に設定でき、ATM Star用プリセットも内蔵しています。機械生産・自動クラフトで作ったアイテムも、インベントリに入った時点で記録されます。コマンドだけでなく、キー入力（初期設定 `M`）で開く専用GUIからも目標の検索・追加・削除や記録確認などの全機能を利用できます（専用サーバーではバニラクライアントも従来どおりコマンドで利用可能）。

## Project license setting
- Choose **Custom License** and paste the repository's `LICENSE` file text
  (All Rights Reserved custom terms: personal use and free modpack inclusion
  with credit are allowed; redistribution, re-uploading, selling and modified
  versions need the author's permission).

## First file upload settings
- File: itemmilestonelog-1.0.0.jar (from GitHub Actions artifact / GitHub Release)
- Release type: Release
- Game version: 1.21.1
- Mod loader: NeoForge
- Display name: Item Milestone Log 1.0.0

## Screenshots to add after the first in-game test
CurseForge does not require screenshots for a utility mod, but 2–3 images greatly help the page. Take these once the JAR runs in ATM10 (or any 1.21.1 world):
1. Chat showing "★ Milestone recorded: … at playtime …" right after picking up a goal item
2. Output of `/milestonelog targets` with a mix of recorded (✔ with playtime) and pending (… not yet obtained) entries
3. Output of `/milestonelog preset atm_star` followed by `/milestonelog targets` showing the ATM Star component list
Upload them under the project page's Images section; they can be added after the file is approved, so they do not block the first upload.

## Moderation notes
- Project avatar: use logo-512.png (≥400×400, not blank, no text-only) ✔
- Summary and full description prepared above, English first ✔
- File must be a JAR ✔
- New projects/files go through CurseForge moderation; approval can take some time after upload.
