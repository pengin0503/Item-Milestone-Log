# Item Milestone Log — 追加機能 実装仕様書（v1.0.1）

この文書は、AI（または開発者）がこの文書だけを読んで実装を完遂できるように書かれた自己完結の仕様書である。対象リポジトリ: pengin0503/Item-Milestone-Log

## 0. 前提（現在の状態）

- Mod: Item Milestone Log（mod_id: `itemmilestonelog`、パッケージ: `com.pengin0503.itemmilestonelog`）
- 対応: Minecraft 1.21.1 / 26.1.2 / 26.2 / 26.3（`versions/<ver>/` 構成、共通コードは `common/`）。全バージョンで機能は同一でなければならない
- 現行バージョン 1.0.0 は完成済みで、CurseForge に Beta として提出済み（審査中）。**1.0.0 のファイルは差し替えない。本仕様の成果物は 1.0.1 とする**（全バージョンの `gradle.properties` 等の mod バージョンを 1.0.1 に上げる）
- 既存の主なクラス（各バージョン配下）:
  - `MilestoneData`（SavedData）: NBT 名 `itemmilestonelog_data`。構造は `players` → UUID ごとの compound に `targets`（文字列リスト）、`records`（itemId → int の playTicks）、`carriedFromCreative`（文字列リスト）
  - `MilestoneService` / `Tracker`: 記録の確定はここを通る（インベントリスキャン＋クラフト/精錬/拾得イベント、クリエイティブ・スペクテイター除外あり）
  - `MilestoneCommands`: `/milestonelog`（エイリアス `/iml`、OP 不要）配下に add / addhand / remove / targets / list / playtime / preset / reset / resetall
  - `Config`: `defaultTargets`、`scanIntervalTicks`、`notifyPlayer`（既定 true）、`broadcastMilestones`（既定 false）、`ignoreCreativeAndSpectator`（既定 true）
  - `network/`: `RequestSyncPayload`、`SyncMilestonesPayload`、`MilestoneActionPayload`、`TargetEntry`。ペイロードは **optional 登録**で、バニラクライアントも専用サーバーに参加できる
  - `client/`: `MilestoneScreen`、`ItemPickerScreen`、`ConfirmResetScreen`（GUI 実装済み）
  - 言語ファイル: en_us / ja_jp
- CI: GitHub Actions が全バージョンをビルドし、本番 NeoForge 専用サーバーにリリース JAR を載せてボットテスト（コマンド実行・記録・移行）を実行している

## 1. 共通制約（全機能に適用）

1. **GUI のデザインは変更しない**（決定済み）。許可されるのは既存レイアウト内での表示テキストの追加・変更のみ。ボタン配置、画面構成、色は変えない
2. 既存コマンドの構文・動作は変えない。コマンドは追加のみ
3. **SavedData の後方互換を必須**とする。1.0.0 のセーブデータを読み込めて壊れないこと。1.21.1 → 26.x の既存の記録引き継ぎ（マイグレーション）でも新フィールドが欠ける場合は「不明」として扱い、引き継ぎ自体は継続すること
4. バニラクライアントが専用サーバーに参加できる性質を維持する（新規ペイロードを作る場合も optional 登録、サーバー側処理はクライアント Mod の有無に依存しない）
5. 追加する表示テキストは en_us / ja_jp 両方に lang キーを追加する
6. 追加依存ライブラリは原則なし（Minecraft 同梱の Gson は使用可）
7. 実装は全4バージョン（1.21.1 / 26.1.2 / 26.2 / 26.3）に行う。各バージョンの API 差異は既存コードの書き方に合わせる

## 2. 実施順序

F1 → F4 → F3 → F5 → F6 → F7 の順に実装する（F1 がデータ形式の基盤、F4 は F1 の記録構造に依存）。F8 はコード外の参考情報。F9〜F14（追加採用項目）は相互に独立しているので任意の順でよいが、F12 は ServerLang の変更を含むため早めに行うこと。

---

## F1. 記録に「ゲーム内 Day」と「現実の日付」を併記する

### 仕様
- 記録1件を次の3値で表す構造に拡張する（例: `MilestoneRecord` レコード型を新設）:
  - `playTicks`（int）: 累計プレイ時間（従来通り、バニラ統計 PLAY_TIME）
  - `day`（long）: 記録時点のワールド経過日数。`floor(記録時点の overworld の getDayTime() / 24000)`。表示時は **Day = day + 1**（ワールド開始日を Day 1 とする）
  - `epochMillis`（long）: 記録時点の現実時刻（サーバーの `System.currentTimeMillis()`）
  - 旧データ等で値が無い場合は day = -1、epochMillis = 0 を「不明」とし、表示では時刻部分のみ出す（Day・日付は省略する）
- 表示:
  - `/milestonelog list` の各行: `アイテム名 — 27h 15m 30s (Day 12, 2026-11-22)` の形式（Day・日付は判明している場合のみ括弧内に追加）
  - GUI の記録表示: 既存の行テキストに同じ括弧書きを追加するのみ（レイアウト変更なし）
  - 日付の表示形式は `yyyy-MM-dd`、タイムゾーンはサーバー（シングルプレイ時はそのマシン）のデフォルト

### 実装メモ
- NBT: `records` の各値を int から compound `{ "ticks": int, "day": long, "epoch": long }` に変更する。**ロード時は両形式に対応**すること（値が int の旧形式は ticks のみとして読む）
- `SyncMilestonesPayload` の records の型を新構造に合わせて変更する（通信相手は同一 Mod 同士のみなのでペイロード形式の互換は不要。ただし optional 登録は維持）
- GUI「記録済み」タブの並び順（playTicks 昇順）は維持する
- 記録確定の入口（MilestoneService / Tracker）で day と epochMillis を採取する

## F2. （対象外）

導入前アイテムの「推定」マークは **実装しない**（オーナー判断で除外済み）。

## F3. 初取得時のサウンド演出

### 仕様
- 記録が確定したとき、達成した本人のみに音を鳴らす（サーバー側から `ServerPlayer#playNotifySound` 等、バニラの音イベントを使う。他 Mod の音源は使わない＝バニラクライアントでも鳴る）
- コンフィグ追加:
  - `milestoneSoundEnabled`（boolean、既定 true）
  - `milestoneSound`（文字列、既定 `"minecraft:ui.toast.challenge_complete"`）: 効果音の ID。存在しない ID の場合は鳴らさず warn ログ1回のみ
- `notifyPlayer`（チャット通知）とは独立に ON/OFF できること

## F4. サーバー内「世界初」記録

### 仕様
- そのワールドで **誰かが最初に記録したアイテム** について、ワールド全体で1件だけ「世界初」記録を保持する:
  - 保持する値: itemId、プレイヤー UUID、プレイヤー名（記録時点）、playTicks、day、epochMillis（F1 の構造を流用）
  - 判定タイミング: 通常の記録確定時。その itemId の世界初がまだ無ければ登録する
- プレイヤー個人の `reset` / `resetall` では世界初は消えない（世界の歴史として保持）
- 管理用コマンド（**権限レベル2必須**）:
  - `/milestonelog firsts reset <item>`: 指定アイテムの世界初を削除
  - `/milestonelog firsts resetall`: 世界初を全削除（実行前に引数なしでは動かさない通常のコマンド確認は不要だが、実行結果をチャットに返す）
- 閲覧コマンド（OP 不要）:
  - `/milestonelog firsts`: 世界初の一覧をチャットに表示（アイテム名、プレイヤー名、playtime、Day、日付。F1 と同じ表示形式）
- 通知: コンフィグ `announceWorldFirst`（boolean、既定 false）。true のとき、世界初が登録されたら全オンラインプレイヤーにチャット通知する
- GUI への表示追加はしない（コマンドのみ）

### 実装メモ
- `MilestoneData` に `worldFirsts`（itemId → 構造体）を追加し、NBT キー `worldFirsts` で保存する。1.0.0 のセーブには無いので空で開始（後方互換）
- 1.21.1 → 26.x のマイグレーション対象に worldFirsts も含める

## F5. Discord Webhook 通知

### 仕様
- コンフィグ追加:
  - `discordWebhookUrl`（文字列、既定 `""`）: 空なら無効。**この URL は秘密情報として扱い、ログ・チャット・エラーメッセージに出力しない**
  - `discordNotifyScope`（文字列、既定 `"all"`）: `"all"` = 全員の全記録、`"world_first"` = 世界初のみ
- 送信内容: Discord Webhook の JSON `{ "content": "..." }` 形式のみ（embeds は使わない）。content にはプレイヤー名、アイテム ID、整形済み playtime、Day、日付を含める。世界初の場合はその旨を明記する
- 実装要件:
  - `java.net.http.HttpClient` を使用し、**専用の単一スレッド Executor で非同期送信**する。サーバーティックをブロックしない
  - 送信失敗時は warn ログのみ（URL は出さない）。リトライしない
  - サーバー停止時に Executor を shutdown する
  - 送信は記録確定イベントからのみ行う（import では送らない）

## F6. 記録のエクスポート / インポート

### 仕様
- コマンド（本人のデータが対象、OP 不要）:
  - `/milestonelog export`: 自分の目標と記録を JSON ファイルに書き出し、保存先パスをチャットで知らせる
    - 保存先: ワールドフォルダ配下 `itemmilestonelog/exports/<プレイヤー名>.json`（同名があれば上書き）
  - `/milestonelog import <fileName>`: 同フォルダ内の JSON を読み込んで自分のデータにマージする
    - マージ規則: 目標は和集合。記録は itemId ごとに **playTicks が小さい方** を採用し、day / epochMillis は採用した側の値を保持する
    - インポートでは F7 のイベントを発火しない。世界初（F4）も更新しない。Discord 通知（F5）も送らない
- OP 用コマンド（権限レベル2）:
  - `/milestonelog exportall`: 全プレイヤー分を1ファイル `itemmilestonelog/exports/all-players.json` に書き出す
- JSON スキーマ（export / import 共通、Gson 使用）:

```json
{
  "format": 1,
  "player": { "uuid": "…", "name": "…" },
  "targets": ["minecraft:diamond"],
  "records": {
    "minecraft:diamond": { "ticks": 123456, "day": 11, "epoch": 1795600000000 }
  }
}
```

  - `exportall` の場合はトップレベルを `{ "format": 1, "players": [ 上記 player オブジェクト相当の配列 ] }` とする（import は単一プレイヤー形式のみ対応でよい）
  - `format` が想定外の値ならインポートを中断し、エラーをチャットに返す
- セキュリティ: `<fileName>` はサニタイズする（使用可能文字は英数字・`.`・`_`・`-` のみ、`..` を含むものは拒否）。exports フォルダ外は読み書きしない

## F7. 記録イベント API（他 Mod / KubeJS 連携）

### 仕様
- `api` サブパッケージに公開 API を新設する（サーバー側のみ）:
  - `MilestoneRecordedEvent`: NeoForge の `EVENT_BUS` に投稿されるイベント（キャンセル不可）。保持する値: `ServerPlayer`、itemId、playTicks、day、epochMillis。発火タイミングは **通常の記録確定時のみ**（import・reset では発火しない）
  - `ItemMilestoneLogApi`: 読み取り専用の静的メソッド
    - `getRecord(MinecraftServer server, UUID player, String itemId)` → 記録（F1 の構造体）の Optional
    - `getTargets(MinecraftServer server, UUID player)` → 目標 itemId の Set（個人目標＋デフォルト目標の実効値ではなく、保存されている個人目標を返す。デフォルト目標の解決は呼び出し側の責務とし、その旨を Javadoc に明記）
    - `getWorldFirst(MinecraftServer server, String itemId)` → 世界初記録の Optional
- README に利用例を記載する（Java のイベント購読例と、KubeJS から NeoForge イベントを購読する例）

## F8. Modrinth 公開（コード外・参考）

- 本仕様の実装対象外（オーナーが別途対応）。メモのみ:
  - JAR は CurseForge と同じものを流用できる
  - プロジェクトのメタデータ（概要・説明・ライセンス文）はリポジトリの `CURSEFORGE.md` を再利用する

---

## F9. 管理者（OP）向けコマンド

コード調査で判明した不足への対応。全コマンドが自分のデータ専用で、他プレイヤーの記録の確認・削除や、抜けたプレイヤーのデータ整理ができない。

### 仕様
- 前提として `MilestoneData.PlayerData` に `lastKnownName`（最後に参加したときのプレイヤー名）を追加する。参加時に更新し、NBT に保存する（旧データには無いので空で開始する後方互換）
- 追加コマンド（すべて **権限レベル2必須**、対象はオフラインのプレイヤーも可。名前は保存データのあるプレイヤーから補完候補を出す）:
  - `/milestonelog admin players`: データを持つ全プレイヤーを、名前・目標数・記録数つきで一覧表示
  - `/milestonelog admin <player> list`: そのプレイヤーの記録一覧（本人の `list` と同じ表示形式）
  - `/milestonelog admin <player> targets`: そのプレイヤーの目標一覧
  - `/milestonelog admin <player> reset <item>` / `resetall`: そのプレイヤーの記録をリセット
  - `/milestonelog admin <player> purge`: そのプレイヤーのデータを丸ごと削除（目標・記録・付随フラグ）。実行したことをサーバーログに残す
- 対象プレイヤーがオンラインの場合、そのプレイヤーの GUI が開いていれば既存の再同期で表示も更新されること
- GUI への追加はしない（コマンドのみ）

## F10. 初回参加時の GUI 案内

キー未割り当てが初期状態のため、Mod 入りクライアントのプレイヤーが GUI の存在に気づけない問題への対応。

### 仕様
- プレイヤーがワールド/サーバーに初めて参加したとき、1回だけ案内をチャットに出す:
  - Mod 入りクライアント（同期チャンネルあり）: 「操作設定で "Open Milestone Log" にキーを割り当てると記録 GUI が開ける。コマンド /milestonelog でも使える」旨
  - バニラクライアント: コマンドで使える旨のみ（GUI に触れない）
- 「表示済み」は `PlayerData` のフラグで管理し、2回目以降の参加では出さない。1.0.1 導入時点で既存のプレイヤーにも初回参加時に1回だけ出る（許容する）
- コンフィグ `showFirstJoinHint`（boolean、既定 true）で無効化できる

## F11. 達成ブロードキャストの個人オプトアウト

`broadcastMilestones` が ON のサーバーで、他人の達成通知を受け取る側が止める手段がない問題への対応。

### 仕様
- `PlayerData` に `muteBroadcasts`（boolean、既定 false = 受け取る）を追加
- コマンド `/milestonelog mutebroadcast`（OP 不要）: 実行するたびに ON/OFF を切り替え、現在の状態をフィードバックする
- `Tracker` のブロードキャスト送信ループで、`muteBroadcasts` が true のプレイヤーを宛先から除外する。自分の記録の本人通知（`notifyPlayer`）には影響しない
- F4 の世界初通知（`announceWorldFirst`）の宛先からも同様に除外する

## F12. バニラクライアント向け表示をそのプレイヤーの言語に合わせる

現状の整理: Mod 入りクライアントでは、メッセージは translatable コンポーネントで送られ **各プレイヤーのゲーム内言語で表示される（対応済み）**。一方バニラクライアントには Mod の言語ファイルが無いため、`ServerLang` の英語フォールバックが常に出る。本項目はこのバニラ側を改善する。

### 仕様
- サーバーは各プレイヤーのクライアント言語設定（`ServerPlayer.clientInformation().language()`、例 `ja_jp`）を受け取っている。これを使い、**プレイヤーごとに、その言語の lang ファイルをフォールバック文として選ぶ**:
  - Mod が同梱する全言語（現状 en_us / ja_jp）のフォールバック表を `ServerLang` が読み込み、キャッシュする
  - プレイヤー向けメッセージは `translatableWithFallback(key, そのプレイヤーの言語の文, args)` で構築する。バニラクライアントはフォールバック文（＝その人の言語）で表示し、引数のアイテム名コンポーネントはクライアント側で各自の言語に描画される（現行方式のまま）
  - 対応する言語ファイルが無い言語のプレイヤーには英語フォールバックを使う
- 宛先がプレイヤーでない送信（コンソール宛てのコマンド応答など）は従来通り英語でよい
- 既存の呼び出し箇所（Tracker の通知、MilestoneCommands の応答、MilestoneService のフィードバック等）を、宛先プレイヤーの言語で構築するよう置き換える。GUI のフィードバック（Mod 入りクライアント専用）は従来通りでよい

## F13. インベントリスキャンの位相分散の改善

現状の位相ずらしは UUID ハッシュの下位4ビット（16通り）で、間隔 20 tick のときに分散し切らない。

### 仕様
- 位相を `Math.floorMod(player.getUUID().hashCode(), interval)` で決める（interval 全体に分散させる）。変更は Tracker のスキャン判定1箇所のみ

## F14. 検出範囲のドキュメント明記

- README（および CURSEFORGE.md の説明）に、検出対象は「プレイヤーのインベントリに入った時点」であり、**エンダーチェスト・シュルカーボックスの中身・他 Mod のストレージ内に直接入ったアイテムは、インベントリに取り出すまで記録されない**ことを明記する。既に同等の記載がある場合は重複させない

---

## 3. ドキュメント更新

- `README.md`: 新機能、新コマンド（firsts / export / import / exportall / admin 系 / mutebroadcast）、新コンフィグ、F7 の API 利用例を追記。F14 の検出範囲の明記もここで行う
- `CHANGELOG.md`: 1.0.1 の節を追加
- `CURSEFORGE.md`: 説明文への追記案（新機能の箇条書き、英語＋日本語）を反映
- lang: 追加メッセージはすべて en_us / ja_jp 両方

## 4. テストと完了条件

- 既存 CI のボットテストに以下を追加する:
  - F1: 記録に day / epoch が保存されること。**1.0.0 形式のセーブデータ（records が int 形式）を読み込んで起動できること**
  - F4: 2人目のプレイヤーが同じアイテムを記録しても世界初が上書きされないこと。`/milestonelog firsts` で表示されること
  - F6: export → resetall → import で記録が復元されること
  - F5: Webhook URL 未設定で記録しても例外なく通常動作すること（実送信のテストはしない）
  - F9: OP のボットが他プレイヤーの記録を list で見て、reset と purge ができること。非 OP では admin 系が拒否されること
  - F10: 初回参加時に案内が1回だけ出て、再参加では出ないこと
  - F11: mutebroadcast を有効にしたプレイヤーに、他人の達成ブロードキャストが届かないこと
  - F12: 言語が ja_jp の（バニラ相当の）宛先には日本語フォールバックが、en_us には英語が選ばれること（フォールバック選択の単体確認で可）
- 完了条件: GitHub Actions で **全4バージョンのビルドとサーバーテストが成功**し、バージョン 1.0.1 の JAR が4つ生成されること。既存テストを壊さないこと
