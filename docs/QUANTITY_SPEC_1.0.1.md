# Item Milestone Log — 個数つき目標 実装仕様書（v1.0.1 追加分）

この文書は、AI（または開発者）がこの文書だけを読んで実装を完遂できるように書かれた自己完結の仕様書である。対象リポジトリ: pengin0503/Item-Milestone-Log

先に `docs/FEATURE_SPEC_1.0.1.md` と `docs/CUSTOM_PRESET_SPEC_1.0.1.md` を通読すること。本仕様はその 1.0.1 実装済みコード（main にマージ済み）への**追加機能**である。

## 0. 前提（現在の状態）

- Mod: Item Milestone Log（mod_id: `itemmilestonelog`、パッケージ: `com.pengin0503.itemmilestonelog`）
- 対応: Minecraft 1.21.1 / 26.1.2 / 26.2 / 26.3（`versions/<ver>/` 構成、共通リソースは `common/`）。全バージョンで機能は同一でなければならない
- バージョン 1.0.1 は main に実装済みだが**未リリース**（CurseForge / Modrinth に掲載中なのは 1.0.0 のみ）。本機能は 1.0.1 のリリース前に取り込むため、**mod バージョンは 1.0.1 のまま**据え置く。1.0.0 のファイルには触れない
- 現在の仕様: 目標は「アイテムの種類」単位で、個数は1個固定。同じアイテムに複数の目標は持てない
- 既存の主なクラス（各バージョン配下、`com.pengin0503.itemmilestonelog`）:
  - `MilestoneData`（SavedData）: NBT 名 `itemmilestonelog_data`。`players` → UUID ごとの compound に `targets`（文字列リスト）、`records`（itemId → compound `{ticks, day, epoch}`、旧 1.0.0 形式は int のみで読み替え対応済み）、`carriedFromCreative`（文字列リスト）、`lastKnownName` 等。ワールド全体の `worldFirsts`（itemId → 記録構造）も保持
  - `MilestoneService` / `Tracker`: 記録の確定はここを通る（インベントリスキャン＋クラフト/精錬/拾得イベント、クリエイティブ・スペクテイター除外あり）
  - `MilestoneCommands`: `/milestonelog`（エイリアス `/iml`、原則 OP 不要）配下に add / addhand / remove / targets / list / playtime / preset / reset / resetall / firsts / export / import / 管理用コマンド等
  - `Config`: `defaultTargets`、`scanIntervalTicks`、`notifyPlayer`、`broadcastMilestones`、`ignoreCreativeAndSpectator`、1.0.1 で追加された各種設定
  - `Presets`: 組み込みプリセット（atm_star / vanilla_endgame）と `config/itemmilestonelog/presets/<id>.json` のカスタムプリセット
  - `MilestoneExport`: export / import 用 JSON 形式
  - `network/`: `RequestSyncPayload`、`SyncMilestonesPayload`、`MilestoneActionPayload`、`TargetEntry`。ペイロードは **optional 登録**で、バニラクライアントも専用サーバーに参加できる
  - `client/`: `MilestoneScreen`、アイテム選択画面（ItemPicker 相当）、`PresetListScreen`、`SavePresetScreen` 等
  - `api/`: `MilestoneRecordedEvent`、`ItemMilestoneLogApi`
  - `ServerLang`: バニラクライアント向け言語フォールバック
  - CI: GitHub Actions が全4バージョンのビルド＋本番 NeoForge 専用サーバーでのボットテスト（記録・移行を含む）を実行している

## 1. 共通制約（全項目に適用）

1. **GUI のデザインは変更しない**（決定済み）。許可されるのは既存レイアウト内での表示テキストの追加・変更と、本仕様で明示する入力欄1つの追加のみ。ボタン配置、画面構成、色は変えない
2. 既存コマンドの構文・動作は原則変えない。本仕様で明示する**末尾の任意引数 `<count>` の追加のみ**を例外として許可する。引数を省略した場合の動作は従来と完全に同一でなければならない
3. **SavedData の後方互換を必須**とする。1.0.0 および現行 1.0.1（個数なし）形式のセーブデータを読み込めて壊れないこと。移行規則は Q1 に定める
4. バニラクライアントが専用サーバーに参加できる性質を維持する（ペイロードは optional 登録のまま。1.0.1 は未リリースなので、ペイロード形式を 1.0.0 と揃える必要はない）
5. 追加する表示テキストは en_us / ja_jp 両方に lang キーを追加し、キー数を一致させる
6. 追加依存ライブラリはなし
7. 実装は全4バージョンに行う。各バージョンの API 差異は既存コードの書き方に合わせる
8. 記録確定後の副作用（サウンド、世界初判定、Discord Webhook、ブロードキャスト、API イベント）は、既存の記録確定フロー（MilestoneService）を通る形で個数対応させる。別経路を作らない

## 2. 機能概要

目標を「アイテム」単位から「**アイテム＋個数**」単位に拡張する。

- 例: `minecraft:diamond` の目標を「×1」と「×100」で別々に登録でき、それぞれ独立に初回達成時のプレイ時間（＋Day・日付）を記録する
- 判定方式は**同時所持数**とする: インベントリ内にあるそのアイテムの合計数が、目標個数に初めて達した瞬間を記録する。使って減っても記録は消えない
- 累計獲得数方式（拾った総数）は採用しない

---

## Q1. データモデル

### 仕様
- 目標の同一性は `(itemId, count)` の組とする。`count` の範囲は **1〜1,000,000**
- 内部表現として `(itemId, count)` を表す型（例: `TargetKey` レコード）を新設し、サービス層・ネットワーク・API ではこの型を使う
- NBT 保存形式:
  - `targets`（文字列リスト）は形状を維持し、エンコーディングを拡張する: count = 1 は従来通り `itemId` のみ、count > 1 は `itemId#count`（例: `minecraft:diamond#100`）。ロード時、`#` が無い要素は count = 1 として読む
  - `records` は itemId → compound の形状を維持し、値を「count（文字列キー）→ 記録 compound `{ticks, day, epoch}`」の compound に拡張する。ロード時は次の全形式に対応すること:
    - 新形式: itemId → { "100": {ticks, day, epoch}, ... }
    - 1.0.1 形式: itemId → {ticks, day, epoch}（count = 1 の記録として読む。判定方法: 値の compound に `ticks` キーがあれば旧形式）
    - 1.0.0 形式: itemId → int（count = 1、ticks のみとして読む。対応済みの既存処理を壊さない）
  - `worldFirsts` も同様に (itemId, count) 単位に拡張し、旧形式は count = 1 として読む
- 1.21.1 → 26.x の記録マイグレーションでも上記の読み替えが効くこと（移行先で新フィールドが欠ける場合は count = 1 として継続）

### 実装メモ
- `itemId#count` のパースは1か所（TargetKey のファクトリ）に集約し、不正な文字列（count が数値でない、範囲外）は無効な目標として読み飛ばす（warn ログ）
- アイテム ID 自体に `#` は含まれないため、このエンコーディングは安全

## Q2. 検出（同時所持数のカウント）

### 仕様
- 既存のインベントリスキャンの際、プレイヤーごとに「目標に含まれるアイテム」の所持合計数を数える:
  - 数える範囲: メインインベントリ＋オフハンド＋カーソルで掴んでいるスタック（carried）
  - 数えないもの: エンダーチェスト、シュルカーボックスの中身、その他外部ストレージ（従来の検出範囲の注記 F14 と同じ）
- 記録条件: ある `(itemId, count)` について、合計数が count 以上であり、まだその組の記録が無ければ記録を確定する
- クラフト/精錬/拾得イベント時は、従来通りその場で対象アイテムについて再カウントして判定する（スキャンを待たずに記録できる）
- 同一アイテムの複数目標は独立に判定する:
  - ×1 と ×100 の両方が目標で、所持数が 0 → 100 に一気に増えた場合、**両方ともその時点で記録**される（同じ ticks / day / epoch でよい）
  - ×100 だけが目標の場合、1個手に入れても記録しない
- クリエイティブ・スペクテイター除外（`ignoreCreativeAndSpectator`、`carriedFromCreative`）の既存セマンティクスはアイテム単位のまま変えない。個数判定はその除外を通過したアイテムについてのみ行う

### 実装メモ
- スキャンの計算量が増えないよう、カウントは「そのプレイヤーの目標に含まれるアイテム ID」だけを対象に集計する（全アイテムの集計はしない）
- 記録確定は既存の MilestoneService の入口を通し、(itemId, count) を渡す形に拡張する

## Q3. コマンド

### 仕様（いずれも末尾の任意引数追加のみ。省略時は従来動作）
- `/milestonelog add <item> [<count>]`: count 省略時は 1。既に同じ (item, count) が目標にある場合は従来の「登録済み」フィードバック。同じアイテムでも count が違えば追加できる
- `/milestonelog addhand [<count>]`: 手持ちアイテムを対象に同上
- `/milestonelog remove <item> [<count>]`: 指定の組を目標から外す
- `/milestonelog reset <item> [<count>]`: 指定の組の記録をリセットする（従来の reset の意味を組単位に拡張）
- F9 の管理用コマンド（他プレイヤーの表示・リセット等）も同じ組単位で扱えるようにする
- `/milestonelog firsts reset <item> [<count>]`: 世界初の削除も組単位（権限レベル2必須は従来通り）
- 表示系:
  - `targets` / `list` / `firsts` の各行で、count > 1 の場合はアイテム名の後に ` ×100` の形式で個数を併記する（count = 1 の表示は従来通り個数を出さない）
  - add / remove / reset のフィードバックメッセージも count > 1 の場合は個数を併記する
- count 引数は Brigadier の integer（1〜1,000,000）で検証する

## Q4. GUI

### 仕様（デザイン凍結の範囲内）
- アイテム選択画面に、個数を入力する小さな数値入力欄を1つ追加する（既定値 `1`）。配置は既存レイアウトの空きスペースを使い、他の部品の配置は変えない
  - 空欄は 1 として扱う。数値でない、または 1〜1,000,000 の範囲外の場合は「追加」ボタンを無効化する
  - 追加すると `(選択アイテム, 入力した個数)` が目標に登録される
- `MilestoneScreen` の目標行・記録行: count > 1 の場合は行テキストに ` ×100` を併記する（レイアウト変更なし、テキスト追加のみ）
- `SavePresetScreen` で保存するプリセットには現在の目標の個数も含める（Q5）
- プリセット適用時のフィードバックに、個数つきで登録された件数が分かるようにする（既存のフィードバック文の拡張でよい）

## Q5. プリセットとコンフィグ

### 仕様
- カスタムプリセット JSON（`config/itemmilestonelog/presets/<id>.json`）の items 要素を拡張する:
  - 従来形式の文字列 `"minecraft:diamond"` は count = 1 として読み込む（既存ファイル互換）
  - 新形式のオブジェクト `{"item": "minecraft:diamond", "count": 100}` を読み込める
  - 保存時は count > 1 の要素のみオブジェクト形式で書き出し、count = 1 は文字列で書き出す
- 組み込みプリセット（atm_star / vanilla_endgame）は全て count = 1 のままで内容不変
- `Config.defaultTargets` の各要素でも `itemId#count` 記法（Q1 と同じエンコーディング）を受け付ける。`#` 無しは count = 1。不正な要素は従来通り warn して読み飛ばす

## Q6. 世界初・通知・サウンド

### 仕様
- 世界初（F4）は (itemId, count) 単位で保持する: 「ダイヤ ×100 の世界初」と「ダイヤ ×1 の世界初」は別々に存在できる
- 記録確定時のサウンド（F3）、ブロードキャスト通知、Discord Webhook（F5）の文面には、count > 1 の場合に個数を併記する（Q3 と同じ ` ×100` 表記）
- Discord の送信条件・非同期送信などの既存要件はそのまま

## Q7. エクスポート / インポート・API

### 仕様
- export JSON（F6）: 目標と記録に count を含める。形式は既存 JSON 構造の拡張とし、count = 1 の要素では count フィールドを省略してよい
- import: count フィールドの無い旧形式ファイルは count = 1 としてマージできる。マージ規則（目標は和集合、記録は既存優先 等）は既存仕様のまま、組単位に読み替える
- API（F7）:
  - `MilestoneRecordedEvent` に count を取得できるアクセサ（例: `getCount()`）を追加する
  - `ItemMilestoneLogApi` に (itemId, count) を扱うメソッドを追加する。既存メソッドは count = 1 の省略形として動作を維持する

## Q8. ネットワーク同期

### 仕様
- `TargetEntry` と `SyncMilestonesPayload` に count を含め、目標・記録とも (itemId, count) 単位で同期する
- `MilestoneActionPayload` の add / remove / reset 等のアクションにも count を含める
- optional 登録は維持し、バニラクライアントが参加できる性質を変えない

## 3. 実装順序

1. Q1 データモデル（TargetKey、NBT 読み書き・移行）
2. Q2 検出（カウントと記録確定の拡張）
3. Q3 コマンド
4. Q8 ネットワーク同期
5. Q4 GUI
6. Q5 プリセット・コンフィグ
7. Q6 世界初・通知
8. Q7 export/import・API
9. テスト拡張と全バージョン確認

## 4. テストと完了条件

- CI のボットテストに個数シナリオを追加する:
  - ダイヤ ×5 を目標に登録 → 4個入手では記録されない → 5個目で記録される
  - ×1 と ×100 の併存登録で、100個一括入手時に両方記録される
  - 個数なし（1.0.1 形式）のセーブデータを読み込んで壊れないこと（既存の移行テストの枠組みで確認できればそれを使う）
- 完了条件: GitHub Actions が全4バージョンで成功し、4バージョン分の 1.0.1 JAR が生成されること
- README / docs の説明に「個数つき目標」と検出範囲（エンダーチェスト等は数えない）の注記を追記する

## 5. やらないこと

- 累計獲得数方式の実装
- 個数の上限を 1,000,000 より大きくすること
- GUI のレイアウト・デザイン変更（Q4 で許可した入力欄追加とテキスト併記以外）
- 1.0.0 のファイル差し替え、mod バージョンの変更（1.0.1 のまま）
