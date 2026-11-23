# Item Milestone Log — 追加機能 実装仕様書（v1.1.0）

この文書は、AI（または開発者）がこの文書だけを読んで実装を完遂できるように書かれた自己完結の仕様書である。対象リポジトリ: pengin0503/Item-Milestone-Log

> **改訂（2026-11-23、設計レビューの反映）**: 初版の設計上の不備・矛盾を修正した。修正した箇所には 〔X番号〕 を付けている。各修正の理由と根拠（コードの位置・確認した内容）は末尾の「付録A」にまとめた。採用しない修正は、その箇所を初版の記述に戻せばよい（git の差分で初版と比較できる）。
>
> 追加機能の**候補**（未採用）は別文書 `docs/FEATURE_PROPOSALS_1.1.0.md` にある。**候補は採用が決まって本書に移されるまで実装しない。**

## 0. 前提（現在の状態）

- Mod: Item Milestone Log（mod_id: `itemmilestonelog`、パッケージ: `com.pengin0503.itemmilestonelog`）
- 対応: Minecraft 1.21.1 / 26.1.2 / 26.2 / 26.3（`versions/<ver>/` 構成、共通アセットは `common/`）。全バージョンで機能は同一でなければならない
- 現行バージョン 1.0.1 は完成済みで、CurseForge / Modrinth に **Release** としてアップロード済み（プロジェクト審査中）。**1.0.1 のファイルは差し替えない。本仕様の成果物は 1.1.0 とする**（全バージョンの mod バージョンを 1.1.0 に上げる）
- 1.0.1 時点の主な機能: 数量ターゲット（item + count、同時所持カウント）、世界初記録、記録への Day + 実日付併記、マイルストーンサウンド、Discord Webhook 通知、JSON export / import、カスタムプリセット（JSON + ゲーム内保存）、他 Mod / KubeJS 向け API・イベント、GUI（MilestoneScreen / ItemPickerScreen / PresetListScreen）
- 既存の主なクラス（各バージョン配下）:
- `MilestoneData`（SavedData）: `players`（UUID → PlayerData: targets / records / carriedFromCreative / lastKnownName / firstJoinHintShown / muteBroadcasts）と `worldFirsts` を保持。26.x は Codec 方式（壊れたエントリは個別にスキップする lenient デコード）、1.21.1 は NBT 方式
- `MilestoneService` / `Tracker`: 記録の確定はここを通る（インベントリスキャン＋クラフト/精錬/拾得イベント。クラフト/精錬は次 tick の所持確認で確定する。FakePlayer は対象外、クリエイティブ・スペクテイター除外あり）
- `MilestoneCommands`: `/milestonelog`（エイリアス `/iml`、OP 不要）配下に add / addhand / remove / targets / list / playtime / preset / firsts / export / import / reset 系 / admin 系
- `Config`（`ModConfigSpec`。1.21.1 / 26.1.2 / 26.2 は `ModConfig.Type.COMMON`、26.3 は `ModConfig.Type.LOCAL`。シングルプレイでは全ワールド共通の1ファイル）〔X1〕: `defaultTargets`、`scanIntervalTicks`、`notifyPlayer`（既定 true）、`broadcastMilestones`（既定 false）、`ignoreCreativeAndSpectator`（既定 true）、`milestoneSoundEnabled`（既定 true）+ `milestoneSound`、`announceWorldFirst`（既定 false）、`discordWebhookUrl`（空 = 無効）+ `discordNotifyScope`、`showFirstJoinHint`（既定 true）
- `network/`: `SyncMilestonesPayload` 等は **optional 登録**（`PROTOCOL_VERSION` = `"1"`）で、バニラクライアントも専用サーバーに参加できる
- 言語ファイル: `common/resources/assets/itemmilestonelog/lang/` の en_us / ja_jp
- CI: GitHub Actions が全バージョンをビルドし、本番 NeoForge 専用サーバーでのボットテスト（コマンド実行・記録）と 1.21.1 → 26.1.2 のワールド移行テストを実行している
- 1.0.1 の既知の不備（本書の G 節で修正する）〔X4〕〔X5〕: `MilestoneService.canManagePresets` は統合サーバー（シングルプレイ・LAN）で常に true を返すため、LAN のゲストもホストのプリセットを保存・削除できる。また GUI からの保存は既存のカスタムプリセットを確認なしで上書きし、手書きの表示名（JSON の `name`）を ID で置き換える

## 1. 共通制約（全機能に適用）

1. **GUI のデザインは変更しない**（決定済み）。許可されるのは既存レイアウト内での表示テキストの追加・変更のみ。ボタン配置、画面構成、色は変えない
2. 既存コマンドの構文・動作は変えない。コマンドは追加のみ
3. **SavedData の後方互換を必須**とする。1.0.1 以前のセーブデータを読み込めて壊れないこと。新フィールドが欠ける場合は既定値として扱い、1.21.1 → 26.x の記録引き継ぎでも同様に継続すること
4. バニラクライアントが専用サーバーに参加できる性質を維持する（新規ペイロードを作る場合も optional 登録、サーバー側処理はクライアント Mod の有無に依存しない）
5. 追加する表示テキストは en_us / ja_jp 両方に lang キーを追加する（プレースホルダの数・順序を両言語で一致させる。ServerLangTest / LangFilesTest が検証する）。リテラルの `%` は `%%` と書く
6. 追加依存ライブラリは原則なし（Minecraft 同梱の Gson は使用可）
7. 実装は全4バージョン（1.21.1 / 26.1.2 / 26.2 / 26.3）に行う。各バージョンの API 差異は既存コードの書き方に合わせる
8. **利用者向けの新機能には必ず ON/OFF の手段を用意する**。原則は**既存の `Config` クラス**への boolean の追加とする（既存と同じくセクション分けせずトップレベルに追加する。新しい `ModConfig` の登録や、`SERVER` / 26.3 の `SYNCED` 型の設定ファイルは作らない）〔X1〕。「設定値が空なら無効」（コマンド文字列・URL 等）や「使う側がデータパック / ターゲット設定で選ぶ」性質のものは、その旨を各機能に明記すれば Config を追加しなくてよい
9. 新機能の状態を永続化する場合は MilestoneData（PlayerData またはワールド側）に追加し、制約3の互換ルールと §1.1 の保存形式に従う 〔X3〕
10. **ネットワーク互換** 〔X2〕: 既存ペイロード（`RequestSyncPayload` / `SyncMilestonesPayload` / `MilestoneActionPayload`）のワイヤ形式、`MilestoneActionPayload.Action` の列挙値、`PROTOCOL_VERSION` を変更しない。クライアントへ追加のデータを送る必要が生じた場合は、**新しい optional ペイロード型を追加**し、送信前に `player.connection.hasChannel(新しい TYPE)` を確認する（1.0.1 のクライアントにはそのチャンネルが無いので送らない）。C2S に新しい操作が必要な場合も、既存の `Action` に値を足さず新しいペイロード型にする
    - 理由: NeoForge は optional のペイロードでも、両者のバージョン文字列が異なると接続自体を拒否する。版を据え置いたまま形式を変えると、1.0.1 のクライアント / サーバーとの組み合わせでデコードに失敗して切断される（付録A X2）
11. **管理者判定の一元化** 〔X4〕: 1.1.0 で追加・修正する「管理者だけが行える操作」の判定は、新設する `MilestoneService.isPrivileged(CommandSourceStack)` / `isPrivileged(ServerPlayer)` の1か所で行う
    - プレイヤー以外のソース（コンソール・RCON・コマンドブロック等）: 権限レベル2以上なら true
    - 専用サーバーのプレイヤー: 権限レベル2以上なら true
    - 統合サーバー（シングルプレイ・LAN）のプレイヤー: **ホスト本人**（`MinecraftServer#isSingleplayerOwner` 相当、バージョンごとの API に合わせる）であるか、権限レベル2以上なら true。LAN のゲストはチートが許可されていない限り false
    - 既存コマンドの `.requires(hasPermission(2))`（admin / firsts reset 系 / exportall）は制約2により変更しない
12. **外部由来の文字列の扱い** 〔X5〕: プレイヤー名、カスタムプリセットの表示名、他 Mod のアイテム名など、利用者や外部から来る文字列は書式文字列として解釈せず `Component.literal` で埋め込む。HTML（F3）では HTML エスケープ、Discord（F4 / F9）では Markdown のエスケープ、コマンド文字列（F7）では検証を行う（各節に詳細）
13. **タグ形式のキー** 〔X14〕: F8a の実装以降、`TargetKey.itemId` は `#` で始まるタグ ID（例: `#c:ingots/iron`）になり得る。1.1.0 で追加する全機能は `TargetKey.isTag()` を考慮し、`itemId` を確認せずに `Identifier.parse` やレジストリ参照に渡さない

### 1.1 新しく永続化するフィールド 〔X3〕

1.21.1（NBT）と 26.x（Codec）で**同じキー名・同じ型**にする。1.21.1 → 26.x の移行（`MilestoneData.readLegacy`）は 26.x の Codec で 1.21.1 のファイルを読むため、キー名がずれると移行で黙って失われる。

| 場所 | NBT キー | 型 | 欠落時の値 | 書き出し | 用途 |
|---|---|---|---|---|---|
| PlayerData | `progressNotified` | compound（キー: エンコード済み TargetKey 文字列（`toEncodedString()`、例 `minecraft:diamond#1000`）、値: int） | 空 | 空なら書かない | F1 |
| PlayerData | `completedPresets` | 文字列リスト（プリセット ID） | 空 | 空なら書かない | F5 |
| MilestoneData（`players` と同じ階層） | `lastDigestEpoch` | long | 0 | 0 なら書かない | F9 |

- 26.x では新フィールドを `lenientOptionalFieldOf`（または既存コードと同じ `orElse` による要素単位の読み飛ばし）で定義し、新フィールドが壊れていてもファイル全体の読み込みを失敗させない（issue #40 と同じ方針）。1.21.1 も要素単位で読み飛ばし、warn ログを出す
- 不正なキー（TargetKey としてパースできない）や範囲外の値の要素は、要素単位で読み飛ばす
- 後片付けの規則（データが残り続けないようにする）:
  - 記録の確定、`reset <item> [count]`、GUI のリセット、`admin <player> reset`、`remove`: そのキーの `progressNotified` を消す
  - `resetall`、GUI の全リセット、`admin <player> resetall`: `progressNotified` と `completedPresets` をすべて消す
  - `admin <player> purge`: PlayerData ごと消える（従来通り）
  - import: これらのフィールドを変更しない
  - ログイン時: 記録済みのキーと、実効ターゲット（個人 + `defaultTargets`）に無いキーの `progressNotified` を消す（import や `defaultTargets` の変更で残ったものを片付ける）
- ダウングレード（1.1.0 → 1.0.1）: 1.0.1 は知らないフィールドを読み捨て、次の保存で消す（通知済み・制覇済みの状態が失われ、1.1.0 に戻すと再び通知され得る）。**タグのターゲット・記録・世界初（F8）は 1.0.1 の検証で弾かれ、次の保存で失われる**。CHANGELOG と README に明記する

## 2. 実施順序 〔X19〕

G（1.0.1 からの不備修正）→ T（既存機能のスイッチ追加）→ **F8a（タグのデータ表現）** → F1 → F4 → F5 → F6 → F7 → F3 → F9 → F10 → F2 → **F8b（タグの検出・コマンド・表示）** の順を推奨する。

- F8 は `TargetKey` の意味（`itemId` が `#` で始まり得る）を変え、F1〜F9 のすべてがその上に乗る。データ表現（F8a）を先に入れておけば、後続の各機能は最初からタグを考慮して作れる。初版のように F8 を最後にすると、F1〜F9 を F8 のときに作り直すことになる
- F8b は規模が大きいので PR を分けてよい
- 各機能は相互に独立しているため、上記以外の順序の入れ替え自体は可

---

## G. 1.0.1 から引き継ぐ不備の修正（新設）〔X4〕〔X5〕

### G1. LAN のゲストによるプリセットの保存・削除を防ぐ
- `canManagePresets`（コマンドと GUI の両方の判定、`SyncMilestonesPayload.canManagePresets` の値）を §1 制約11の `isPrivileged` に置き換える
- 結果: 専用サーバーは従来通り権限レベル2以上。シングルプレイのホストは従来通り可能。**LAN のゲストは、ホストがチートを許可していない限り保存・削除・再読み込みできない**（適用は従来通り誰でも可）
- 理由: プリセットの保存先 `config/itemmilestonelog/presets/` はホストの全ワールド共通のフォルダで、ゲストが削除すると他のワールドでも使えなくなる
- ペイロードの形式は変わらない（制約10）
- テスト: 判定を純粋関数（専用/統合 × ホスト/ゲスト × 権限レベル × プレイヤー以外のソース）に切り出して単体テストする。CI の専用サーバーでは LAN を再現できないため

### G2. プリセットの上書き保存を明示し、表示名を保つ
- サーバー側（コマンド・GUI 共通）: 既存のカスタムプリセットを上書き保存するとき、既存ファイルに有効な文字列の `name` があれば**その `name` を引き継ぐ**（無ければ従来通り ID）。`items` は置き換える。それ以外の未知のフィールドは保持しない（README に明記）
- GUI（`SavePresetScreen`）: 入力中の ID が、直近の同期データにある既存のカスタムプリセットの ID と一致したら、
  - 「保存」ボタンのラベルを「上書き保存」に変える（`gui.itemmilestonelog.save_preset.overwrite`: en "Overwrite" / ja 「上書き保存」）
  - 入力欄の上の説明（`gui.itemmilestonelog.save_preset.name_label`）の行を、上書きの注意に置き換えて表示する（`gui.itemmilestonelog.save_preset.overwrite_notice`: en "Custom preset '%s' already exists and will be overwritten." / ja 「カスタムプリセット「%s」は既にあり、上書きされます。」、%s = 既存の表示名）
  - 組み込みプリセットの ID と一致したら「保存」ボタンを無効にする（サーバー側は従来通り拒否する）
  - いずれもテキストの変更とボタンの有効/無効だけで、レイアウトは変えない（制約1の範囲内）
- コマンド `/milestonelog preset save <name>` の動作（上書きし、上書きした旨を返す）は変えない（制約2）。表示名の引き継ぎだけが変わる
- テスト: `PresetsTest` に「`name` 付きのファイルを上書き保存しても `name` が残る」ケースを追加する

---

## T. 既存機能への ON/OFF スイッチ追加

現在スイッチが無い既存機能に Config を追加する。いずれも**既定値は現在の挙動と同じ**（アップグレードで挙動が変わらないこと）。

- `presetsEnabled`（boolean、既定 true）: false のとき、カスタムプリセットの保存・適用・削除とプリセット一覧を無効化する（コマンドは「無効化されている」旨のメッセージを返す。組み込みプリセットの適用も含めて無効。既存のプリセットファイルやデータは削除しない）
  - 〔X6〕対象: `preset <name>` / `preset list` / `preset save` / `preset delete` / `preset reload`、GUI の APPLY_PRESET / SAVE_PRESET / DELETE_PRESET（サーバー側で拒否し、無効化メッセージをフィードバックとして返す）
  - 〔X6〕GUI: 同期データのプリセット一覧を空にし、`canManagePresets` を false にして送る（「プリセット...」ボタンは残り、一覧画面は既存の「利用可能なプリセットがありません。」表示になる。ペイロード形式は変えない）
  - F5（制覇）の判定・通知も行わない
- `exportImportEnabled`（boolean、既定 true）: false のとき export / import コマンド（F3 の exporthtml も含む）を無効化する
  - 〔X6〕対象: `export` / `exportall` / `import` / `exporthtml` / `exporthtmlall`。OP も対象とする（サーバー所有者が設定で戻す）。F10 の自動バックアップはこのスイッチの影響を受けない
- `worldFirstEnabled`（boolean、既定 true）: false のとき、世界初の**記録自体**を行わない（既存の世界初データは保持するが新規登録しない。`announceWorldFirst` は従来通りアナウンスのみの制御として残す。firsts の閲覧は可能）
  - 〔X6〕連動: 新しい世界初が生まれないため、`announceWorldFirst` は何もしない。`discordNotifyScope = "world_first"` では Discord に何も送られない。F2 の `world_first: true` 条件は一致しない。F7 の `%worldfirst%` は常に `false`。`firsts reset` / `firsts resetall` は従来通り使える
- Mod API / イベントは「他 Mod が使う側が opt-in する」性質のため、スイッチは追加しない（この節の対象外であることを明記して完了とする）

実装メモ: 無効化メッセージ用に lang キー `message.itemmilestonelog.feature_disabled`（en: "This feature is disabled on this server." / ja: 「この機能はサーバーで無効化されています。」）を追加し、各機能で使い回す（`ServerLang.tr` で受信者の言語のフォールバック付きで送る）。

---

## F1. 数量ターゲットの進捗アラート

### 仕様
- 数量ターゲット（count > 1）について、同時所持数の達成率がしきい値を**初めて**超えたとき、達成した本人のみにチャット通知する
  - 〔X7〕対象は count が `progressAlertMinCount` 以上の未記録の数量ターゲット（count = 2 のターゲットで「1/2 (50%)」のような通知が出ないようにするため）
  - 〔X7〕達成率 = floor(現在数 × 100 / count)（整数、切り捨て）。数え方は Q9 の進捗表示と同じ（クリエイティブから持ち帰ったアイテム `carriedFromCreative` は数えない。記録が一時停止中（クリエイティブ・スペクテイター）は判定しない）
- 文面: `message.itemmilestonelog.progress_alert`（%s = ターゲット表示名（個数つき）、%s = 現在数、%s = 目標数、%s = 達成率）。en: "Progress: %s — %s / %s (%s%%)" / ja: 「進捗: %s — %s / %s（%s%%）」。例: "Progress: Diamond ×1000 — 900 / 1000 (90%)"。数値は既存の表示（`(current: 37)`）と同じく桁区切りなし 〔X7〕
- Config:
- `progressAlerts`（boolean、既定 true）
- `progressAlertThresholds`（整数リスト、既定 [50, 90]）: 1〜99 の範囲。各しきい値につき1回だけ通知する（重複は無視し、昇順に並べて使う）
- 〔X7〕`progressAlertMinCount`（int、既定 10、範囲 2〜1,000,000）
- 通知済み状態は PlayerData に永続化する（ターゲットごとに「通知済みの最大しきい値」、§1.1 の `progressNotified`）。再ログインやサーバー再起動で再通知しない。後片付けは §1.1 の規則に従う（ターゲットを削除して再追加した場合や、記録をリセットした場合はリセットされる）
- 〔X7〕**ベースライン**: あるキーについて `progressNotified` にエントリが無い状態で初めて評価したときは、その時点で超えている最大のしきい値（無ければ 0）を**通知せずに**記録する。以後の評価で、記録済みの値より大きいしきい値を超えたときだけ通知する
  - 理由: 1.1.0 の導入時、ターゲットの追加・プリセットの適用・import の直後、`defaultTargets` の変更後に、既に持っている分のアラートがまとめて出ないようにする
- 〔X7〕1回の評価で複数のしきい値を超えた場合は、超えた最大のしきい値について1通だけ通知し、その値を記録する
- 判定タイミング: 所持数が増える経路（スキャン、拾得、クラフト/精錬の確定）で進捗が再計算されるたび。記録確定（100%）時は従来の達成通知があるのでアラートは出さない（そのキーの `progressNotified` は消す）
- `notifyPlayer` とは独立に ON/OFF できること（progressAlerts が唯一の制御）

### 実装メモ
- PlayerData に `Map<TargetKey, Integer> progressNotified`（値 = 通知済み最大しきい値、ベースラインでは 0 もあり得る）を追加。26.x Codec / 1.21.1 NBT の両方で欠落時は空として読む（§1.1）
- 所持数の算出は既存の `Tracker.countItemsInPossession` を使う。新規の所持数え直しを増やさない（スキャンの結果を再利用する）。スキャンの `counts` は未記録ターゲットのアイテムだけを数えているので、そのまま使える
- しきい値判定（ベースライン、複数超え、範囲外の値の扱い）は純粋関数に切り出して単体テストする

---

## F2. Advancement 用カスタムトリガー

### 仕様
- NeoForge の CriterionTrigger `itemmilestonelog:milestone_recorded` を登録する。データパック / パック製作者が advancement JSON から参照できる
- 条件（すべて任意項目）〔X8〕:
  - `player`: バニラの `SimpleCriterionTrigger` と同じプレイヤー条件（`ContextAwarePredicate`）
  - `item`: TargetKey の itemId と**完全一致**する文字列。タグターゲット（F8）は `"#c:ingots/iron"` のように書く（タグへの所属は判定しない）
  - `count`: バニラの `MinMaxBounds.Ints`。数値を書けば完全一致、`{"min": 100}` のように書けば範囲。省略時は任意
  - `world_first`: boolean。その記録が世界初かどうか。省略時は任意
- advancement の例（仕様書に記載するのみ。Mod 本体には同梱しない）:
```json
{ "criteria": { "diamonds_100": {
"trigger": "itemmilestonelog:milestone_recorded",
"conditions": { "item": "minecraft:diamond", "count": { "min": 100 } }}}}
```
- 発火タイミング: 記録確定時（`Tracker.record` 経由）、達成したプレイヤーに対してのみ
- 〔X8〕**既存の記録への遡及**: プレイヤーのログイン時（`PlayerEvent.PlayerLoggedInEvent`）と `/reload` 時（`OnDatapackSyncEvent` のうちプレイヤーが null のもの。全オンラインプレイヤーが対象）に、そのプレイヤーの全記録についてトリガーを呼ぶ。データパックを後から入れたワールドでも、既に記録済みのマイルストーンの advancement が付与される。advancement は一度達成すると再付与されないので、毎回呼んでも重複しない。遡及時の `world_first` は「そのキーの世界初の保持者がそのプレイヤーか」で判定する
  - ログイン時に `OnDatapackSyncEvent` を使わない理由: NeoForge はこのイベントをログイン処理の途中（プレイヤーがプレイヤー一覧に入る前）で発火する（21.1.1 の `PlayerList` のパッチで確認）。`PlayerLoggedInEvent` はログイン処理の最後に発火する
  - データパックを後から入れた直後は、`announce_to_chat` が true の advancement の達成メッセージがまとめて流れ得る（データパック側の設定で抑えられる）。README に書く
- 記録のリセットでは advancement を取り消さない（取り消しはデータパック側や `/advancement revoke` に任せる）。README に明記する
- Config は追加しない（データパックが存在しなければ何も起きない = 使う側の opt-in。共通制約8の例外として明記）

### 実装メモ
- トリガーの登録方法はバージョンごとに既存の NeoForge の流儀に合わせる（1.21.1 と 26.x で CriterionTrigger の登録 API が異なる点に注意）
- 条件の照合は TargetKey（itemId / count）に対して行う。F8 のタグターゲットが対象の場合は itemId がタグ形式（`#...`）のまま渡す
- 世界初かどうかは `MilestoneRecordedEvent` に無いので、`Tracker.record` の中で `recordWorldFirstIfAbsent` の戻り値を使って直接トリガーを呼ぶ
- 〔X8〕バニラクライアント互換: `TRIGGER_TYPE` レジストリは NeoForge が接続時に同期するレジストリに含まれない（21.1.1 と 26.1.2.71 の `NeoForgeRegistriesSetup` で確認）ので、参加互換は保たれる見込み。ただし本 Mod が初めてレジストリに登録を行う機能なので、**CI の mineflayer（バニラクライアント）参加テストが全バージョンで通ることを完了条件にする**

---

## F3. HTML サマリー出力

### 仕様
- 〔X9〕コマンド追加（既存の export / exportall と同じ構成にする）:
  - `/milestonelog exporthtml`: 自分の記録を出力する（OP 不要）
  - `/milestonelog exporthtmlall`: 全プレイヤー分を `all-players.html` に出力する（権限レベル2、既存の exportall と同じ）
  - `exportImportEnabled` が false のときは T の無効化メッセージを返す
- 出力先: 既存 export と同じ exports ディレクトリ（`<world>/itemmilestonelog/exports/`）に、単体で開ける HTML ファイル（外部リソース参照なし、CSS はインライン、JavaScript なし）を書き出す。一時ファイル + 移動で書く（既存 export と同じ）
- ファイル名は既存の `sanitizePlayerFileName` の規則に従い、拡張子を `.html` とする（全体版は `all-players.html`。`all-players` と衝突する名前を `all-players-player` に変える既存の規則も同じく適用する）
- 内容: プレイヤー名、生成日時、記録の一覧表（アイテム名、アイテム ID、数量、プレイ時間、Day、日付、世界初の印）、ターゲット達成率のサマリー
  - 〔X9〕アイテム名はサーバー側の `Component.getString()` で得る（専用サーバーでは英語、シングルプレイ / LAN ではホストのクライアント言語になる）。名前を解決できないアイテム（Mod を外した等）は ID だけを出す
  - 〔X9〕世界初の印: そのキーの世界初の保持者 UUID が本人と一致する記録に付ける
  - 〔X9〕達成率 = 記録済みの数 / 実効ターゲット数（個人ターゲット + `defaultTargets`）。所持数の進捗は載せない（オフラインのプレイヤーは数えられないため、全員分と揃える）
  - 日付・生成日時のタイムゾーンは既存の表示と同じ（サーバーの既定）
- HTML エスケープを必須とする（プレイヤー名・プリセット名・アイテム名等、外部由来の文字列を埋め込む箇所すべて。`&` `<` `>` `"` `'` を実体参照にする）。`<meta charset="utf-8">` を入れる
- 失敗時（IO エラー等）は既存 export と同じ失敗メッセージ経路を使う（例外詳細をプレイヤーに出さない、という既存方針を維持）
- 〔X9〕専用サーバーではプレイヤーはこのファイルを開けない（サーバーのフォルダにあるため）。主にシングルプレイとサーバー所有者向けの機能であることを README に書く

### 実装メモ
- JSON export（MilestoneExport）のデータ取得を再利用し、HTML 生成は別メソッドに分ける。テンプレートは文字列組み立てでよい（追加依存なし）
- エスケープ関数とファイル名の決定は単体テストする

---

## F4. Discord 通知の Embed 化

### 仕様
- Config: `discordUseEmbeds`（boolean、既定 true）。false のときは従来通りのプレーンテキストで送る
  - 〔X10〕**判断事項**: 既定 true のため、既に Webhook を使っているサーバーでは 1.1.0 への更新で投稿の形式が変わる（T 節の「アップグレードで挙動が変わらない」方針の対象外の新機能だが、目に見える変化）。既定 true を維持する場合は CHANGELOG に明記する。従来の形式を守る場合は既定を false にする
- Embed の構成（true のとき）〔X10〕:
  - title: アイテム表示名＋個数（count > 1 のとき ` ×N`）。表示名はサーバー側の `getString()`（専用サーバーでは英語。シングルプレイ / LAN ではホストの言語）。タグターゲットは F8b の表示名の規則に従う
  - description: 通常 = `<プレイヤー名> obtained <アイテム>`、世界初 = `★ World First — <プレイヤー名> obtained <アイテム>`（従来のプレーン文面と同じ英語）
  - fields（inline）: `Playtime`（整形済みプレイ時間）、`Day`（判明時のみ）、`Date`（判明時のみ）
  - color: 通常記録 = 0x3498DB（青系）、世界初 = 0xF1C40F（金系）
  - footer: プレイヤー名
  - timestamp: 記録の epochMillis を ISO-8601 で入れる（判明時のみ。Discord が閲覧者のタイムゾーンで表示する）
- 〔X10〕Markdown のエスケープ: description・fields・プレーンテキストの content に入れるプレイヤー名・アイテム名は、Discord の Markdown 記号（`\` `*` `_` `~` `` ` `` `|` `>` `#` `-` `[` `]` `(` `)`）の前に `\` を付ける（例: `_Steve_` という名前が斜体の「Steve」にならない）。プレーンテキスト形式にも適用する
- 〔X10〕長さの上限: title 256 文字、field の値 1024 文字、description 4096 文字、プレーンテキストの content 2000 文字。超える場合は切り詰める（サロゲートペアを分割しない）
- 既存の挙動（Webhook URL が空 = 無効、discordNotifyScope、429 はドロップ、有界キュー、allowed_mentions なし）は変更しない

### 実装メモ
- DiscordNotifier の JSON 組み立てに embeds 配列の分岐を追加する。プレーン形式の既存テスト・送信経路は壊さない
- エスケープと切り詰めは純粋関数にして単体テストする

---

## F5. プリセット制覇アナウンス

### 仕様 〔X11〕
- プレイヤーが、あるプリセット（組み込み + カスタム）の**全ターゲットを記録し終えた**瞬間に制覇として扱う
- 用語: プリセット S の「判定対象キー」R(S) = S の各キーのうち、判定時点でアイテムがレジストリに存在するもの（タグは存在して空でないもの）。存在しないキーは判定から除く（プリセットの適用でスキップされるのと同じ扱い。例: ATM の Mod が入っていないワールドの `atm_star`）
- 判定: プレイヤー P がキー K を記録確定したとき、次をすべて満たすプリセット S を制覇とする
  - `presetsEnabled` が true
  - K ∈ R(S)（**今記録したキーを含むプリセットだけを判定する**）
  - R(S) が空でない（すべての Mod が無いプリセットが「0 / 0 で制覇」にならないようにする）
  - S の ID が P の `completedPresets` に無い
  - R(S) のすべてのキーが P の records にある（キーは個数まで完全一致。ダイヤ ×1 のプリセットは、ダイヤ ×100 の記録では満たされない）
- P の現在のターゲットに含まれているかは問わない（記録はターゲットだったときにしか作られない。記録後にターゲットから外しても制覇は成立する）
- 1.1.0 の導入前に条件を満たしていたプリセットや、import で条件を満たしたプリセットは通知しない（今記録したキーを含むプリセットだけを判定するため）
- 制覇の通知はプリセットごとに1回だけ（PlayerData に制覇済みプリセット ID の集合を永続化する、§1.1 の `completedPresets`）
- 通知（**本人が受け取るのは多くても1通**。下の2つのどちらかだけが本人に届き、両方とも当てはまらなければ届かない）:
  - Config `announcePresetCompletion`（boolean、既定 false）が true: 全オンラインプレイヤーに `message.itemmilestonelog.preset_completed_broadcast`（%s = プレイヤー名、%s = プリセット表示名）を送る。本人は muteBroadcasts / notifyPlayer に関係なく受け取る。他のプレイヤーは通常ブロードキャストと同じく muteBroadcasts を尊重する
  - false: `notifyPlayer` が true なら本人に `message.itemmilestonelog.preset_completed`（%s = プリセット表示名）を送る
  - 文面例: en "★ All targets of %s are complete!" / ja 「★ %s のターゲットをすべて達成しました！」、broadcast: en "★ %s completed all targets of %s!" / ja 「★ %s が %s のターゲットをすべて達成しました！」
- プリセット表示名: 組み込みは lang キー `gui.itemmilestonelog.preset.<id>` を `ServerLang.tr` で受信者ごとに引く（値は「プリセット: ATM Star」の形なので、文面はそれを埋め込んで自然な形にしてある）。カスタムは JSON の `name`（無ければ ID）を `Component.literal` で埋め込む（制約12）
- 世界初と同時に起きた場合は両方通知してよい
- `completedPresets` はプリセット ID で持つ。カスタムプリセットの中身が後から変わっても制覇済みは取り消さない。同じ ID で削除・作り直しても制覇済みのまま（README に明記）
- `resetall` で `completedPresets` は消える（§1.1）。単体の `reset` では消さない（リセットと再記録のたびに通知が出るのを防ぐ）
- `presetsEnabled` が false のときは判定・通知を行わない。import では判定しない

### 実装メモ
- PlayerData に `Set<String> completedPresets` を追加（互換ルールに従う）
- プリセットのターゲット集合は Presets の既存 API（`Presets.all()`）から取得する
- 判定は「K, P.records, プリセット一覧, 存在判定関数 → 制覇したプリセット ID の一覧」の純粋関数にして単体テストする

---

## F6. 他プレイヤーの記録を見るコマンド（lookup）

### 仕様
- コマンド追加: `/milestonelog lookup <player>`（対象は過去に参加したことのあるプレイヤー。名前解決は既存の admin 系コマンドと同じ方法を使う: 保存されている最後の名前（大文字小文字を区別しない）または UUID。複数一致は既存の「曖昧」メッセージ）
- 〔X12〕出力は既存の `/milestonelog admin <player> list` と同じ形式（アイテム、プレイ時間、Day、日付）。見出しは既存の `message.itemmilestonelog.admin_records_header`（%s = プレイヤー名、%s = 件数）を使う
  - 初版の「世界初の印」は削除した。既存の本人の `list` にも世界初の印は無く、「list と同じ形式」と両立しないため（印を付ける案は追加候補の文書を参照）
- 対象のデータが無い場合は「記録なし」の既存メッセージ相当（`message.itemmilestonelog.admin_no_records`）を返す
- Config: `allowLookup`（boolean、既定 true）
- false のとき、lookup は §1 制約11の `isPrivileged` を満たす者だけが使える（一般プレイヤーには T の無効化メッセージ）〔X12〕。シングルプレイ / LAN のホストは使える
- 自分自身の lookup は常に可
- 〔X12〕補完候補（保存されている全プレイヤー名）は、そのソースが lookup を使えるときだけ出す（`allowLookup` = false のサーバーで一般プレイヤーに過去の参加者名の一覧を見せない）
- コンソール・RCON からも使える

### 実装メモ
- 表示は MilestoneService / MilestoneCommands の既存の記録整形を再利用する。対象プレイヤーがオフラインでも動作すること

---

## F7. マイルストーン達成時のコマンド実行

### 仕様
- Config:
- `milestoneCommand`（文字列、既定 "" = 無効）: 記録確定時にサーバーコンソール権限で実行するコマンド（先頭の `/` は付けない形式で記述）
- 置換プレースホルダ: `%player%`（プレイヤー名）、`%uuid%`、`%item%`（アイテム ID。タグターゲットでは `#c:ingots/iron` のようなタグ ID になる）、`%count%`（ターゲット数量）、`%playtime%`（整形済みプレイ時間。`27h 15m 30s` のように空白を含む）、`%worldfirst%`（`true` / `false`）
- 例: `give %player% minecraft:firework_rocket 3`
- 複数の処理をしたい場合は `function <namespace>:<path>` を使う（README に例を書く）
- 〔X13〕実行のタイミング: 記録確定時にキューに積み、`ServerTickEvent.Post` で順に実行する（1 tick あたり最大 20 件、残りは次の tick）。記録処理の途中（スキャンのループ中など）でコマンドを実行しない。`server.execute(...)` はサーバースレッドからは即時実行になり得るので、遅延の手段として使わない
  - 同時に複数のキーが記録された場合（×1 と ×100 を同時に達成など）は、記録1件につき1回、TargetKey の順に実行する
  - import・reset・admin 操作では実行しない（記録確定時のみ）
- 〔X13〕実行するソース: `server.createCommandSourceStack()`（権限レベル4相当）に出力の抑止（`withSuppressedOutput` 相当）を付ける。OP のチャットに「[Server: ...]」のフィードバックを出さない。失敗（構文エラー・実行時エラー・戻り値 0）はコールバック（`withCallback` 相当）で検出してサーバーログに warn で残す
- 失敗しても記録処理に影響させない
- 〔X13〕プレイヤー名の検証: `%player%` は、名前が `^[A-Za-z0-9_]{1,16}$` に一致するときだけ置換する。一致しない場合はそのプレイヤーについてはコマンドを**実行せず** warn ログを出す
  - 理由: `online-mode=false` のサーバーでは名前が Mojang のアカウント規則で検証されず、`@` などの記号を含み得る（`give %player% ...` が `give @a ...` になる）。初版の「プレイヤー名は Mojang の命名規則で検証済み」はオフラインモードでは成り立たない
- セキュリティ上の注意を Config のコメントと README に明記する: この値はサーバー所有者のみが設定できる（設定ファイル）。`%player%` は上記の検証を通った場合だけ置換される。プレイヤーの指定には `%uuid%` も使える。`%item%` は Mod 内で検証済みの ID（タグ ID を含む）だけが入る

### 実装メモ
- 実行は `server.getCommands().performPrefixedCommand(source, cmd)` 相当を使う（バージョン差異は既存コードに合わせる）
- 世界初かどうかは `Tracker.record` の戻り経路で分かる値を渡す
- 置換と名前の検証は純粋関数にして単体テストする

---

## F8. タグ指定ターゲット

〔X14〕初版の F8 を、データ表現の F8a（早めに実装）と、検出・コマンド・表示の F8b（最後に実装）に分けた。

### F8a. データ表現（TargetKey とパース・保存）

#### 仕様
- ターゲット指定で、アイテム ID の代わりに **アイテムタグ** を書けるようにする
- 記法: `#namespace:path`（先頭に `#`）。例: `#c:ingots/iron`。defaultTargets・プリセット JSON（文字列形式と `{"item": "#c:ingots/iron", "count": 100}` のオブジェクト形式の両方）・export / import のすべてで同じ記法を使う。コマンドは F8b の専用コマンドを使う
- 意味: そのタグに属するアイテムの所持数を合算して同時所持カウントする（数量ターゲットの意味論は従来通り）
- 記録・世界初・進捗はタグ単位（TargetKey の itemId が `#...` 形式のキーとして扱われる）
- 後方互換: 従来のアイテム ID 形式のターゲット・記録・プリセットは一切変更しない。`#` で始まる文字列は従来は無効だったため衝突しない

#### パース規則（固定）〔X14〕
- 先頭の `#` はタグ印。数量の区切りは**インデックス 1 以降にある最後の `#`**。区切りの後ろは既存の `tryParseCount` の規則（1〜1,000,000 の数字のみ）
- タグ印の後ろの ID は `Identifier`（1.21.1 は `ResourceLocation`）として有効でなければならない
- 例: `#c:ingots/iron` → (タグ, 1)、`#c:ingots/iron#100` → (タグ, 100)、`minecraft:diamond#5` → (アイテム, 5)
- 無効: `#`、`##c:x`、`#c:x#`、`#c:x#0`、`#c:x#abc`、`#:x`、`#c:`、`c:x#1#2`
- 現在の `tryParseEncoded` は最初の `#` で分割しているので、この規則に合わせて書き換え、上の全例を単体テストする

#### TargetKey（公開 API）の変更
- `isValidItemId` がタグ形式（`#` + 有効な ID）を受け付けるようにする。`isTag()`（`#` で始まるか）と、タグ ID（`#` を除いた部分）を返すメソッドを追加する
- API の利用者への影響: `MilestoneRecordedEvent#getItemId()` や `ItemMilestoneLogApi` が `#` で始まる文字列を返し得る。Javadoc と README に「`isTag()` を確認してから `Identifier` として扱う」と明記する

#### 正規化を1か所にまとめる
現在、次の箇所がそれぞれ `Identifier.tryParse(itemId)` で正規化・検証しており、タグ形式をすべて落とす。これらを TargetKey 側の1つのパース/正規化関数に置き換える:
- `Config.validateTargetEntry`、`Tracker.defaultTargets`
- `Presets.parsePresetFile`（文字列形式・オブジェクト形式）、`Presets.saveCustomTo`
- `MilestoneExport.parseSinglePlayerExport`（targets / records）
- `MilestoneService.removeTarget` / `resetRecord`、`MilestoneCommands` の firsts reset / admin reset

#### 検証のタイミング
- Config の検証関数・プリセットの読み込み・import では**形式だけ**を検証する。Config の検証は Mod の初期化時に走り、その時点ではタグがまだ読み込まれていないため、「タグが存在し空でない」は検証できない（初版の「Config 検証でタグの存在を確かめる」は実現できない）
- プリセットは、存在しないタグも保持する（存在しないアイテム ID を落とさない既存の方針と同じ。Mod パック用のプリセットが任意の Mod のタグを含むため）。適用時に、存在しない・空のタグはスキップ件数に数える
- `defaultTargets` の存在しない・空のタグは、サーバー起動時とタグの再読み込み時（`TagsUpdatedEvent`）にタグごとに1回 warn ログを出し、判定では所持数 0 として扱う

#### 保存形式
- targets リスト: `#c:ingots/iron`、`#c:ingots/iron#100`
- records / worldFirsts: 既存の「itemId → { count → 記録 }」の itemId にタグ ID（`#c:ingots/iron`）がそのまま入る
- ダウングレード時の扱いは §1.1 を参照（1.0.1 では失われる）
- 1.0.1 のクライアント（GUI）は、同期データのタグのエントリを黙って捨てる（1.0.1 の `SyncMilestonesPayload.decode` が `isValidItemId` で弾くため。切断はしない）。1.0.1 のクライアントでは、タグのターゲット・記録が GUI に出ないことを README に書く

### F8b. 検出・コマンド・表示

#### 検出
- 所持数カウント（`Tracker.countItemsInPossession`）は、要求された ID 集合にタグが含まれる場合、そのタグのアイテム集合を展開してから数える。タグの解決はサーバーのレジストリ（`BuiltInRegistries.ITEM` のタグ）から行い、スキャンのたびに再解決しない（`Set<Item>` を起動時 + `TagsUpdatedEvent` でキャッシュ）
- 〔X14〕`carriedFromCreative`（アイテム単位）に入っているアイテムは、タグの合計にも数えない
- 〔X14〕拾得・クラフト/精錬の即時判定（`tryRecordForItem`）は、そのアイテムを含むタグの未記録ターゲットも判定対象にする（現在はアイテム ID の完全一致だけを見ている）
- F1・Q9 の進捗もタグの合計で数える

#### コマンド 〔X14〕
既存の `add` は `ItemArgument`、`remove` / `reset` / `firsts reset` / `admin <player> reset` は `IdentifierArgument` を使っており、どれも `#` を受け付けない。制約2により既存コマンドの引数は変えず、次を追加する:

| コマンド | 権限 | 動作 |
|---|---|---|
| `/milestonelog addtag <tag> [<count>]` | 不要 | タグターゲットを追加。存在しない・空のタグは拒否（`message.itemmilestonelog.invalid_tag`、%s = タグ ID） |
| `/milestonelog removetag <tag> [<count>]` | 不要 | タグターゲットを外す |
| `/milestonelog resettag <tag> [<count>]` | 不要 | タグターゲットの記録をリセット |
| `/milestonelog firsts resettag <tag> [<count>]` | 権限レベル2 | タグの世界初を削除 |
| `/milestonelog admin <player> resettag <tag> [<count>]` | 権限レベル2 | 他プレイヤーのタグの記録をリセット |

- `<tag>` は `#` を付けずに書く（`IdentifierArgument`）。コマンド側で `#` を付けて TargetKey にする
- 補完: `addtag` はサーバーのアイテムタグ一覧（`BuiltInRegistries.ITEM` のタグ名）、それ以外はそのプレイヤー（またはワールド）が持っているタグのキー

#### GUI
- タグターゲットは GUI から**追加できない**（アイテム選択画面はアイテムの一覧で、デザイン凍結のため）。追加はコマンド・プリセット・`defaultTargets`・import で行う
- 表示・削除・リセットは既存のボタンで行える（`MilestoneActionPayload` の引数は文字列なので形式の変更は不要。サーバー側は F8a の正規化で受け付ける）
- アイコンはタグの最初のアイテム（クライアント側でタグを解決して描画。解決できない場合はバリアアイコンの既存フォールバック）

#### 表示名 〔X14〕
- チャット・GUI・Discord・HTML の表示名は `Component.translatableWithFallback(Tags.getTagTranslationKey(tagKey), "#c:ingots/iron")` とする
  - NeoForge のタグ翻訳キーの規約（例 `tag.item.c.ingots.iron`）を使う。NeoForge や各 Mod が翻訳を同梱しているタグは「Iron Ingots」のように表示され、翻訳が無い場合とバニラクライアントではタグ ID そのもの（初版の仕様と同じ表示）になる
  - 本 Mod の lang に独自のタグ用キーは作らない（初版の方針を維持）
  - `Tags.getTagTranslationKey` は NeoForge 21.1.1 と 26.1.2.71 にあることを確認済み。26.2 / 26.3 の下限版でも存在を確認すること
- 数量の表記は従来通り（` ×100`）

### 実装メモ（F8 全体）
- SyncMilestonesPayload は itemId 文字列を運ぶだけなので形式変更は不要。クライアント側のアイコンと表示名の解決のみ追加実装が要る
- F8b の CI ボットテスト: `addtag` でタグターゲットを登録し、タグに属するアイテムを拾って記録されることを1ケース確認する

---

## F9. Discord 週次ダイジェスト

### 仕様
- Config:
- `discordDigestEnabled`（boolean、既定 false）
- `discordDigestIntervalDays`（int、既定 7、範囲 1〜30）
- 動作: 前回送信から間隔日数が経過していたら、期間内の新規記録と世界初をまとめて1通の Discord 通知を送る
- 期間の判定には記録の epochMillis を使う。前回送信時刻は MilestoneData（ワールド側）に永続化する（§1.1 の `lastDigestEpoch`）
  - 〔X15〕**初回**（`lastDigestEpoch` が 0）は送らずに現在時刻を記録する。初版のままだと、有効にした直後にワールドの全履歴（epoch 0 以降）を1通で送ってしまう
  - 〔X15〕期間は (前回送信時刻, 現在時刻]。epochMillis が不明（0）の記録（1.0.0 で作られた記録）は含めない
  - 〔X15〕`discordNotifyScope` に従う: `"all"` = 期間内の全員の全記録（世界初には ★ を付ける）、`"world_first"` = 期間内の世界初だけ
- 期間内の記録が0件なら送らない（時刻だけ更新する）
- 送信チェックはサーバー起動時と、稼働中は1時間に1回程度の低頻度で行う（tick カウンタで間引く）
- Webhook URL が空、または discordDigestEnabled が false のときは何もしない
- 文面は F4 の Embed 設定に従う（Embed 時はダイジェスト用 Embed 1件に箇条書きを入れる）。件数が多い場合は先頭20件 + 「他 N 件」とする
  - 〔X15〕各行: `<プレイヤー名> — <アイテム>（<プレイ時間>）`、epochMillis の昇順。名前とアイテム名は F4 と同じく Markdown をエスケープする
  - 〔X15〕長さの上限: Embed の description は 4096 文字、プレーンテキストは 2000 文字。20 件でも超える場合は末尾の行を削って「他 N 件」の N を増やす
- 〔X15〕既知の制限（README に書く）: 送信時刻は送信をキューに積んだ時点で更新する。429 や通信失敗で届かなかったダイジェストは再送しない（既存の通知と同じ方針）。import した記録も epochMillis が期間内ならダイジェストに含まれる（記録に「import 由来」の印が無いため）

### 実装メモ
- MilestoneData に `lastDigestEpochMillis`（long、欠落時 0、NBT キー `lastDigestEpoch`）を追加
- 送信は DiscordNotifier の既存キュー・送信経路を再利用する
- 対象の抽出・整形・切り詰めは純粋関数にして単体テストする

---

## F10. セーブデータの自動バックアップ

### 仕様
- Config:
- `autoBackupEnabled`（boolean、既定 true）
- `autoBackupGenerations`（int、既定 5、範囲 1〜20）
- 動作: MilestoneData を、ワールド保存のタイミングでバックアップする
- 〔X16〕**保存先: `<world>/itemmilestonelog/backups/`**（export と同じ親フォルダ）に `milestones-YYYYMMDD-HHmmss.dat` の名前で書き、世代数を超えた古いものから削除する
  - 初版の `config/itemmilestonelog/backups/` は使わない。config フォルダはシングルプレイでは全ワールド共通なので、複数のワールドのバックアップが同じ名前規則で混ざり、世代の削除が**別のワールドのバックアップを消す**。どのワールドのものかも区別できない
- 〔X16〕**書き方: 実ファイルをコピーせず、メモリ上の MilestoneData をそのまま書き出す**。サーバースレッドで、実ファイルと同じ形式（バニラの SavedData ファイルと同じラッパー。1.21.1 は `save` の出力、26.x は Codec の出力）の圧縮 NBT を、一時ファイル + 移動で書く
  - 理由: 保存の書き込み方式はバージョンで異なり（26.x はサーバー単位の DataStorage）、`LevelEvent.Save` の時点で実ファイルが最新であること・書き込み中でないことを仕様として保証できない。メモリから書けば、その時点の内容が確実に入る
  - 書き出したファイルを `MilestoneData` の読み込み処理で読み戻せることを単体テストで確認する（形式がずれていると復元に使えないため）
- 対象（参考: 復元時に置き換える実ファイル）: 1.21.1 は `<world>/data/itemmilestonelog_data.dat`、26.x は `<world>/data/itemmilestonelog/milestones.dat`
- 頻度制限: 最短1時間に1回まで（〔X16〕最新のバックアップのファイル名の時刻で判定する）。加えてサーバー停止時にも1回試みる（頻度制限の対象外）
- 〔X16〕データが空（プレイヤーも世界初も無い）ときは書かない。同じ秒に2回書く場合はファイル名の末尾に `-1`、`-2` を付ける
- 〔X16〕世代の削除は `milestones-<8桁>-<6桁>(-<数字>)?.dat` に一致するファイルだけを対象にする（利用者が置いた他のファイルは消さない）
- 失敗してもサーバー処理に影響させない（ログのみ）

### 実装メモ
- トリガーは LevelEvent.Save（overworld のみ）+ ServerStoppingEvent を使う。ファイルサイズが小さいためサーバースレッドで同期的に書いてよい
- バックアップからの復元は手動（ファイルを戻す）でよい。復元コマンドは作らない
- 〔X16〕README に復元手順を書く: サーバーを止める → バックアップを上記の実ファイルの場所に、実ファイルの名前で置く → 起動する。26.x で 1.21.1 時代のバックアップを使う場合は、`milestones.dat` が無い状態で `data/itemmilestonelog_data.dat` として置く（26.x の既存の移行処理が読む）

---

## 3. テスト・検証（全機能共通）

- 追加・変更した純粋ロジック（パース、検証、置換、整形、しきい値判定）には単体テストを追加する。特に F8 の TargetKey パースは必須
- 〔X18〕次の純粋関数は必ず単体テストする:
  - G1 の権限判定（専用/統合 × ホスト/ゲスト × 権限レベル × プレイヤー以外のソース）、G2 の表示名の引き継ぎ
  - F1 のしきい値判定（ベースライン、複数超え、最小個数）
  - F5 の制覇判定（判定対象キーが空、存在しないアイテム、個数の不一致、今記録したキーを含まないプリセット）
  - F7 の置換と名前の検証
  - F4 / F9 の Markdown エスケープと切り詰め、F9 の期間と範囲の抽出
  - F10 の世代削除のファイル名一致と、書き出したファイルの読み戻し
  - §1.1 の新フィールドの往復（1.21.1 NBT と 26.x Codec）と、26.x で 1.21.1 形式の新フィールドを読めること
- lang キーの追加は ServerLangTest / LangFilesTest が通ること（en / ja のキー集合とプレースホルダの一致）
- CI の既存ボットテスト（記録・コマンド・移行）が全バージョンで通ること。F8 を実装した場合は、タグターゲットでの記録がボットテストで1ケース確認できることが望ましい
- 〔X18〕F2 を入れた状態で mineflayer（バニラクライアント）の参加テストが通ること
- 〔X2〕手動確認: 1.1.0 のサーバーに 1.0.1 の Mod 入りクライアント、1.0.1 のサーバーに 1.1.0 の Mod 入りクライアントで参加でき、GUI が開けること
- バージョン表記を 1.1.0 に上げる（gradle.properties 等、1.0.1 へ上げたときと同じ箇所）

## 4. ドキュメントとリリース 〔X17〕

- `CHANGELOG.md` に `## 1.1.0` の節を追加する。`check-release.sh` が `mod_version` と同じ節を要求するので、**mod_version を 1.1.0 に上げるコミットと同時に**入れる（無いと CI の release-check が失敗する）。F4 の形式変更（既定 true の場合）と、§1.1 のダウングレードの注意を必ず書く
- `README.md` / `CURSEFORGE.md`: 新しいコマンド・設定、F3 の出力場所、F7 のセキュリティ上の注意、F8 のタグの書き方と 1.0.1 クライアントでの見え方、F10 の復元手順、G2 の上書き時の挙動
- `docs/updates.json`: 1.1.0 の項目と promos は **CurseForge / Modrinth で公開するときのコミットで**更新する。更新チェッカーは main の raw ファイルを読むため、実装の PR で先に更新すると、未公開の版の更新通知がプレイヤーに出る
- `GEMINI.md`: 「現在の開発バージョンは 1.0.1（未リリース）。バージョン番号は上げない」の記述を 1.1.0 の開発状態に合わせ、`docs/` の一覧に本書を加える
- `docs/VERSION_NOTES.md`: §1.1 の新フィールドを「保存データの構造」に追記する

---

## 付録A. レビューで見つかった不備と修正の理由

コードの位置は 26.1.2 のもの（他のバージョンも同じ構造）。NeoForge の挙動は、NeoForge 21.1.1（1.21.1 の下限）と 26.1.2.71（26.1.2 の下限）のソース jar で確認した。

| ID | 修正内容 | 理由・根拠 |
|---|---|---|
| X1 | Config の種類の記述を「サーバー Config」から実際の型（COMMON / 26.3 は LOCAL）に直し、新しい設定型を作らないと明記 | 初版の「サーバー Config」を `ModConfig.Type.SERVER` と読むと、ワールドごとの serverconfig になり、26.3 では SYNCED（クライアントへ同期）になってバニラクライアントの参加互換を壊す（VERSION_NOTES の 26.3 の節） |
| X2 | 既存ペイロードの形式・`Action` の値・`PROTOCOL_VERSION` を変えない。追加は新しい optional ペイロード | `NetworkComponentNegotiator.validateComponent` は optional でもバージョン文字列が異なると失敗を返し、接続を拒否する。版を据え置いて形式を変えると、`SyncMilestonesPayload.decode` / `readEnum` が失敗して切断される。`displayTest="IGNORE_ALL_VERSION"` のため、版の違うクライアントとサーバーの組み合わせは実際に起こる |
| X3 | 新フィールドの NBT キー・型・後片付け・ダウングレード時の扱いを固定（§1.1） | 1.21.1 → 26.x の移行は 26.x の Codec で 1.21.1 のファイルを読む（`MilestoneData.readLegacy`）ので、キー名が揃っていないと黙って失われる。初版はキー名も、reset / remove 時の扱いも未定義だった。1.0.1 の `isValidItemId` は `#` を弾くので、タグのデータはダウングレードで消える |
| X4 | LAN のゲストのプリセット管理を修正（G1）、管理者判定を `isPrivileged` に一元化（制約11）、F6 の `allowLookup = false` にも使う | `MilestoneService.canManagePresets` は `!server.isDedicatedServer()` なら true を返す（コマンド・GUI とも）。プリセットはホストの config フォルダ（全ワールド共通）にあり、ゲストが削除できる。初版の F6 の「権限レベル2以上」だと、チート無しのシングルプレイ / LAN のホストが使えない |
| X5 | GUI の上書き保存の明示と表示名の引き継ぎ（G2）、外部文字列の扱い（制約12） | `Presets.saveCustomTo` は常に `"name": id` を書き、`SavePresetScreen` は既存 ID を確認せずに送る。上書きと手書きの表示名の消失が、保存した後のメッセージでしか分からない |
| X6 | T の各スイッチが GUI・他機能に与える影響を明記 | 初版は GUI の APPLY / SAVE / DELETE や一覧の扱い、exportall・OP の扱い、世界初を止めたときの Discord / F2 / F7 への影響が未定義だった |
| X7 | F1 にベースライン・複数超え・最小個数・端数の規則を追加 | 初版のままだと、1.1.0 導入直後やプリセット適用直後に既に持っている分のアラートがまとめて出る。count = 2 で「1/2 (50%)」が出る。複数のしきい値を同時に超えたときの通知数が未定義 |
| X8 | F2 の `count` を範囲指定可能に、`world_first` と `player` 条件、既存記録への遡及、バニラ互換の確認 | 「数量。省略時は任意」だけでは完全一致か以上かが決まらない。初版のままだと、データパックを後から入れたワールドでは既存の記録に advancement が付かない。`TRIGGER_TYPE` は `NeoForgeRegistriesSetup.VANILLA_SYNC_REGISTRIES` に無い（21.1.1 / 26.1.2.71） |
| X9 | F3 のコマンドを `exporthtml` / `exporthtmlall` に | 初版の `exporthtml [player]` は「動作・権限は既存の export と同じ」と矛盾し（既存の export に player 引数は無い）、`all-players.html` を作るコマンドが無かった |
| X10 | F4 の各項目の固定、Markdown エスケープ、長さの上限、既定値の判断事項 | 初版は「description または fields」で出力が一意に決まらない。`_Steve_` のような名前の `_` などが Markdown として解釈される（現行のプレーンテキストでも同じ）。Discord の上限を超えると投稿が拒否される |
| X11 | F5 の判定を「今記録したキーを含むプリセット」「判定対象キー」で定義、本人への通知を多くても1通に、表示名の出所 | 初版のままだと、導入後の最初の記録で導入前に条件を満たしていたプリセットの「制覇」が一斉に出る。Mod が入っていないプリセット（`atm_star`）は永遠に制覇できない／判定対象が空だと0件で制覇になる。`notifyPlayer` と全体アナウンスが両方 true だと本人に2通届く。組み込みの表示名（lang の値）は「プリセット: …」の形 |
| X12 | F6 の出力形式・権限・補完 | 既存の `list` に世界初の印は無く（`MilestoneCommands.list`）、「list と同じ形式」と「世界初の印」が両立しない |
| X13 | F7 の実行タイミング・出力抑止・名前の検証 | オフラインモードではプレイヤー名が Mojang の規則で検証されず、記号を含み得るため、初版のセキュリティ上の前提が成り立たない。記録処理の途中で任意のコマンドを実行すると、そのコマンドがデータを変更したときの影響が読めない。`give` などは OP のチャットにフィードバックを出す |
| X14 | F8 を F8a / F8b に分割、パース規則・正規化箇所・検証時期・専用コマンド・クリエイティブ除外・即時判定・表示名・API への影響 | `tryParseEncoded` は最初の `#` で分割する。`add` は `ItemArgument`、`remove` / `reset` は `IdentifierArgument` で `#` を受け付けず、既存コマンドではタグを扱えない（制約2で引数も変えられない）。正規化の各箇所が `Identifier.tryParse` でタグを落とす。Config の検証時点ではタグが未読込。プリセットの「存在しない ID を落とさない」方針と初版の「存在しないタグは弾く」が矛盾。`tryRecordForItem` はアイテム ID の完全一致だけを見る。`Tags.getTagTranslationKey` が NeoForge にある |
| X15 | F9 の初回・期間・範囲・長さの上限・既知の制限 | `lastDigestEpochMillis` の欠落時 0 のままだと、有効化した直後に全履歴を送る。`discordNotifyScope` との関係が未定義。プレーンテキストは 2000 文字が上限 |
| X16 | F10 の保存先をワールドフォルダに、メモリから書き出す方式に、復元手順 | config フォルダは全ワールド共通で、世代の削除が他のワールドのバックアップを消す。実ファイルのコピーは保存の書き込み方式・時期に依存する |
| X17 | ドキュメント・リリース手順の節を追加 | `check-release.sh` が CHANGELOG の節を要求する。`updates.json` は main の raw URL から読まれる。`GEMINI.md` は「1.0.1 未リリース・版を上げない」のままで本仕様と矛盾する |
| X18 | テスト項目の追加 | CI の専用サーバーでは LAN を再現できない。F2 は初めてのレジストリ登録で、バニラ参加互換の確認が要る |
| X19 | 実施順序を変更（G 先頭、F8a を前へ） | F8 が TargetKey の意味を変え、F1〜F9 がその上に乗るため |
