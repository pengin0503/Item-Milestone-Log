# Item Milestone Log — 追加機能 実装仕様書（v1.1.0）

この文書は、AI（または開発者）がこの文書だけを読んで実装を完遂できるように書かれた自己完結の仕様書である。対象リポジトリ: pengin0503/Item-Milestone-Log

## 0. 前提（現在の状態）

- Mod: Item Milestone Log（mod_id: `itemmilestonelog`、パッケージ: `com.pengin0503.itemmilestonelog`）
- 対応: Minecraft 1.21.1 / 26.1.2 / 26.2 / 26.3（`versions/<ver>/` 構成、共通アセットは `common/`）。全バージョンで機能は同一でなければならない
- 現行バージョン 1.0.1 は完成済みで、CurseForge / Modrinth に **Release** としてアップロード済み（プロジェクト審査中）。**1.0.1 のファイルは差し替えない。本仕様の成果物は 1.1.0 とする**（全バージョンの mod バージョンを 1.1.0 に上げる）
- 1.0.1 時点の主な機能: 数量ターゲット（item + count、同時所持カウント）、世界初記録、記録への Day + 実日付併記、マイルストーンサウンド、Discord Webhook 通知、JSON export / import、カスタムプリセット（JSON + ゲーム内保存）、他 Mod / KubeJS 向け API・イベント、GUI（MilestoneScreen / ItemPickerScreen / PresetListScreen）
- 既存の主なクラス（各バージョン配下）:
- `MilestoneData`（SavedData）: `players`（UUID → PlayerData: targets / records / carriedFromCreative / lastKnownName / firstJoinHintShown / muteBroadcasts）と `worldFirsts` を保持。26.x は Codec 方式（壊れたエントリは個別にスキップする lenient デコード）、1.21.1 は NBT 方式
- `MilestoneService` / `Tracker`: 記録の確定はここを通る（インベントリスキャン＋クラフト/精錬/拾得イベント。クラフト/精錬は次 tick の所持確認で確定する。FakePlayer は対象外、クリエイティブ・スペクテイター除外あり）
- `MilestoneCommands`: `/milestonelog`（エイリアス `/iml`、OP 不要）配下に add / addhand / remove / targets / list / playtime / preset / firsts / export / import / reset 系 / admin 系
- `Config`（サーバー Config）: `defaultTargets`、`scanIntervalTicks`、`notifyPlayer`（既定 true）、`broadcastMilestones`（既定 false）、`ignoreCreativeAndSpectator`（既定 true）、`milestoneSoundEnabled`（既定 true）+ `milestoneSound`、`announceWorldFirst`（既定 false）、`discordWebhookUrl`（空 = 無効）+ `discordNotifyScope`、`showFirstJoinHint`（既定 true）
- `network/`: `SyncMilestonesPayload` 等は **optional 登録**で、バニラクライアントも専用サーバーに参加できる
- 言語ファイル: `common/resources/assets/itemmilestonelog/lang/` の en_us / ja_jp
- CI: GitHub Actions が全バージョンをビルドし、本番 NeoForge 専用サーバーでのボットテスト（コマンド実行・記録）と 1.21.1 → 26.1.2 のワールド移行テストを実行している

## 1. 共通制約（全機能に適用）

1. **GUI のデザインは変更しない**（決定済み）。許可されるのは既存レイアウト内での表示テキストの追加・変更のみ。ボタン配置、画面構成、色は変えない
2. 既存コマンドの構文・動作は変えない。コマンドは追加のみ
3. **SavedData の後方互換を必須**とする。1.0.1 以前のセーブデータを読み込めて壊れないこと。新フィールドが欠ける場合は既定値として扱い、1.21.1 → 26.x の記録引き継ぎでも同様に継続すること
4. バニラクライアントが専用サーバーに参加できる性質を維持する（新規ペイロードを作る場合も optional 登録、サーバー側処理はクライアント Mod の有無に依存しない）
5. 追加する表示テキストは en_us / ja_jp 両方に lang キーを追加する（プレースホルダの数・順序を両言語で一致させる。ServerLangTest / LangFilesTest が検証する）
6. 追加依存ライブラリは原則なし（Minecraft 同梱の Gson は使用可）
7. 実装は全4バージョン（1.21.1 / 26.1.2 / 26.2 / 26.3）に行う。各バージョンの API 差異は既存コードの書き方に合わせる
8. **利用者向けの新機能には必ず ON/OFF の手段を用意する**。原則はサーバー Config の boolean（既定値は各機能に明記）。「設定値が空なら無効」（コマンド文字列・URL 等）や「使う側がデータパック / ターゲット設定で選ぶ」性質のものは、その旨を各機能に明記すれば Config を追加しなくてよい
9. 新機能の状態を永続化する場合は MilestoneData（PlayerData またはワールド側）に追加し、制約3の互換ルールに従う

## 2. 実施順序

T（既存機能のスイッチ追加）→ F1 → F4 → F5 → F6 → F7 → F3 → F9 → F10 → F2 → F8 の順を推奨する（小さい・独立したものから。F8 はデータモデル変更を伴う最大の変更なので最後。F8 だけ PR を分けてもよい）。各機能は相互に独立しているため、順序の入れ替え自体は可。

---

## T. 既存機能への ON/OFF スイッチ追加

現在スイッチが無い既存機能に Config を追加する。いずれも**既定値は現在の挙動と同じ**（アップグレードで挙動が変わらないこと）。

- `presetsEnabled`（boolean、既定 true）: false のとき、カスタムプリセットの保存・適用・削除とプリセット一覧を無効化する（コマンドは「無効化されている」旨のメッセージを返す。組み込みプリセットの適用も含めて無効。既存のプリセットファイルやデータは削除しない）
- `exportImportEnabled`（boolean、既定 true）: false のとき export / import コマンド（F3 の exporthtml も含む）を無効化する
- `worldFirstEnabled`（boolean、既定 true）: false のとき、世界初の**記録自体**を行わない（既存の世界初データは保持するが新規登録しない。`announceWorldFirst` は従来通りアナウンスのみの制御として残す。firsts の閲覧は可能）
- Mod API / イベントは「他 Mod が使う側が opt-in する」性質のため、スイッチは追加しない（この節の対象外であることを明記して完了とする）

実装メモ: 無効化メッセージ用に lang キー `message.itemmilestonelog.feature_disabled`（en: "This feature is disabled on this server." / ja: 「この機能はサーバーで無効化されています。」）を追加し、各機能で使い回す。

---

## F1. 数量ターゲットの進捗アラート

### 仕様
- 数量ターゲット（count > 1）について、同時所持数の達成率がしきい値を**初めて**超えたとき、達成した本人のみにチャット通知する
- 文面: `message.itemmilestonelog.progress_alert`（%s = ターゲット表示名、%s = 現在数、%s = 目標数、%s = 達成率%）。例: "Progress: Diamond — 900 / 1,000 (90%)"
- Config:
- `progressAlerts`（boolean、既定 true）
- `progressAlertThresholds`（整数リスト、既定 [50, 90]）: 1〜99 の範囲。各しきい値につき1回だけ通知する
- 通知済み状態は PlayerData に永続化する（ターゲットごとに「通知済みの最大しきい値」）。再ログインやサーバー再起動で再通知しない。ターゲットを削除して再追加した場合はリセットしてよい
- 判定タイミング: 所持数が増える経路（スキャン、拾得、クラフト/精錬の確定）で進捗が再計算されるたび。記録確定（100%）時は従来の達成通知があるのでアラートは出さない
- `notifyPlayer` とは独立に ON/OFF できること（progressAlerts が唯一の制御）

### 実装メモ
- PlayerData に `Map<TargetKey, Integer> progressNotified`（値 = 通知済み最大しきい値）を追加。26.x Codec / 1.21.1 NBT の両方で欠落時は空として読む
- 所持数の算出は既存の `Tracker.countItemsInPossession` を使う。新規の所持数え直しを増やさない（スキャンの結果を再利用する）

---

## F2. Advancement 用カスタムトリガー

### 仕様
- NeoForge の CriterionTrigger `itemmilestonelog:milestone_recorded` を登録する。データパック / パック製作者が advancement JSON から参照できる
- 条件（任意項目）: `item`（アイテム ID 文字列）、`count`（ターゲットの数量。省略時は任意）
- advancement の例（仕様書に記載するのみ。Mod 本体には同梱しない）:
```json
{ "criteria": { "got_diamond": {
"trigger": "itemmilestonelog:milestone_recorded",
"conditions": { "item": "minecraft:diamond"}}}}
```
- 発火タイミング: 記録確定時（`Tracker.record` 経由）、達成したプレイヤーに対してのみ
- Config は追加しない（データパックが存在しなければ何も起きない = 使う側の opt-in。共通制約8の例外として明記）

### 実装メモ
- トリガーの登録方法はバージョンごとに既存の NeoForge の流儀に合わせる（1.21.1 と 26.x で CriterionTrigger の登録 API が異なる点に注意）
- 条件の照合は TargetKey（itemId / count）に対して行う。F8 のタグターゲットが対象の場合は itemId がタグ形式（`#...`）のまま渡す

---

## F3. HTML サマリー出力

### 仕様
- コマンド追加: `/milestonelog exporthtml [player]`（引数なし = 自分。動作・権限は既存の export と同じ。`exportImportEnabled` が false のときは T の無効化メッセージを返す）
- 出力先: 既存 export と同じ exports ディレクトリに、単体で開ける HTML ファイル（外部リソース参照なし、CSS はインライン）を書き出す
- ファイル名は既存の `sanitizePlayerFileName` の規則に従い、拡張子を `.html` とする（全体版は `all-players.html`）
- 内容: プレイヤー名、生成日時、記録の一覧表（アイテム、数量、プレイ時間、Day、日付、世界初の印）、ターゲット達成率のサマリー
- HTML エスケープを必須とする（プレイヤー名・プリセット名等、外部由来の文字列を埋め込む箇所すべて）
- 失敗時（IO エラー等）は既存 export と同じ失敗メッセージ経路を使う（例外詳細をプレイヤーに出さない、という既存方針を維持）

### 実装メモ
- JSON export（MilestoneExport）のデータ取得を再利用し、HTML 生成は別メソッドに分ける。テンプレートは文字列組み立てでよい（追加依存なし）

---

## F4. Discord 通知の Embed 化

### 仕様
- Config: `discordUseEmbeds`（boolean、既定 true）。false のときは従来通りのプレーンテキストで送る
- Embed の構成（true のとき）:
- title: アイテム表示名（サーバー側の英語名フォールバックでよい）
- description または fields: プレイ時間、Day、日付、数量ターゲットなら数量
- color: 通常記録 = 0x3498DB（青系）、世界初 = 0xF1C40F（金系）
- footer: プレイヤー名
- 既存の挙動（Webhook URL が空 = 無効、discordNotifyScope、429 はドロップ、有界キュー、allowed_mentions なし）は変更しない

### 実装メモ
- DiscordNotifier の JSON 組み立てに embeds 配列の分岐を追加する。プレーン形式の既存テスト・送信経路は壊さない

---

## F5. プリセット制覇アナウンス

### 仕様
- プレイヤーが、あるプリセット（組み込み + カスタム）の**全ターゲットを記録し終えた**瞬間に制覇として扱う
- 判定対象は「そのプレイヤーの有効ターゲットにプリセットの全ターゲットが含まれ、かつ全て記録済み」のプリセット。判定は記録確定のたびに行う
- 制覇の通知はプリセットごとに1回だけ（PlayerData に制覇済みプリセット ID の集合を永続化する）
- 通知:
- 本人へは `notifyPlayer` が true のとき常に通知: `message.itemmilestonelog.preset_completed`（%s = プリセット表示名）
- 全体アナウンスは Config `announcePresetCompletion`（boolean、既定 false）が true のときのみ。他プレイヤーへの配信は通常ブロードキャストと同じく muteBroadcasts を尊重し、本人は常に対象
- 世界初と同時に起きた場合は両方通知してよい
- `presetsEnabled` が false のときは判定・通知を行わない

### 実装メモ
- PlayerData に `Set<String> completedPresets` を追加（互換ルールに従う）
- プリセットのターゲット集合は Presets の既存 API から取得する。カスタムプリセットが後から変更された場合の再判定は、制覇済み集合に無いプリセットについてのみ行えばよい

---

## F6. 他プレイヤーの記録を見るコマンド（lookup）

### 仕様
- コマンド追加: `/milestonelog lookup <player>`（対象は過去に参加したことのあるプレイヤー。名前解決は既存の admin 系コマンドと同じ方法を使う）
- 出力は本人の `/milestonelog list` と同じ形式（アイテム、プレイ時間、Day、日付、世界初の印）
- 対象のデータが無い場合は「記録なし」の既存メッセージ相当を返す
- Config: `allowLookup`（boolean、既定 true）
- false のとき、lookup は権限レベル2以上でのみ使用可（一般プレイヤーには T の無効化メッセージ）
- 自分自身の lookup は常に可

### 実装メモ
- 表示は MilestoneService の既存の記録整形を再利用する。対象プレイヤーがオフラインでも動作すること

---

## F7. マイルストーン達成時のコマンド実行

### 仕様
- Config:
- `milestoneCommand`（文字列、既定 "" = 無効）: 記録確定時にサーバーコンソール権限で実行するコマンド（先頭の `/` は付けない形式で記述）
- 置換プレースホルダ: `%player%`（プレイヤー名）、`%uuid%`、`%item%`（アイテム ID）、`%count%`（ターゲット数量）、`%playtime%`（整形済みプレイ時間）、`%worldfirst%`（`true` / `false`）
- 例: `give %player% minecraft:firework_rocket 3`
- 実行は記録確定の処理をブロックしない範囲でサーバースレッドから行い、失敗しても記録処理に影響させない（エラーはサーバーログに残す）
- セキュリティ上の注意を Config のコメントに明記する: この値はサーバー所有者のみが設定でき、プレースホルダのうちプレイヤー名は Mojang の命名規則で検証済み、アイテム ID は Mod 内で検証済みの値のみが入る

### 実装メモ
- 実行は `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd)` 相当を使う（バージョン差異は既存コードに合わせる）
- 世界初かどうかは `Tracker.record` の戻り経路で分かる値を渡す

---

## F8. タグ指定ターゲット

### 仕様
- ターゲット指定で、アイテム ID の代わりに **アイテムタグ** を書けるようにする
- 記法: `#namespace:path`（先頭に `#`）。例: `#c:ingots/iron`、個人ターゲット追加・defaultTargets・プリセット JSON・export / import のすべてで同じ記法を使う
- 意味: そのタグに属するアイテムの所持数を合算して同時所持カウントする（数量ターゲットの意味論は従来通り）
- 記録・世界初・進捗はタグ単位（TargetKey の itemId が `#...` 形式のキーとして扱われる）
- 検証: タグが存在し、サーバー起動時点で空でないこと。存在しない / 空のタグは無効として弾く（Config 検証・プリセット読み込み・コマンド追加の各経路で、理由をログまたはメッセージに出す）
- 表示:
- チャット / GUI の表示名はタグ ID そのもの（`#c:ingots/iron`）を基本とし、lang に `tag.itemmilestonelog...` のようなキーは作らない
- GUI のアイコンはタグの最初のアイテム（クライアント側でタグを解決して描画。解決できない場合はバリアアイコンの既存フォールバック）
- 後方互換: 従来のアイテム ID 形式のターゲット・記録・プリセットは一切変更しない。`#` で始まる文字列は従来は無効だったため衝突しない

### 実装メモ
- TargetKey の検証（isValidItemId）に `#` 形式の分岐を追加する。エンコード形式（`id#count`）との曖昧さに注意: タグ形式は `##c:ingots/iron#100` のようにならないよう、パース順序を仕様として固定する（**先頭の `#` はタグ印、数量の `#` は最後の `#` 以降**として解釈する。tryParseEncoded の単体テストを必ず追加する）
- 所持数カウント（Tracker.countItemsInPossession）は、要求された ID 集合にタグが含まれる場合、そのタグのアイテム集合を展開してから数える。タグの解決はサーバーのレジストリ（BuiltInRegistries.ITEM のタグ）から行い、スキャンのたびに再解決しない（起動時 + reload 時にキャッシュ）
- SyncMilestonesPayload は itemId 文字列を運ぶだけなので形式変更は不要。クライアント側のアイコン解決のみ追加実装が要る
- 実装規模が大きいため、本機能のみ別 PR に分けてよい

---

## F9. Discord 週次ダイジェスト

### 仕様
- Config:
- `discordDigestEnabled`（boolean、既定 false）
- `discordDigestIntervalDays`（int、既定 7、範囲 1〜30）
- 動作: 前回送信から間隔日数が経過していたら、期間内の新規記録と世界初をまとめて1通の Discord 通知を送る
- 期間の判定には記録の epochMillis を使う。前回送信時刻は MilestoneData（ワールド側）に永続化する
- 期間内の記録が0件なら送らない（時刻だけ更新する）
- 送信チェックはサーバー起動時と、稼働中は1時間に1回程度の低頻度で行う（tick カウンタで間引く）
- Webhook URL が空、または discordDigestEnabled が false のときは何もしない
- 文面は F4 の Embed 設定に従う（Embed 時はダイジェスト用 Embed 1件に箇条書きを入れる）。件数が多い場合は先頭20件 + 「他 N 件」とする

### 実装メモ
- MilestoneData に `lastDigestEpochMillis`（long、欠落時 0）を追加
- 送信は DiscordNotifier の既存キュー・送信経路を再利用する

---

## F10. セーブデータの自動バックアップ

### 仕様
- Config:
- `autoBackupEnabled`（boolean、既定 true）
- `autoBackupGenerations`（int、既定 5、範囲 1〜20）
- 動作: MilestoneData の実ファイルを、ワールド保存のタイミングでバックアップする
- 対象ファイル: 1.21.1 は `data/itemmilestonelog_data.dat`、26.x は `data/itemmilestonelog/milestones.dat`
- 保存先: `config/itemmilestonelog/backups/` に `milestones-YYYYMMDD-HHmmss.dat` の名前でコピーし、世代数を超えた古いものから削除する
- 頻度制限: 最短1時間に1回まで（ファイルの更新時刻で判定）。加えてサーバー停止時にも1回試みる（頻度制限の対象外）
- 失敗してもサーバー処理に影響させない（ログのみ）

### 実装メモ
- トリガーは LevelEvent.Save（overworld のみ）+ ServerStoppingEvent を使う。コピーはファイルサイズが小さいためサーバースレッドで同期的に行ってよい
- バックアップからの復元は手動（ファイルを戻す）でよい。復元コマンドは作らない

---

## 3. テスト・検証（全機能共通）

- 追加・変更した純粋ロジック（パース、検証、置換、整形、しきい値判定）には単体テストを追加する。特に F8 の TargetKey パースは必須
- lang キーの追加は ServerLangTest / LangFilesTest が通ること（en / ja のキー集合とプレースホルダの一致）
- CI の既存ボットテスト（記録・コマンド・移行）が全バージョンで通ること。F8 を実装した場合は、タグターゲットでの記録がボットテストで1ケース確認できることが望ましい
- バージョン表記を 1.1.0 に上げる（gradle.properties 等、1.0.1 へ上げたときと同じ箇所）
