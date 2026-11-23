# Item Milestone Log — 追加機能 実装仕様書（v1.1.0）

この文書は、AI（または開発者）がこの文書だけを読んで v1.1.0 の実装を完遂できるように書かれた自己完結の仕様書である。対象リポジトリ: pengin0503/Item-Milestone-Log

**v1.2.0 に入る機能の仕様は別文書 `docs/FEATURE_SPEC_1.2.0.md` を参照すること**（候補 P52 により合本から版別に分割した。共通制約 §1 と付録A・付録B は本書に残し、v1.2.0 の文書から参照する）。

> **改訂（2026-11-23、設計レビューの反映）**: 初版の設計上の不備・矛盾を修正した。修正した箇所には 〔X番号〕 を付けている。各修正の理由と根拠（コードの位置・確認した内容）は末尾の「付録A」にまとめた。採用しない修正は、その箇所を初版の記述に戻せばよい（git の差分で初版と比較できる）。
>
> **改訂（2026-11-23、追加機能の採用）**: 別文書 `docs/FEATURE_PROPOSALS_1.1.0.md` の候補のうち、オーナーが採用した16件を F11〜F26 として本書に移した（見出しに元の候補番号を付けた）。P15（収納の中身を数える）と P16（タイトル演出）は採用しない。F4 の `discordUseEmbeds` の既定値はオーナーの判断で false にした。経緯は末尾の「付録B」。
>
> **改訂（2026-11-23、第2回の候補の採用）**: 第2回の候補 P19〜P32 をすべて採用した。P19〜P30 を F27〜F38 として本書に移し、P31 を §3 に、P32 を §2.1（**1.1.0 と 1.2.0 の2回のリリースに分ける**）に反映した。ファイル名は `FEATURE_SPEC_1.1.0.md` のままとし、1.2.0 の分も本書で扱う。あわせて、日本語の文面の用語を既存の lang ファイルに揃えた（〔X20〕）。経緯は付録B。
>
> **改訂（2026-11-23、第3回の候補の採用）**: 第3回の候補 P33〜P43 をすべて採用した。P33〜P42 を F39〜F48 として本書に移し、P43 を §3.1 に入れた。P35〜P37 の採用にあたり、オーナーが制約1（F41・F42・F44）と制約2・11（F43）の例外を承認した（各制約に明記）。見送りを勧めた R12〜R14 は見送りのまま。経緯は付録B。
>
> **改訂（2026-11-23、第4回の候補の採用）**: 第4回の候補 P44〜P53 をすべて採用した（P50 は (a) と (c) を採用し (b) は不採用）。P44〜P49 を F49〜F54 として本書に移し、P50 を §3.2（1.1.0 の実装の前の前提作業）、P51 を §3.3（GameTest のテスト基盤）、P53 を §4.1（公開の CI 化）に入れた。P52（仕様書の版別分割）を実施し、v1.2.0 の仕様を `docs/FEATURE_SPEC_1.2.0.md` に分離した。見送りを勧めた R15〜R19 は見送りのまま。経緯は付録B。

## 0. 前提（現在の状態）

- Mod: Item Milestone Log（mod_id: `itemmilestonelog`、パッケージ: `com.pengin0503.itemmilestonelog`）
- 対応: Minecraft 1.21.1 / 26.1.2 / 26.2 / 26.3（`versions/<ver>/` 構成、共通アセットは `common/`）。全バージョンで機能は同一でなければならない
- 現行バージョン 1.0.1 は完成済みで、CurseForge / Modrinth に **Release** としてアップロード済み（プロジェクト審査中）。**1.0.1 のファイルは差し替えない。追加機能は 1.1.0 と 1.2.0 の2回のリリースに分けて出す**（どの機能をどちらに入れるかは §2.1。本書は 1.1.0 の仕様、1.2.0 は `docs/FEATURE_SPEC_1.2.0.md`。各リリースで全バージョンの mod バージョンを上げる）
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
   - 例外（オーナーが 2026-11-23 に承認。いずれも 1.2.0）: F41 のキー割り当て（カーソルのアイテムを目標に追加）とアクションバーへの結果の表示、F42 のアイテムのツールチップへの行の追加、F44 の NeoForge の汎用の設定画面。どれも本 Mod の画面（MilestoneScreen / ItemPickerScreen / PresetListScreen など）のレイアウトは変えない。これ以外のクライアントの新しい表示・画面（付録の R5・R11）は引き続き行わない
2. 既存コマンドの構文・動作は変えない。コマンドは追加のみ
   - 例外（オーナーが 2026-11-23 に承認。1.2.0）: F43 で、既存の管理者用コマンドの `.requires` を「権限レベル2以上、**または**権限ノードが true」に広げる。構文は変えず、OP と、権限 Mod を使わないサーバーでの結果は変わらない
3. **SavedData の後方互換を必須**とする。1.0.1 以前のセーブデータを読み込めて壊れないこと。新フィールドが欠ける場合は既定値として扱い、1.21.1 → 26.x の記録引き継ぎでも同様に継続すること
4. バニラクライアントが専用サーバーに参加できる性質を維持する（新規ペイロードを作る場合も optional 登録、サーバー側処理はクライアント Mod の有無に依存しない）
5. 追加する表示テキストは en_us / ja_jp 両方に lang キーを追加する（プレースホルダの数・順序を両言語で一致させる。ServerLangTest / LangFilesTest が検証する）。リテラルの `%` は `%%` と書く
   - 〔X20〕日本語の用語は既存の ja_jp.json に揃える: target は「目標」（既存の 28 か所が「目標」、「ターゲット」は 1 か所だけ）、default target は「デフォルト目標」。本書の地の文の「ターゲット」は説明のための語で、文面には使わない
6. 追加依存ライブラリは原則なし（Minecraft 同梱の Gson は使用可）
7. 実装は全4バージョン（1.21.1 / 26.1.2 / 26.2 / 26.3）に行う。各バージョンの API 差異は既存コードの書き方に合わせる
8. **利用者向けの新機能には必ず ON/OFF の手段を用意する**。原則は**既存の `Config` クラス**への boolean の追加とする（既存と同じくセクション分けせずトップレベルに追加する。新しい `ModConfig` の登録や、`SERVER` / 26.3 の `SYNCED` 型の設定ファイルは作らない）〔X1〕。「設定値が空なら無効」（コマンド文字列・URL 等）や「使う側がデータパック / ターゲット設定で選ぶ」性質のものは、その旨を各機能に明記すれば Config を追加しなくてよい
9. 新機能の状態を永続化する場合は MilestoneData（PlayerData またはワールド側）に追加し、制約3の互換ルールと §1.1 の保存形式に従う 〔X3〕
10. **ネットワーク互換** 〔X2〕: 既存ペイロード（`RequestSyncPayload` / `SyncMilestonesPayload` / `MilestoneActionPayload`）のワイヤ形式、`MilestoneActionPayload.Action` の列挙値、`PROTOCOL_VERSION` を変更しない。クライアントへ追加のデータを送る必要が生じた場合は、**新しい optional ペイロード型を追加**し、送信前に `player.connection.hasChannel(新しい TYPE)` を確認する（1.0.1 のクライアントにはそのチャンネルが無いので送らない）。C2S に新しい操作が必要な場合も、既存の `Action` に値を足さず新しいペイロード型にする
    - 理由: NeoForge は optional のペイロードでも、両者のバージョン文字列が異なると接続自体を拒否する。版を据え置いたまま形式を変えると、1.0.1 のクライアント / サーバーとの組み合わせでデコードに失敗して切断される（付録A X2）
11. **管理者判定の一元化** 〔X4〕: 1.1.0 / 1.2.0 で追加・修正する「管理者だけが行える操作」の判定は、新設する `MilestoneService.isPrivileged(CommandSourceStack)` / `isPrivileged(ServerPlayer)` の1か所で行う
    - プレイヤー以外のソース（コンソール・RCON・コマンドブロック等）: 権限レベル2以上なら true
    - 専用サーバーのプレイヤー: 権限レベル2以上なら true
    - 統合サーバー（シングルプレイ・LAN）のプレイヤー: **ホスト本人**（`MinecraftServer#isSingleplayerOwner` 相当、バージョンごとの API に合わせる）であるか、権限レベル2以上なら true。LAN のゲストはチートが許可されていない限り false
    - 既存コマンドの `.requires(hasPermission(2))`（admin / firsts reset 系 / exportall）は、1.1.0 では制約2により変更しない
    - 新しく追加するコマンドのうち、既存の管理者用コマンドと同じ種類のもの（F3 `exporthtmlall`、F26 `exportcsvall`、F22 `importall`、F8b `firsts resettag` / `admin <player> resettag`、F24 `renameitem`、F37 `prunedata`、F49 `discordtest`）は、既存と揃えて `.requires(hasPermission(2))` にする（同じ系統のコマンドで、使える人が食い違わないようにする）。F33 のコマンドは既存の `admin` の下に置くので、`admin` の `.requires` がそのまま効く。`isPrivileged` は、OP 不要のコマンドやプリセットの管理の中で、管理者かどうかで動作を変える箇所（G1、F6 / F13 / F38 / F51 の `allowLookup`、F14 の非公開、F29 の一覧に出すかどうか）に使う
    - 1.2.0 の F43 以降: 上の `.requires` と `isPrivileged` を使う箇所の判定を、F43 の判定関数（従来の判定、**または**権限ノード）に置き換える（制約2の例外。オーナーが承認）。置き換えた後も、権限 Mod が無ければ結果は変わらない
12. **外部由来の文字列の扱い** 〔X5〕: プレイヤー名、カスタムプリセットの表示名、他 Mod のアイテム名など、利用者や外部から来る文字列は書式文字列として解釈せず `Component.literal` で埋め込む。HTML（F3）では HTML エスケープ、Discord（F4 / F9）では Markdown のエスケープ、コマンド文字列（F7）では検証を行う（各節に詳細）
13. **タグ形式のキー** 〔X14〕: F8a の実装以降（**1.2.0**、§2.1）、`TargetKey.itemId` は `#` で始まるタグ ID（例: `#c:ingots/iron`）になり得る。1.2.0 で追加・変更する全機能は `TargetKey.isTag()` を考慮し、`itemId` を確認せずに `Identifier.parse` やレジストリ参照に渡さない
    - 1.1.0 ではタグを受け付けない。ただし 1.1.0 で作る機能も、キーの正規化・検証は 1.1.0 で先に1か所にまとめる関数（§2.1、F8a の「正規化を1か所にまとめる」）を通し、1.2.0 でタグに対応する箇所を `FEATURE_SPEC_1.2.0.md` の F8b「1.1.0 の機能へのタグの対応」の一覧で追えるようにする

### 1.1 新しく永続化するフィールド 〔X3〕

1.21.1（NBT）と 26.x（Codec）で**同じキー名・同じ型**にする。1.21.1 → 26.x の移行（`MilestoneData.readLegacy`）は 26.x の Codec で 1.21.1 のファイルを読むため、キー名がずれると移行で黙って失われる。

| 場所 | NBT キー | 型 | 欠落時の値 | 書き出し | 用途 |
|---|---|---|---|---|---|
| PlayerData | `progressNotified` | compound（キー: エンコード済み TargetKey 文字列（`toEncodedString()`、例 `minecraft:diamond#1000`）、値: int） | 空 | 空なら書かない | F1 |
| PlayerData | `completedPresets` | 文字列リスト（プリセット ID） | 空 | 空なら書かない | F5 |
| PlayerData | `hideFromLookup` | boolean | false | true のときだけ書く | F14 |
| PlayerData | `muteProgressAlerts` | boolean | false | true のときだけ書く | F15 |
| PlayerData | `lastSeen` | long（epochMillis） | 0（不明） | 0 なら書かない | F37（1.2.0 で F52 も参照） |
| PlayerData | `excludeFromDiscord` | boolean | false | true のときだけ書く | F45 |

- 1.2.0 で追加する永続化フィールド（`lastDigestEpoch` 等）および 1.2.0 → 1.1.0 ダウングレードの扱いは `docs/FEATURE_SPEC_1.2.0.md` を参照

- 26.x では新フィールドを `lenientOptionalFieldOf`（または既存コードと同じ `orElse` による要素単位の読み飛ばし）で定義し、新フィールドが壊れていてもファイル全体の読み込みを失敗させない（issue #40 と同じ方針）。1.21.1 も要素単位で読み飛ばし、warn ログを出す
- 不正なキー（TargetKey としてパースできない）や範囲外の値の要素は、要素単位で読み飛ばす
- 後片付けの規則（データが残り続けないようにする）:
  - 記録の確定、`reset <item> [count]`、GUI のリセット、`admin <player> reset`、`remove`: そのキーの `progressNotified` を消す
  - `resetall`、GUI の全リセット、`admin <player> resetall`: `progressNotified` と `completedPresets` をすべて消す
  - `admin <player> purge`: PlayerData ごと消える（従来通り）
  - `hideFromLookup` / `muteProgressAlerts` / `excludeFromDiscord` は個人の設定なので、`resetall` や admin の操作では消さない（`purge` では PlayerData ごと消える）
  - `lastSeen` はログインとログアウトのときだけ書き換える（F37。1.2.0 の F52 での参照は `FEATURE_SPEC_1.2.0.md` 参照）。リセット・import・admin の操作では変えない
  - F28 の一括削除: 外したキーの `progressNotified` を消す（`remove` と同じ）
  - F33 の `grant`: 記録の確定と同じ（そのキーの `progressNotified` を消す）
  - import / F22 importall: これらのフィールドを変更しない
  - F23 undo / F37 prunedata: 各節の規則に従う（1.2.0 の F24 renameitem は `FEATURE_SPEC_1.2.0.md` 参照）
  - ログイン時: 記録済みのキーと、実効ターゲット（個人 + `defaultTargets`）に無いキーの `progressNotified` を消す（import や `defaultTargets` の変更で残ったものを片付ける）
- ダウングレード（1.1.0 → 1.0.1）: 1.0.1 は知らないフィールドを読み捨て、次の保存で消す（通知済み・制覇済みの状態と、非公開・ミュート・Discord から外す設定が失われ、1.1.0 に戻すと再び通知され得る）。CHANGELOG と README に明記する
- 1.2.0 で足す: タグデータおよび `lastDigestEpoch` のダウングレード時の扱い（`FEATURE_SPEC_1.2.0.md` 参照）

### 1.2 追加する Config の一覧

すべて既存の `Config` クラスのトップレベルに追加する（§1 制約8）。既存の機能の挙動は、既定値のままなら 1.0.1 と変わらない。例外は F27 の `groupSimultaneousRecords`（既定 true。同じ tick に3件以上の記録があると通知がまとまる）と、設定を持たない表示の改善の F47（`firsts` に今の名前を出す）・F50（クリエイティブ持ち出しで記録が止まっている旨を本人に伝える）で、CHANGELOG に書く。既定で有効な新機能（F1 など）も §4 のとおり CHANGELOG に書く。

| キー | 型 | 既定値 | 範囲・検証 | 機能 |
|---|---|---|---|---|
| `presetsEnabled` | boolean | true | | T |
| `exportImportEnabled` | boolean | true | | T |
| `worldFirstEnabled` | boolean | true | | T |
| `progressAlerts` | boolean | true | | F1 |
| `progressAlertThresholds` | 整数リスト | [50, 90] | 各 1〜99 | F1 |
| `progressAlertMinCount` | int | 10 | 2〜1,000,000 | F1 |
| `groupSimultaneousRecords` | boolean | **true** | | F27 |
| `discordUseEmbeds` | boolean | **false** | | F4 |
| `announcePresetCompletion` | boolean | false | | F5 |
| `allowLookup` | boolean | true | | F6・F13・F38（1.2.0 で F51 も従う） |
| `displayTimeZone` | 文字列 | "" | 空、または `ZoneId.of` が受け付ける値 | F11 |
| `discordLanguage` | 文字列 | "en_us" | 同梱の言語（F34 の `ServerLang.BUNDLED_LANGUAGES`。現在は `en_us` / `ja_jp`） | F18 |
| `discordNotifyPresetCompletion` | boolean | false | | F19 |
| `firstJoinPresets` | 文字列リスト | []（空 = 無効） | 各要素が `Presets.isValidId` の形。未知の ID は warn ログを出して飛ばす | F40 |
| `allowDiscordOptOut` | boolean | true | | F45 |

- 既存の `discordWebhookUrl` は、F39 で `env:<変数名>` の書き方も受け付ける（設定のキー・既定値は変えない）
- 1.2.0 で追加する Config 一覧および F44 の設定画面の対象は `docs/FEATURE_SPEC_1.2.0.md` を参照

Config を持たない新機能（制約8の例外、1.1.0 分）: F14 / F15（個人の切り替えそのものが ON/OFF）、F16（`presetsEnabled` に従う）、F20（API。T の方針どおり）、F22 / F26（`exportImportEnabled` に従う）、F23 / F28 / F29（コマンドを使わなければ何もしない）、F33 / F37（管理者のコマンド）、F30（ページ番号を付けなければ従来通り）、F31（`exportImportEnabled` に従う）、F32（API。T の方針どおり）、F34（利用者向けの機能ではない）、F35 / F36（挙動を良くする内部の改善で、切り替える意味が無い）、F38（`allowLookup` に従う）、F39（既存の設定の書き方を増やすだけ）、F47（表示の改善）、F49（管理者のコマンド）、F50（既存の挙動の説明を出すだけ）。

## 2. リリースの分け方と実施順序 〔X19〕

### 2.1 2回のリリースに分ける（候補 P32、2026-11-23 に採用）

G・T・F1〜F54 を2回のリリースに分ける。1.1.0 には保存データとコマンドが中心の機能を、1.2.0 には新しい登録・通信・リロード・定期処理・コマンドの実行・ファイルの書き換え・クライアントの新しい表示を伴う機能とタグ（F8）を入れる。1回の版に全部を入れると、審査に出すまでの期間が延び、不具合が出たときに原因の切り分けも難しくなるため（1.0.1 では、実装の後のレビューで issue #28〜#48 の範囲に多数の指摘が出た）。

| リリース | 入れるもの |
|---|---|
| **1.1.0** | §3.1 の CI の参加テスト（実装の前に）、§3.2 のコード共有化と同一ファイル検査（実装の前に）、§3.3 の GameTest 基盤（前半）、G、T、F1、F3、F4、F5、F6、F11、F13（`toptag` を除く）、F14、F15、F16、F18、F19、F20、F22、F23、F26、F27〜F38、F39、F40、F45、F47、F49（`worldfirst` と権限ノードを除く）、F50（タグ対象を除く）、**F8a のうち「正規化を1か所にまとめる」だけ**（タグの記法はまだ受け付けない）、§4.1 の公開 CI 化（1.1.0 の公開まで） |
| **1.2.0** | F8a（タグの記法・保存）、F8b（タグの検出・コマンド・表示と、1.1.0 の機能のタグへの対応）、F2、F7、F9、F10、F12、F13 の `toptag`、F17、F21、F24、F25、F41、F42、F43、F44、F46、F48、F49 の `worldfirst`・権限ノード、F50 のタグ対象、F51、F52、F53、F54 |

- 1.2.0 に回した理由: F2 は本 Mod で初めてのレジストリ登録、F25 は新しいペイロード、F21 はリロードリスナー、F17 はスコアボード、F7 は任意のコマンドの実行、F9 は定期処理、F10 はセーブデータの書き出し、F24 は F10 を使う全データの書き換え、F12 はバージョンごとに API が違い、バニラクライアントでの確認も要る。F8 は `TargetKey` の意味を変え、ほかの機能すべてに触れる
  - 第3回の分: F41・F42・F44 はクライアントの新しい表示で、本物のクライアントでの手動の確認が要る（F41 はバージョンで API の名前も違う）。F43 は全部の管理者用コマンドの判定を置き換えるので、1.2.0 で足す管理者用コマンド（F8b・F24）が揃ってから入れる。F48 は記録の整形をチャット・GUI の全箇所で置き換えるので、同じくチャットの文面を変える F12 と同時に入れる。F46 は F35 の送り方の改善が実際のサーバーで落ち着いてから入れる
  - 第3回の分のうち 1.1.0 に入れるもの（F39・F40・F45・F47）は、どれも小さく、1.1.0 の機能（F35、F33 のプリセットの適用、F14 と同じ個人の設定、`firsts`）の延長にある
  - 第4回の分: F49 は 1.1.0 の Discord 関連機能（F4・F18・F35・F39）の確認手段、F50 は既存のクリエイティブ持ち出しの挙動の説明なので 1.1.0 に入れる。§3.2 は 1.1.0 の実装全体の重複・反映漏れを防ぐ前提作業、§3.3 は記録経路の検証基盤として 1.1.0 の前半、§4.1 は 1.1.0 の公開までに入れる。F51（バニラクライアント向けの F42 相当で F8b のタグ対応を伴う）、F52（F37 の `lastSeen` の上に乗る）、F53（F46 と合わせて Discord 送信を拡張する）、F54（F21 のデータパックプリセットと相乗効果が大きい）は 1.2.0 に回す
- P52 に基づき本書（`FEATURE_SPEC_1.1.0.md`）と `FEATURE_SPEC_1.2.0.md` に分割済み。1.2.0 の仕様本文は `docs/FEATURE_SPEC_1.2.0.md` を参照
- 1.1.0 は、1.2.0 の機能が無くても完結する。1.2.0 の機能と関わる箇所（F17 のスコアを書く、F7 を動かす、F2 のトリガーを呼ぶ、F25 で送る、タグを扱う）は、1.2.0 でその機能を入れるときに足す。`docs/FEATURE_SPEC_1.2.0.md` の「1.1.0 の機能へ 1.2.0 で足す変更の一覧」と F8b の「1.1.0 の機能へのタグの対応」がその一覧になる
- F8a の正規化だけを 1.1.0 に入れる理由: 正規化の箇所（`Config.validateTargetEntry`、`Presets.parsePresetFile` ほか、F8a の一覧）を 1.1.0 のうちに1つの関数にまとめておけば、1.1.0 で足すコマンド（F28・F32・F33 ほか）も最初からその関数を通り、1.2.0 でタグを受け付けるときの変更がその関数と F8b の一覧の箇所に限られる
- 版ごとに CHANGELOG・`docs/updates.json`・mod バージョンを分ける（§4）

### 2.2 実施順序

**1.1.0**

1. P31: issue #44 のテスト不足の解消（§3。既存のコードは変えず、テストだけを足す）→ §3.2（コード共有化: (a) 同一ファイル CI 検査と (c) `common/java` の設定）→ F34（言語ファイルのテスト）→ §3.1（版の違うクライアントとサーバーの参加テスト。受け取りの debug ログだけを足す）
2. G（1.0.1 からの不備修正）→ T（既存機能のスイッチ追加）→ F8a の正規化の整理 → F11（タイムゾーン）
3. F1 → F15 → F27 → F36 → F50（記録の経路）→ §3.3（GameTest 基盤の導入）
4. F4 → F18 → F35 → F39 → F45 → F49 → F5 → F19 → F20 → F32
5. F6 → F14 → F13 → F38 → F16 → F47
6. F23 → F28 → F33 → F40
7. F3 → F26 → F22 → F31 → F37
8. F30 → F29（`help` は 1.1.0 のコマンドが揃ってから）→ §4.1（公開 CI 化。1.1.0 の公開まで）

- F11 は日付を整形するすべての箇所（F3・F4・F13・F26 ほか、1.2.0 の F9）に関わるので早めに入れる
- F27 は F5 の通知と F19 の送り方を決めるので、F5 より先に入れる。F35 は F27 と同じく Discord の投稿が届かない問題への対処なので、Discord の機能（F4・F18）と続けて入れる
- F28 は F23 の取り消しの対象を広げるので、F23 の後に入れる
- F39 と F45 は Discord に送る箇所すべてに関わるので、F35 の直後、F19 より先に入れる。F49 は F4・F18・F35・F39 の確認に使えるのでその直後に入れる。F40 は F33 の `admin <player> preset` と同じ適用の関数を使うので、F33 の後に入れる
- §3.1 と §3.2 は 1.1.0 の本体コードの変更より先に入れ、通信互換と4バージョン間の反映漏れを最初から毎回確かめる。§3.3 の GameTest は記録の経路（段落 3）の直後に導入し、以後のサーバー側機能の検証にも使う
- 上記の依存関係を守れば、順序の入れ替え自体は可。分けない場合も、PR は上の段落ごとに小さく分ける
- 1.2.0 の実施順序は `docs/FEATURE_SPEC_1.2.0.md` の §2 を参照

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
- 1.2.0 で足す: G1 への F43 権限ノード `itemmilestonelog.presets.manage`、G2 での F54 オブジェクト形式 `name` の引き継ぎ（`FEATURE_SPEC_1.2.0.md` 参照）

---

## T. 既存機能への ON/OFF スイッチ追加

現在スイッチが無い既存機能に Config を追加する。いずれも**既定値は現在の挙動と同じ**（アップグレードで挙動が変わらないこと）。

- `presetsEnabled`（boolean、既定 true）: false のとき、カスタムプリセットの保存・適用・削除とプリセット一覧を無効化する（コマンドは「無効化されている」旨のメッセージを返す。組み込みプリセットの適用も含めて無効。既存のプリセットファイルやデータは削除しない）
  - 〔X6〕対象: `preset <name>` / `preset list` / `preset save` / `preset delete` / `preset reload`、GUI の APPLY_PRESET / SAVE_PRESET / DELETE_PRESET（サーバー側で拒否し、無効化メッセージをフィードバックとして返す）
  - 〔X6〕GUI: 同期データのプリセット一覧を空にし、`canManagePresets` を false にして送る（「プリセット...」ボタンは残り、一覧画面は既存の「利用可能なプリセットがありません。」表示になる。ペイロード形式は変えない）
  - F5（制覇）の判定・通知も行わない（F19 の Discord 通知と F20 の `PresetCompletedEvent` も起きない）
  - F16 の `presetprogress`、F28 の `presetremove`、F33 の `admin <player> preset` も使えない
- `exportImportEnabled`（boolean、既定 true）: false のとき export / import コマンド（F3 の exporthtml、F22 の importall、F26 の exportcsv も含む）を無効化する
  - 〔X6〕対象: `export` / `exportall` / `import` / `exporthtml` / `exporthtmlall` / `importall` / `exportcsv` / `exportcsvall`。OP も対象とする（サーバー所有者が設定で戻す）。F31 の世界初の書き出し・読み込みは `exportall` / `importall` の一部なので、同じく止まる
- `worldFirstEnabled`（boolean、既定 true）: false のとき、世界初の**記録自体**を行わない（既存の世界初データは保持するが新規登録しない。`announceWorldFirst` は従来通りアナウンスのみの制御として残す。firsts の閲覧は可能）
  - 〔X6〕連動: 新しい世界初が生まれないため、`announceWorldFirst` は何もしない。`discordNotifyScope = "world_first"` では Discord に何も送られない。F20 の `isWorldFirst()` は常に `false`。既存の世界初は F13 の ★ に従来通り反映される。`firsts reset` / `firsts resetall` は従来通り使える
- Mod API / イベントは「他 Mod が使う側が opt-in する」性質のため、スイッチは追加しない（この節の対象外であることを明記して完了とする）
- 1.2.0 で足す: `presetsEnabled` は F21 にも効く、`exportImportEnabled` は F10 に影響しない、`worldFirstEnabled` = false 時の F2・F7・F17・F25 との連動（`FEATURE_SPEC_1.2.0.md` 参照）

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
- `notifyPlayer` とは独立に ON/OFF できること（サーバー全体の制御は progressAlerts だけ。個人は F15 の `muteprogress` で止められる）
- 1.2.0 で足す: F8b のタグターゲットの所持数合算による進捗判定、F12 のホバー（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- PlayerData に `Map<TargetKey, Integer> progressNotified`（値 = 通知済み最大しきい値、ベースラインでは 0 もあり得る）を追加。26.x Codec / 1.21.1 NBT の両方で欠落時は空として読む（§1.1）
- 所持数の算出は既存の `Tracker.countItemsInPossession` を使う。新規の所持数え直しを増やさない（スキャンの結果を再利用する）。スキャンの `counts` は未記録ターゲットのアイテムだけを数えているので、そのまま使える
- しきい値判定（ベースライン、複数超え、範囲外の値の扱い）は純粋関数に切り出して単体テストする

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
  - 日付・生成日時のタイムゾーンは F11 の `displayTimeZone`（空ならサーバーの既定）
- HTML エスケープを必須とする（プレイヤー名・プリセット名・アイテム名等、外部由来の文字列を埋め込む箇所すべて。`&` `<` `>` `"` `'` を実体参照にする）。`<meta charset="utf-8">` を入れる
- 失敗時（IO エラー等）は既存 export と同じ失敗メッセージ経路を使う（例外詳細をプレイヤーに出さない、という既存方針を維持）
- 〔X9〕専用サーバーではプレイヤーはこのファイルを開けない（サーバーのフォルダにあるため）。主にシングルプレイとサーバー所有者向けの機能であることを README に書く
- 1.2.0 で足す: F8b のタグターゲットの表示名、F43 の `exporthtmlall` 権限ノード `itemmilestonelog.export.all`（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- JSON export（MilestoneExport）のデータ取得を再利用し、HTML 生成は別メソッドに分ける。テンプレートは文字列組み立てでよい（追加依存なし）
- エスケープ関数とファイル名の決定は単体テストする

---

## F4. Discord 通知の Embed 化

### 仕様
- Config: `discordUseEmbeds`（boolean、**既定 false**）。false のときは従来通りのプレーンテキストで送る
  - 〔X10〕既定値は、オーナーの判断で false にした（初版は true）。既に Webhook を使っているサーバーで、1.1.0 に更新しただけで投稿の形式が変わらないようにするため。Embed を使いたいサーバーが true にする
- 文面と欄名は F18 の `discordLanguage` の言語で作る。日付は F11 の `displayTimeZone` で整形する
- Embed の構成（true のとき）〔X10〕:
  - title: アイテム表示名＋個数（count > 1 のとき ` ×N`）。表示名はサーバー側の `getString()`（専用サーバーでは英語。シングルプレイ / LAN ではホストの言語）
  - description: 通常 = `<プレイヤー名> obtained <アイテム>`、世界初 = `★ World First — <プレイヤー名> obtained <アイテム>`（従来のプレーン文面と同じ英語）
  - fields（inline）: `Playtime`（整形済みプレイ時間）、`Day`（判明時のみ）、`Date`（判明時のみ）
  - color: 通常記録 = 0x3498DB（青系）、世界初 = 0xF1C40F（金系）
  - footer: プレイヤー名
  - timestamp: 記録の epochMillis を ISO-8601 で入れる（判明時のみ。Discord が閲覧者のタイムゾーンで表示する）
- 〔X10〕Markdown のエスケープ: description・fields・プレーンテキストの content に入れるプレイヤー名・アイテム名は、Discord の Markdown 記号（`\` `*` `_` `~` `` ` `` `|` `>` `#` `-` `[` `]` `(` `)`）の前に `\` を付ける（例: `_Steve_` という名前が斜体の「Steve」にならない）。プレーンテキスト形式にも適用する
- 〔X10〕長さの上限: title 256 文字、field の値 1024 文字、description 4096 文字、プレーンテキストの content 2000 文字。超える場合は切り詰める（サロゲートペアを分割しない）
- 既存の挙動（Webhook URL が空 = 無効、discordNotifyScope、429 はドロップ、有界キュー、allowed_mentions なし）は変更しない
- 1.2.0 で足す: F8b のタグターゲットの表示名、F53 の通知音なしフラグ（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- DiscordNotifier の JSON 組み立てに embeds 配列の分岐を追加する。プレーン形式の既存テスト・送信経路は壊さない
- エスケープと切り詰めは純粋関数にして単体テストする

---

## F5. プリセット制覇アナウンス

### 仕様 〔X11〕
- プレイヤーが、あるプリセット（組み込み + カスタム）の**全ターゲットを記録し終えた**瞬間に制覇として扱う
- 用語: プリセット S の「判定対象キー」R(S) = S の各キーのうち、判定時点でアイテムがレジストリに存在するもの。存在しないキーは判定から除く（プリセットの適用でスキップされるのと同じ扱い。例: ATM の Mod が入っていないワールドの `atm_star`）
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
  - 文面例: en "★ All targets of %s are complete!" / ja 「★ %s の目標をすべて達成しました！」、broadcast: en "★ %s completed all targets of %s!" / ja 「★ %s が %s の目標をすべて達成しました！」
- プリセット表示名: 組み込みは lang キー `gui.itemmilestonelog.preset.<id>` を `ServerLang.tr` で受信者ごとに引く（値は「プリセット: ATM Star」の形なので、文面はそれを埋め込んで自然な形にしてある）。カスタムは JSON の `name`（無ければ ID）を `Component.literal` で埋め込む（制約12）
- 世界初と同時に起きた場合は両方通知してよい
- 制覇を検出したら、上の通知に加えて F19（Discord、設定時のみ）と F20（`PresetCompletedEvent`）を行う。判定の対象は `Presets.all()` のすべて（組み込み・カスタム）
- `completedPresets` はプリセット ID で持つ。カスタムプリセットの中身が後から変わっても制覇済みは取り消さない。同じ ID で削除・作り直しても制覇済みのまま（README に明記）
- `resetall` で `completedPresets` は消える（§1.1）。単体の `reset` では消さない（リセットと再記録のたびに通知が出るのを防ぐ）
- `presetsEnabled` が false のときは判定・通知を行わない。import では判定しない
- 1.2.0 で足す: F8b のタグの R(S) 判定、F21 のデータパックプリセット、F54 の多言語表示名（`FEATURE_SPEC_1.2.0.md` 参照）

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
- F14 で記録を隠しているプレイヤーは、`isPrivileged` を満たさない他のプレイヤーからは lookup できない（`lookup_hidden` の文面を返す）。補完候補にも出さない
- 〔X12〕補完候補（保存されている全プレイヤー名）は、そのソースが lookup を使えるときだけ出す（`allowLookup` = false のサーバーで一般プレイヤーに過去の参加者名の一覧を見せない）
- コンソール・RCON からも使える
- 1.2.0 で足す: F12 のホバー、F43 の権限ノード `itemmilestonelog.viewhidden`、F48 のプレイ時間・Day 表記（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 表示は MilestoneService / MilestoneCommands の既存の記録整形を再利用する。対象プレイヤーがオフラインでも動作すること

---

## F8a（1.1.0 分）. ターゲットキーの正規化を1か所にまとめる

F8（タグ指定ターゲット）本体（F8a のタグ記法・保存と F8b の検出・コマンド・表示）は 1.2.0 に入る（`FEATURE_SPEC_1.2.0.md` 参照）。1.1.0 ではタグの記法はまだ受け付けず、1.2.0 での変更箇所を最小にするために正規化・検証の集約だけを先に行う（§2.1）。

#### 正規化を1か所にまとめる
現在、次の箇所がそれぞれ `Identifier.tryParse(itemId)` で正規化・検証しており、タグ形式をすべて落とす。これらを TargetKey 側の1つのパース/正規化関数に置き換える:
- `Config.validateTargetEntry`、`Tracker.defaultTargets`
- `Presets.parsePresetFile`（文字列形式・オブジェクト形式）、`Presets.saveCustomTo`
- `MilestoneExport.parseSinglePlayerExport`（targets / records）
- `MilestoneService.removeTarget` / `resetRecord`、`MilestoneCommands` の firsts reset / admin reset

- 1.1.0 では、この集約関数はアイテム ID のみを受け付ける（`#` で始まるタグ形式は受け付けない）。1.1.0 で追加するコマンド・API（F28・F32・F33 ほか）も最初からこの関数を通す
- 1.2.0 で足す: F8a のタグ記法・パース・保存と F8b 全体（`FEATURE_SPEC_1.2.0.md` 参照）

---

## F11. 表示に使うタイムゾーン（候補 P1）

### 仕様
- Config `displayTimeZone`（文字列、既定 "" = サーバーの既定のタイムゾーン）。`Asia/Tokyo` のような IANA の ID、または `ZoneId.of` が受け付ける形式（`UTC`、`+09:00` など）
  - Config の検証関数で `ZoneId.of` に通し、解釈できない値は NeoForge の Config 検証で既定値（空）に戻す（`ZoneId.of` はタグと違い初期化時に使えるので、検証時に確かめられる）
- 対象: **サーバーが整形する日付すべて**。チャット（`list` / `firsts` / admin / F6 lookup / F13 / 世界初のアナウンス）、サーバーログの記録行、Discord（プレーンテキストの「at …」、F4 の `Date` 欄）、F3 の HTML（記録の日付と生成日時）、F26 の CSV の `date` 列
- GUI は従来通り**クライアントのタイムゾーン**（見る人の現地時刻で表示する）。README に「チャット・Discord・出力ファイルは設定のタイムゾーン、GUI は自分の PC のタイムゾーン」と書く
- 保存データは epochMillis のままで、何も変わらない
- 1.2.0 で足す: F9 のダイジェスト・F48 の記録整形・F51・F52 の日付でもこのタイムゾーンを使う（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- `Playtime.formatDate` / `formatRecord` に `ZoneId` を受け取る版を追加する。既存の引数なしの版は `ZoneId.systemDefault()` のまま残し、GUI（`MilestoneScreen`）はそれを使い続ける。サーバー側の呼び出し（`MilestoneCommands`、`Tracker`、`DiscordNotifier` ほか）は Config から得た `ZoneId` を渡す
- `ZoneId` の解決（空 → システムの既定、値 → `ZoneId.of`）は1か所にまとめ、Config の再読み込みに追従する（値ごとにキャッシュしてよい）

---

## F13. ランキングと最近の記録（`top` / `recent`）（候補 P3）

### 仕様
- コマンド追加（OP 不要。権限は F6 の `allowLookup` に従い、false のときは §1 制約11の `isPrivileged` を満たす者だけが使える。満たさない者には T の無効化メッセージ）:

| コマンド | 内容 |
|---|---|
| `/milestonelog top` | 記録数の多い順の上位10人と、実行者自身の順位 |
| `/milestonelog top <item> [<count>]` | そのターゲット（アイテム × 個数、既定 1）を記録した人を、記録時のプレイ時間の短い順に上位10人と、実行者自身の順位 |
| `/milestonelog recent [<n>]` | ワールド全体の最近の記録を、epochMillis の新しい順に n 件（既定 10、範囲 1〜20） |

- `<item>` は既存の `reset` と同じく `IdentifierArgument`（Mod を外したアイテムの記録も指定できる）。`<count>` は 1〜1,000,000
- 集計の対象: 保存データにいる全プレイヤー（オフラインを含む）。記録が0件のプレイヤーは `top` に出さない。F14 で記録を隠しているプレイヤーは、`isPrivileged` を満たさない閲覧者には出さない（閲覧者本人は常に含める）
- 順位の付け方:
  - `top`: 記録数の降順。同数は同じ順位（1, 2, 2, 4 …）とし、表示の並びは名前の辞書順（大文字小文字を区別しない）
  - `top <item>`: 記録時の playTicks の昇順。同じ値は同じ順位とし、並びは epochMillis の昇順（0 = 不明は後ろ）、次に名前
  - 上位10件の後に、実行者の行（「あなたの順位」）を必ず出す。上位10件に入っていても出す。記録が無ければ「未記録」の文面
  - 実行者がプレイヤーでない（コンソール・RCON）場合は、自分の行を出さない
- `recent`: epochMillis が 0（不明、1.0.0 で作られた記録）の記録は含めない。そのキーの世界初の保持者がその記録の本人なら行頭に ★ を付ける
- 名前は保存されている最後の名前（空なら UUID）を `Component.literal` で埋め込む（制約12）
- 補完: `top <item>` はワールドの誰かが記録しているアイテム ID。F6 と同じく、使えないソースには出さない
- 文面（en / ja）:
  - `message.itemmilestonelog.top_header`（%s = 人数）: "Most milestones (%s players):" / 「記録数ランキング（%s人）:」
  - `message.itemmilestonelog.top_entry`（%s = 順位、%s = 名前、%s = 件数）: "%s. %s — %s milestones" / 「%s. %s — %s件」
  - `message.itemmilestonelog.top_self`（%s = 順位、%s = 件数）: "Your rank: #%s (%s milestones)" / 「あなたの順位: %s位（%s件）」
  - `message.itemmilestonelog.top_item_header`（%s = ターゲット表示名、%s = 人数）: "Fastest to record %s (%s players):" / 「%s を早く記録した順（%s人）:」
  - `message.itemmilestonelog.top_item_entry`（%s = 順位、%s = 名前、%s = 記録の整形）: "%s. %s — %s" / 「%s. %s — %s」
  - `message.itemmilestonelog.top_item_self`（%s = 順位、%s = 記録の整形）: "Your rank: #%s (%s)" / 「あなたの順位: %s位（%s）」
  - `message.itemmilestonelog.top_self_none`（`top` で実行者の記録が0件）: "You have no milestones yet." / 「あなたはまだ何も記録していません。」
  - `message.itemmilestonelog.top_item_self_none`（`top <item>` で実行者が未記録）: "You have not recorded this yet." / 「あなたはまだ記録していません。」
  - `message.itemmilestonelog.top_empty`: "Nobody has recorded any milestones yet." / 「まだ誰も記録していません。」
  - `message.itemmilestonelog.recent_header`（%s = 件数）: "Recent milestones (%s):" / 「最近の記録（%s件）:」
  - `message.itemmilestonelog.recent_entry`（%s = 名前、%s = ターゲット表示名、%s = 記録の整形）: "%s — %s (%s)" / 「%s — %s（%s）」。世界初は `recent_entry_world_first`: "★ %s — %s (%s)" / 「★ %s — %s（%s）」
  - `message.itemmilestonelog.recent_empty`: "No dated milestones yet." / 「日付のある記録はまだありません。」
- 1.2.0 で足す: タグ用の `/milestonelog toptag <tag> [<count>]`（F8b と同時）、F12 のホバー、F43 の権限ノード `itemmilestonelog.viewhidden`、F48 のプレイ時間・Day 表記（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 抽出・並べ替え・順位付け（同順位、隠しているプレイヤーの除外、閲覧者本人の扱い）は、PlayerData の集合と閲覧者を受け取る純粋関数にして単体テストする
- 全プレイヤーを走査するのはコマンド実行時だけなので、キャッシュは持たない

---

## F14. 自分の記録を他人から隠す（`privacy`）（候補 P4）

### 仕様
- コマンド追加: `/milestonelog privacy`（OP 不要）。実行するたびに切り替え、切り替え後の状態を返す（既存の `mutebroadcast` と同じ形）
- PlayerData に `hideFromLookup`（boolean、§1.1）を追加する
- 隠している間は、`isPrivileged` を満たさない他のプレイヤーに対して、次に出ない: F6 の lookup（「非公開」の文面を返す）、F6 の補完候補、F13 の `top` / `recent`、F38 の `stats` の数
- 隠していても変わらないもの（README に明記）: 本人の閲覧、`isPrivileged` を満たす者（OP・シングルプレイ / LAN のホスト）の閲覧、admin 系コマンド、`exportall` / `exporthtmlall` / `exportcsvall`、ブロードキャストと世界初のアナウンス、`firsts`、Discord。これらはサーバーの設定や管理者の操作なので、個人の設定では止めない（Discord への投稿だけは、F45 の `discordoptout` で本人が別に止められる）
- Config は追加しない（個人の設定そのものが ON/OFF。既存の `mutebroadcast` と同じ扱い。制約8の例外として明記）
- 文面:
  - `message.itemmilestonelog.privacy_on`: "Your milestones are now hidden from other players (lookup, top, recent and stats)." / 「あなたの記録を他のプレイヤーから隠しました（lookup・top・recent・stats）。」
  - `message.itemmilestonelog.privacy_off`: "Your milestones are now visible to other players." / 「あなたの記録を他のプレイヤーに公開しました。」
  - `message.itemmilestonelog.lookup_hidden`（%s = 名前）: "%s keeps their milestones private." / 「%s は記録を非公開にしています。」
- 1.2.0 で足す: F51 の `info` の人数・順位からの除外、F43 の権限ノード `itemmilestonelog.viewhidden`、F17 のスコアボードは隠していても対象外（`FEATURE_SPEC_1.2.0.md` 参照）

---

## F15. 進捗アラートの個人ミュート（`muteprogress`）（候補 P5）

### 仕様
- コマンド追加: `/milestonelog muteprogress`（OP 不要）。実行するたびに切り替え、切り替え後の状態を返す
- PlayerData に `muteProgressAlerts`（boolean、§1.1）を追加する
- ミュート中は F1 の通知を送らない。**通知済みしきい値（`progressNotified`）の更新は続ける**（解除した直後に、ミュート中に超えた分のアラートがまとめて出ないようにする）
- `progressAlerts`（Config）が false のときは、ミュートの状態にかかわらず何も送らない
- Config は追加しない（F14 と同じ理由）
- 文面:
  - `message.itemmilestonelog.muteprogress_on`: "Progress alerts are now muted." / 「進捗アラートをミュートしました。」
  - `message.itemmilestonelog.muteprogress_off`: "Progress alerts are now enabled." / 「進捗アラートを受け取るようにしました。」

---

## F16. プリセットの進捗表示（`presetprogress`）（候補 P6）

### 仕様
- コマンド追加: `/milestonelog presetprogress <name>`（OP 不要。実行者はプレイヤーに限る。`presetsEnabled` が false なら T の無効化メッセージ）
  - `preset` の下に `progress` を置かない理由: `preset <name>` は任意の ID を取るので、リテラル `progress` を足すと、`progress` という ID の既存のカスタムプリセットが使えなくなる（`Presets.RESERVED_IDS` に足すことになり、既存のファイルが無視される）
- 出力:
  1. 見出し: プリセット表示名と「記録済み X / Y」。Y は F5 の判定対象キー R(S) の件数、X はそのうち records にあるキーの件数（個数まで完全一致。F5 と同じ数え方）
  2. 未記録のキーの一覧（TargetKey の順）。数量ターゲットは現在の所持数を付ける（既存の `target_progress` の文面。`carriedFromCreative` のアイテムは 0。記録の一時停止中も数は出す）。最大 50 行、超えた分は「他 N 件」
  3. R(S) から除いたキー（存在しないアイテム）が1件以上あれば、その件数
  4. すべて記録済みなら、2 の代わりに「すべて記録済み」
- 未知のプリセット名は既存の `preset_unknown` の文面
- 補完: 既存の `preset <name>` と同じ候補
- 文面:
  - `message.itemmilestonelog.presetprogress_header`（%s = 表示名、%s = X、%s = Y）: "%s: %s / %s recorded" / 「%s: 記録済み %s / %s」
  - `message.itemmilestonelog.presetprogress_entry`（%s = ターゲット表示名、進捗つきの場合は `target_progress` で包んだもの）: "- %s" / 「- %s」
  - `message.itemmilestonelog.presetprogress_more`（%s = 件数）: "...and %s more" / 「…ほか %s 件」
  - `message.itemmilestonelog.presetprogress_skipped`（%s = 件数）: "%s target(s) are not counted because the item or tag does not exist in this game." / 「%s 件は、このゲームにアイテムまたはタグが無いため数えていません。」
  - `message.itemmilestonelog.presetprogress_complete`: "All targets are recorded." / 「すべての目標を記録済みです。」
- プリセット表示名の出所は F5 と同じ（組み込みは lang キー、カスタムは JSON の `name`）
- 1.2.0 で足す: F8b の空のタグの扱い、F12 のホバー、F21 のデータパックプリセット、F54 の多言語表示名（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- R(S) の計算は F5 の純粋関数を共用する（同じ数え方を保証するため）

---

## F18. Discord の言語（候補 P8）

### 仕様
- Config `discordLanguage`（文字列、`en_us` / `ja_jp`、既定 `en_us`）。検証関数で同梱の言語に限る
- Discord に送る文面（プレーンテキスト、F4 の Embed の文面と欄名、F19 の制覇通知）をすべて lang キー `discord.itemmilestonelog.*` にし、`discordLanguage` の表から引く
  - **`en_us` の値は 1.0.1 の文面と一字一句同じにする**（既定の設定で投稿の文面が変わらないようにする）。プレーンテキストは `★ Milestone recorded: %s obtained %s at %s` と `★ [World First] %s obtained %s at %s`
- 引くキー（en / ja）:
  - `discord.itemmilestonelog.recorded`（%s = 名前、%s = アイテム、%s = 記録の整形）: 上記 / 「★ マイルストーン記録: %s が %s を入手（%s）」
  - `discord.itemmilestonelog.world_first`（同）: 上記 / 「★ [世界初] %s が %s を入手（%s）」
  - `discord.itemmilestonelog.embed.recorded`（%s = 名前、%s = アイテム）: "%s obtained %s" / 「%s が %s を入手」
  - `discord.itemmilestonelog.embed.world_first`（同）: "★ World First — %s obtained %s" / 「★ 世界初 — %s が %s を入手」
  - `discord.itemmilestonelog.field.playtime` / `field.day` / `field.date`: "Playtime" / "Day" / "Date"、「プレイ時間」/「ゲーム内日数」/「日付」
  - `discord.itemmilestonelog.digest.more`（%s = 件数）: "...and %s more" / 「…ほか %s 件」（F27 が使うため 1.1.0 で足す）
  - `discord.itemmilestonelog.preset_completed`（%s = 名前、%s = プリセット表示名）: "★ %s completed all targets of %s!" / 「★ %s が %s の目標をすべて達成しました！」
- アイテムの表し方は変えない: プレーンテキストは従来通りアイテム ID（`formatDisplayId`、例 `minecraft:diamond ×100`）、Embed（F4）はサーバー側の表示名。専用サーバーはアイテムの英語名しか持たないので、`ja_jp` でも Embed のアイテム名は英語になる（README に明記）
- プレイ時間（`27h 15m 30s`）と記録の整形の「Day」は、英語表記のまま（`Playtime` の既存の書式。この機能では変えない）
- 1.2.0 で足す: F9 のダイジェスト用 lang キー（`digest.title` / `digest.line`）、F8b のタグ表示名、F48 導入後も Discord は英語表記のまま維持（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 文面は `Component.translatable(...).getString()` で作ってはいけない。専用サーバーの `Language` には全 Mod の en_us が読み込まれているため、キーが常に英語に解決され、`discordLanguage` が効かない。`ServerLang` の表から直接テンプレートを取り、Minecraft の翻訳と同じ書式（`%s`、`%1$s` などの位置指定、`%%`）で埋める関数（例 `ServerLang.formatForLang(lang, key, args...)`）を追加する
- 埋め込む値（名前・アイテム名）は、埋め込む前に F4 の Markdown エスケープを通す（テンプレート側の記号はエスケープしない）
- 単体テスト: en_us の出力が 1.0.1 の `String.format` の結果と一致すること、位置指定と `%%` の扱い

---

## F19. Discord へのプリセット制覇通知（候補 P9）

### 仕様
- Config `discordNotifyPresetCompletion`（boolean、既定 false）
- true かつ Webhook が設定されているとき、F5 の制覇を検出したら Discord に1通投稿する。`announcePresetCompletion` と `discordNotifyScope` とは独立（`discordNotifyScope = "world_first"` でも送る）
- 文面: F18 の `discord.itemmilestonelog.preset_completed`。プリセット表示名は、組み込みは lang キー `gui.itemmilestonelog.preset.<id>` を `discordLanguage` の表から引いた値、カスタムは JSON の `name`（無ければ ID）
- F4 の Embed が有効なときは Embed で送る（title = プリセット表示名、description = 上の文面、color = 0x2ECC71（緑系）、footer = プレイヤー名、timestamp = 制覇した時刻）。Markdown のエスケープと長さの上限は F4 と同じ
- `presetsEnabled` が false のとき（F5 が動かない）は送らない。import では送らない
- 送信は既存のキューと送信経路を使う（429 はドロップ、再送しない）
- 1.2.0 で足す: F21 のデータパックプリセット、F54 の多言語表示名、F46 の世界初 Webhook には送らない、F53 の通知音なしフラグは付けない（`FEATURE_SPEC_1.2.0.md` 参照）

---

## F20. API の拡張（世界初フラグ・制覇イベント）（候補 P10）

### 仕様
- `MilestoneRecordedEvent#isWorldFirst()` を追加する。世界初の値を受け取るコンストラクタを追加し、既存の2つのコンストラクタは残して世界初 = false とする（公開 API の互換を保つ）。`Tracker.record` は新しいコンストラクタで、`recordWorldFirstIfAbsent` の戻り値を渡す
  - `worldFirstEnabled` が false のときは新しい世界初が生まれないので、常に false
- `PresetCompletedEvent` を `api` パッケージに追加する（`NeoForge.EVENT_BUS` に投げる。キャンセル不可）
  - 取得できる値: `getPlayer()`（`ServerPlayer`）、`getPresetId()`、`isBuiltin()`
  - F5 の判定で制覇を検出したときだけ、`completedPresets` を更新した後に投げる。通知の設定（`announcePresetCompletion` / `notifyPlayer`）には関係なく投げる。import・F23 の undo では投げない
  - 同じ記録で `MilestoneRecordedEvent` も起きる。順序は `MilestoneRecordedEvent` → `PresetCompletedEvent`
- Javadoc と README の API の節に、KubeJS での使用例（世界初なら報酬、制覇なら報酬）を追加する
- 既存の公開メソッド・コンストラクタは変えない（追加のみ）
- F32 で、記録の原因（`getCause()`）を受け取るコンストラクタをさらに足す。F20 のコンストラクタは原因を `GAMEPLAY` とする（F32）

---

## F22. 全員分の import（`importall`）（候補 P12）

### 仕様
- コマンド追加: `/milestonelog importall <fileName>`（権限レベル2。既存の `exportall` と同じ `.requires`）。`exportImportEnabled` が false なら T の無効化メッセージ
- 読むファイル: exports フォルダの中の、`exportall` が書く形式（`format` と `players` 配列を持つ）のファイル。ファイル名の検証は既存の `import` と同じ（`isSafeFileName`、exports フォルダの外を指さない）。1人分の形式を渡したら既存の `import_invalid_format`
- `players` の各要素は、1人分の export と同じ構造（`player` / `targets` / `records`）。既存の1人分のパース処理で読み、`player.uuid` が無い・不正な要素はスキップして件数を数える
- マージは UUID ごとに既存の `import` と同じ規則（ターゲットは和集合で個人ターゲットの上限まで、記録は playTicks の小さい方）。保存データにいない UUID は新しく作り、`lastKnownName` にファイルの名前を入れる
- ファイルサイズの上限は 32 MiB（1人分の 10 MiB より大きくする。`exportall` の出力は人数に比例して大きくなるため）。超えたら既存の `import_failed`
- 既存の `import` と同じく、イベント・Discord・F1 / F5 は動かさず、記録から新しい世界初を登録しない。ファイルの `worldFirsts`（F31 で `exportall` に足す）があれば、F31 の規則で世界初を戻す。1.0.1 の `exportall` のファイルには世界初が無いので、戻らない（README に明記）
- 終わったら、オンラインのプレイヤーに再同期する
- 文面: `message.itemmilestonelog.importall_done`（%s = ファイル名、%s = プレイヤー数、%s = 追加したターゲット数、%s = 更新した記録数、%s = 追加・更新した世界初の数（F31）、%s = スキップした要素数）: "Imported %s: %s player(s), %s target(s) added, %s record(s) updated, %s world first(s) added or updated, %s entr(ies) skipped." / 「%s から %s 人分を読み込みました: 目標 %s 件を追加、記録 %s 件を更新、世界初 %s 件を追加・更新、%s 件をスキップ。」
- サーバーの引っ越しでオンラインモードとオフラインモードが変わると UUID が変わり、別のプレイヤーとして読み込まれる。README に書く
- 1.2.0 で足す: F8a/F8b のタグキーの読み込み、F17 のスコアの書き込み（F7 は動かさない）、F43 の権限ノード `itemmilestonelog.import.all`（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- サーバースレッドで同期的に読む（管理者の操作で、頻度は低い）。大きなファイルでは一時的に止まり得ることを README に書く
- パースは既存の `parseSinglePlayerExport` の中身を「JSON オブジェクト → ImportPayload」の関数に切り出して共用する
- 単体テスト: `exportall` の出力を読み戻せること、UUID の無い要素のスキップ、1人分の形式の拒否

---

## F23. リセットの取り消し（`undo`）（候補 P13）

### 仕様
- コマンド追加: `/milestonelog undo`（OP 不要。実行者はプレイヤーに限る）
- 取り消せるもの: 自分の**直前の** `reset <item> [count]` / `resetall`（コマンド）と、GUI の記録のリセット・全リセット、F28 の `presetremove` / `removeall`。**10 分以内**に限る。1段階だけ（新しいリセットか一括削除をすると、前のものは取り消せなくなる）
  - 何も消さなかった操作（0 件の `resetall` や `removeall`、失敗した `reset`）は、取り消しの対象を上書きしない（誤って打った空の操作で、直前の取り消しを失わないようにする）
- admin による他プレイヤーのリセット（`admin <player> reset` 等）は対象外
- 戻すもの:
  - 消えた記録。取り消しまでに同じキーが再び記録されていたら、playTicks の小さい方を残す（import と同じ規則）
  - F5 の `completedPresets`（リセットで消えた分を和集合で戻す）
  - F1 の `progressNotified`: 取り消した後に**未記録のまま**のキーについて、今エントリが無ければリセット前の値を戻す（記録が戻ったキーのエントリは §1.1 の規則どおり持たない）
  - 世界初はリセットで消えないので対象外
  - F28 の一括削除の取り消し: 外したターゲットを個人ターゲットに戻す（既にあるものは重複させない。上限 1024 件を超える分は戻さず、件数を `undo_done_targets` の後ろに `preset_applied_limit` と同じ形で足す）。外したときに消した `progressNotified` は、未記録のキーについて今エントリが無ければ戻す
- 取り消しでは、イベント・通知・Discord・F5 を動かさない。GUI に再同期する
- 状態はメモリだけに持つ（プレイヤー UUID ごとに1件）。ログアウトしても 10 分以内なら取り消せる。サーバーを止めると消える
- チャットのコマンドでリセット・一括削除が成功したときは、既存の完了メッセージの後に1行足す: `message.itemmilestonelog.undo_hint`（"You can undo this within 10 minutes with /milestonelog undo." / 「10 分以内なら /milestonelog undo で元に戻せます。」）。GUI のフィードバックは変えない
- 文面:
  - `message.itemmilestonelog.undo_done`（%s = 戻した記録の数）: "Restored %s record(s) from your last reset." / 「直前のリセットから記録を %s 件戻しました。」
  - `message.itemmilestonelog.undo_done_targets`（%s = 戻した目標の数）: "Restored %s target(s) from your last bulk removal." / 「直前の一括削除から目標を %s 件戻しました。」
  - `message.itemmilestonelog.undo_nothing`: "Nothing to undo. Only your last reset or bulk removal within the past 10 minutes can be undone." / 「取り消せるものがありません。取り消せるのは、10 分以内の直前のリセットか一括削除だけです。」
- Config は追加しない（コマンドを使わなければ何も起きない。制約8の例外として明記）
- 1.2.0 で足す: F8b の `resettag` の取り消し対象化、F17 のスコアの書き込み（F2 / F7 は動かさない。管理者のリセット復旧は F10 のバックアップで行う）（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- リセットの処理（`MilestoneService.resetRecord` / `resetAllRecords`、F28 の一括削除）で、消す前の内容を記録しておく。時刻はサーバーの単調時刻（`System.nanoTime`）で判定する
- 戻す処理は「スナップショット、現在の PlayerData → 新しい PlayerData」の純粋関数にして単体テストする（再記録との競合、`progressNotified` の扱い）

---

## F26. CSV 出力（候補 P18）

### 仕様
- コマンド追加: `/milestonelog exportcsv`（自分、OP 不要）と `/milestonelog exportcsvall`（全員、権限レベル2）。権限・出力先・ファイル名の規則は F3 と同じ（拡張子は `.csv`、全員分は `all-players.csv`）。`exportImportEnabled` が false なら T の無効化メッセージ
- 形式: UTF-8（BOM 付き。表計算ソフトで日本語が化けないように）、改行は CRLF、RFC 4180 のクォート（`,` `"` 改行を含むセルは `"` で囲み、`"` は `""` にする）
- 1行 = 1記録。列（見出し行あり）: `player_uuid`, `player_name`, `item_id`, `count`, `item_name`, `play_ticks`, `playtime`, `day`, `epoch_millis`, `date`, `world_first`
  - `item_name` はサーバー側の `getString()`（F3 と同じ）。`playtime` は `Playtime.format`。`day` は表示と同じ 1 始まり（保存値 + 1）、不明なら空。`epoch_millis` と `date`（F11 のタイムゾーンの `yyyy-MM-dd`）は不明（0）なら空。`world_first` は `true` / `false`（その記録の本人がそのキーの世界初の保持者か）
  - 並びはプレイヤー（名前の辞書順）、次に playTicks の昇順
  - ターゲット（未記録のもの）は含めない
- **数式の注入対策**: 文字列のセルが `=` `+` `-` `@`、タブ、CR で始まる場合は先頭に `'` を付ける（オフラインモードのプレイヤー名や Mod のアイテム名が対象になり得る）。数値の列には適用しない（値が負になることは無い）
- 書き込みは一時ファイル + 移動（既存の export と同じ）。失敗時のメッセージも既存の export と同じ。完了の文面は既存の `export_done` / `exportall_done` を使う
- 1.2.0 で足す: F8b のタグターゲットの表示名、F43 の `exportcsvall` 権限ノード `itemmilestonelog.export.all`（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- CSV の組み立て（エスケープ、数式の対策、BOM、CRLF）は純粋関数にして単体テストする

---

## F27. 同時に起きた記録の通知をまとめる（候補 P19）

### 仕様
- 背景: `Tracker.record` は記録1件ごとに、本人へのメッセージ・ブロードキャスト・世界初のアナウンス・サウンド・Discord の投稿を行う。所持しているアイテムは次のスキャンで記録されるので、プリセットの適用・`defaultTargets` の追加・導入直後のログイン・チェストからの一括移動で、数十件が同じ tick に記録される。Discord は短時間に多数の投稿を受けると HTTP 429 を返し、`DiscordNotifier.send` はそれを捨てるので、その大半が届かない
- Config `groupSimultaneousRecords`（boolean、**既定 true**）。false なら 1.0.1 と同じく記録1件ごとに通知する
  - 既定を true にする理由: 既定の設定のままで、まとめて記録された分が Discord に届かない状態を直すため。1.0.1 の挙動が変わるので CHANGELOG に書く（§1.2 の例外）
- true のとき、人に見せる通知（本人へのメッセージ、ブロードキャスト、世界初のアナウンス、サウンド、Discord、F5 の制覇の通知、F19）は、記録の確定時には送らずにプレイヤーごとに溜め、`ServerTickEvent.Post` でまとめて送る
  - **経路ごとに**、同じ tick に同じプレイヤーで送るものが **3件以上**ならまとめて1通にし、2件以下なら従来の文面で1件ずつ送る。経路ごとの件数: 本人・ブロードキャストは記録の件数、世界初のアナウンスは世界初の件数、Discord は `discordNotifyScope` で送る対象になった件数
  - サウンドはプレイヤーごとに1 tick に1回だけ鳴らす
  - 各経路の ON/OFF（`notifyPlayer`、`broadcastMilestones`、`muteBroadcasts`、`announceWorldFirst`、`milestoneSoundEnabled`、`discordNotifyScope`）は従来通り
  - 送る順: 本人 → ブロードキャスト → 世界初のアナウンス → F5 の制覇 → Discord（記録 → F19）
  - 送る時点でオフラインになっている受信者には送らない（本人へのメッセージとサウンドを含む）。Discord は送る
- まとめないもの: `MilestoneRecordedEvent`・F20・F32 のイベント、サーバーログの記録行は、従来通り記録1件ごとに、記録の確定時に行う。F1 の進捗アラートもまとめない（F1 のベースラインにより、一度に多数は出ない）
- まとめた文面（チャット）:
  - 一覧の作り方: 記録したターゲットの表示名（`MilestoneService.targetDisplayName`）を TargetKey の順に最初の5件まで `message.itemmilestonelog.list_separator`（en ", " / ja 「、」）でつなぎ、6件目以降があれば末尾に `message.itemmilestonelog.list_more`（%s = 残りの件数。en ", and %s more" / ja 「 ほか %s 件」）を付ける
  - `message.itemmilestonelog.recorded_group`（%s = 件数、%s = プレイ時間、%s = 一覧）: en "★ %s milestones recorded at playtime %s: %s" / ja 「★ マイルストーン記録: %s 件を累計プレイ時間 %s で初取得: %s」
  - `message.itemmilestonelog.recorded_group_broadcast`（%s = プレイヤー名、%s = 件数、%s = プレイ時間、%s = 一覧）: en "★ %s recorded %s milestones at playtime %s: %s" / ja 「★ %s が %s 件を累計プレイ時間 %s で初取得: %s」
  - `message.itemmilestonelog.world_first_group`（%s = プレイヤー名、%s = 件数、%s = 記録の整形、%s = 一覧（世界初のものだけ））: en "★ World First! %s first obtained %s targets at %s: %s" / ja 「★ 世界初！%s が %s 件を %s にワールド初取得しました: %s」
  - 同じ tick の記録はプレイ時間・Day・日付がすべて同じなので、1つだけ出す
- まとめた文面（Discord）。言語は F18 の `discordLanguage`、日付は F11、Markdown のエスケープと長さの上限は F4 と同じ:
  - プレーンテキスト: 1行目 `discord.itemmilestonelog.recorded_group`（%s = 名前、%s = 件数、%s = 記録の整形）: en "★ Milestones recorded: %s obtained %s targets at %s" / ja 「★ マイルストーン記録: %s が %s 件を入手（%s）」。2行目以降に1件1行で `discord.itemmilestonelog.group_line`（%s = アイテム。en "- %s" / ja 「- %s」）、世界初は `discord.itemmilestonelog.group_line_world_first`（en "- ★ [World First] %s" / ja 「- ★ [世界初] %s」）。アイテムは従来通り `formatDisplayId`
  - Embed（F4 が有効なとき）: Embed は1件だけにし、title = `discord.itemmilestonelog.embed.group_title`（%s = 名前、%s = 件数。en "%s recorded %s milestones" / ja 「%s が %s 件を記録」）、description = 上と同じ行（アイテムはサーバー側の表示名）、fields・footer・timestamp は F4 と同じ、color は世界初を含めば金系、含まなければ青系
  - どちらも行は最初の 20 件までにし、残りは F18 の `discord.itemmilestonelog.digest.more` の1行にする。上限（プレーンテキスト 2000 文字、description 4096 文字）を超える場合は末尾の行を削って「ほか N 件」の N を増やす
  - 1件ごとに Embed を分けない理由: Discord は1通の Embed を 10 件まで、全 Embed の文字数の合計を 6000 文字までに制限している（Discord の開発者向け文書の Message の Embed Limits）。1件の Embed に行を並べれば、この制限を考えずに済む
- F5 の制覇の通知と F19 は、まとめずにプリセットごとに送る（1人が同じ tick に複数のプリセットを制覇することはまれなため）
- 1.2.0 で足す: F7 のコマンド実行より先に F27 を送信、F2・F7・F17 はまとめない、F8b のタグ表示名、F12 のホバー、F46 の送り先別まとめ、F48 のプレイ時間・Day 表記、F53 の通知音なしフラグ（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 溜める場所はサーバースレッドだけで使う `Map<UUID, PendingNotifications>`（挿入順）。サーバーの停止時（`ServerStoppingEvent`）に空にする（送らずに捨てる）
- 「溜めた内容 → 経路ごとに送るもの（1件ずつか、まとめた1通か）」は純粋関数にして単体テストする（しきい値 3、経路ごとの件数、最初の5件と「ほか N 件」、Discord の 20 行と文字数の上限）

---

## F28. 目標を一括で外す（`presetremove` / `removeall`）（候補 P20）

### 仕様
- 背景: プリセットの適用は一度に多数のターゲット（上限 1024 件）を足せるが、外す手段は `remove` と GUI の1件ずつしかない
- `/milestonelog presetremove <name>`（OP 不要。実行者はプレイヤーに限る）: そのプリセットのキーを個人ターゲットから外す
  - キーは `applyPreset` と同じく正規化して照合する。アイテムがレジストリに無いキーも、個人ターゲットにあれば外す
  - `presetsEnabled` が false なら T の無効化メッセージ。未知のプリセット名は既存の `preset_unknown`。補完は `preset <name>` と同じ
- `/milestonelog removeall`（OP 不要。実行者はプレイヤーに限る）: 個人ターゲットをすべて外す
- どちらも、記録・`defaultTargets`・世界初は変えない。外したキーの `progressNotified` は §1.1 の `remove` と同じく消す
- どちらも F23 の `undo` で元に戻せる（F23 の取り消しの対象に加える）。1件も外さなかった場合は、取り消しの対象を上書きしない（F23）。成功したら、完了の文面の後に F23 の `undo_hint` を1行足す
- GUI には足さない（デザイン凍結）
- 文面:
  - `message.itemmilestonelog.presetremove_done`（%s = プリセット ID、%s = 外した件数）: en "Preset '%s': stopped tracking %s target(s). Records were kept." / ja 「プリセット '%s' の目標を %s 件外しました。記録は残っています。」
  - `message.itemmilestonelog.removeall_done`（%s = 外した件数）: en "Stopped tracking %s personal target(s). Records and default targets were kept." / ja 「個人の目標を %s 件外しました。記録とデフォルト目標は残っています。」
- 1.2.0 で足す: F8b のプリセットのタグキーの照合・削除（`FEATURE_SPEC_1.2.0.md` 参照）

---

## F29. `help` コマンド（候補 P21）

### 仕様
- `/milestonelog help`（OP 不要）: そのソースが使えるサブコマンドだけを、1行ずつ短い説明つきで出す
  - 出さないもの: Brigadier の `requires` を満たさないもの（`CommandNode#canUse`）、T のスイッチで無効なもの（`exportImportEnabled` = false の export / import 系、`presetsEnabled` = false の `preset` / `presetprogress` / `presetremove`）、`allowLookup` = false で `isPrivileged` を満たさない者の `lookup` / `top` / `recent` / `stats`、`allowDiscordOptOut` = false の `discordoptout`（F45）、プレイヤー以外のソースにはプレイヤー専用のもの
  - 並びは下の表の順。各行をクリックすると、チャット欄に `/milestonelog <name> ` が入る（`ClickEvent` の `suggest_command`。実行はしない。バニラクライアントでも動く）
- `/milestonelog help <subcommand>`: そのサブコマンドの説明と、Brigadier の `CommandDispatcher#getSmartUsage` で得た書き方（そのソースが使える引数だけ）を出す。補完は上の一覧に出るものだけ。未知の名前や使えないものは `help_unknown`
- 文面:
  - `message.itemmilestonelog.help_header`: en "Item Milestone Log commands (/milestonelog or /iml):" / ja 「Item Milestone Log のコマンド（/milestonelog または /iml）:」
  - `message.itemmilestonelog.help_entry`（%s = コマンド（`/milestonelog add` の形）、%s = 説明）: en "%s — %s" / ja 「%s — %s」
  - `message.itemmilestonelog.help_usage`（%s = 書き方）: en "Usage: %s" / ja 「使い方: %s」
  - `message.itemmilestonelog.help_unknown`（%s = 名前）: en "Unknown command '%s'. Use /milestonelog help to see the list." / ja 「コマンド '%s' はありません。一覧は /milestonelog help で見られます。」
- 説明の lang キーは `message.itemmilestonelog.help.<name>`（ルートの `/milestonelog` は `help.summary`）。版は §2.1 でそのコマンドが入る版:

| name | 版 | プレイヤー専用 | en | ja |
|---|---|---|---|---|
| summary | 1.1.0 | ○ | Show your playtime and milestone progress | 累計プレイ時間と記録の進み具合を表示 |
| playtime | 1.1.0 | ○ | Show your cumulative playtime | 累計プレイ時間を表示 |
| add | 1.1.0 | ○ | Track an item (optionally a quantity) | アイテムを目標に追加（個数も指定できる） |
| addhand | 1.1.0 | ○ | Track the item in your main hand | 手に持っているアイテムを目標に追加 |
| remove | 1.1.0 | ○ | Stop tracking an item | 目標から外す |
| removeall | 1.1.0 | ○ | Stop tracking all your personal targets | 個人の目標をすべて外す |
| targets | 1.1.0 | ○ | List your targets and progress | 目標と進み具合の一覧 |
| list | 1.1.0 | ○ | List your recorded milestones | 記録の一覧 |
| preset | 1.1.0 | | Apply, list, save or delete presets | プリセットの適用・一覧・保存・削除 |
| presetremove | 1.1.0 | ○ | Stop tracking the targets of a preset | プリセットの目標をまとめて外す |
| presetprogress | 1.1.0 | ○ | Show your progress on a preset | プリセットの進み具合を表示 |
| reset | 1.1.0 | ○ | Clear one of your records | 記録を1件消す |
| resetall | 1.1.0 | ○ | Clear all your records (targets are kept) | 記録をすべて消す（目標は残る） |
| undo | 1.1.0 | ○ | Undo your last reset or bulk removal (within 10 minutes) | 直前のリセット・一括削除を取り消す（10 分以内） |
| firsts | 1.1.0 | | List world-first milestones | 世界初の記録の一覧 |
| top | 1.1.0 | | Show milestone rankings | 記録のランキング |
| recent | 1.1.0 | | Show the latest milestones on this server | 最近の記録 |
| stats | 1.1.0 | | Show server-wide milestone statistics | サーバー全体の統計 |
| lookup | 1.1.0 | | View another player's milestones | 他のプレイヤーの記録を見る |
| privacy | 1.1.0 | ○ | Hide or show your milestones to other players | 自分の記録を他のプレイヤーから隠す・公開する |
| mutebroadcast | 1.1.0 | ○ | Mute or unmute other players' milestone announcements | 他のプレイヤーの記録のお知らせを止める・戻す |
| muteprogress | 1.1.0 | ○ | Mute or unmute your progress alerts | 進捗アラートを止める・戻す |
| discordoptout | 1.1.0 | ○ | Stop or resume posting your milestones to Discord | 自分の記録を Discord に投稿しない・投稿する |
| export | 1.1.0 | ○ | Export your milestones to a JSON file | 自分の記録を JSON ファイルに書き出す |
| exporthtml | 1.1.0 | ○ | Export your milestones as an HTML page | 自分の記録を HTML に書き出す |
| exportcsv | 1.1.0 | ○ | Export your milestones as a CSV file | 自分の記録を CSV に書き出す |
| import | 1.1.0 | ○ | Import milestones from an export file | 書き出したファイルから記録を読み込む |
| exportall | 1.1.0 | | Export every player's milestones (admin) | 全員の記録を書き出す（管理者） |
| exporthtmlall | 1.1.0 | | Export every player's milestones as HTML (admin) | 全員の記録を HTML に書き出す（管理者） |
| exportcsvall | 1.1.0 | | Export every player's milestones as CSV (admin) | 全員の記録を CSV に書き出す（管理者） |
| importall | 1.1.0 | | Import every player's milestones from an export (admin) | 全員分の書き出しファイルを読み込む（管理者） |
| admin | 1.1.0 | | View and manage other players' data (admin) | 他のプレイヤーのデータを見る・操作する（管理者） |
| prunedata | 1.1.0 | | Delete data of long-absent players who have nothing recorded (admin) | 長く来ていない、記録の無いプレイヤーのデータを消す（管理者） |
| discordtest | 1.1.0 | | Send a test message to the configured Discord webhook (admin) | Discord Webhook へテスト送信する（管理者） |
| help | 1.1.0 | | Show this list, or how to use one command | この一覧、またはコマンドの使い方を表示 |

- 1.2.0 で足す: 1.2.0 のサブコマンド6件（`info` / `addtag` / `removetag` / `resettag` / `toptag` / `renameitem`）を表と lang キーに追加、F43 の権限ノードに自動追従（`FEATURE_SPEC_1.2.0.md` 参照）
- Config は追加しない（使わなければ何も起きない。制約8の例外）

### 実装メモ
- 表（name、プレイヤー専用か、従うスイッチ）は1か所の定数にし、「すべての登録済みのサブコマンドが表にある」「表のすべての name に en / ja の説明がある」を単体テストする（コマンドを足したときの書き忘れを防ぐ）

---

## F30. 長い一覧のページ分けと絞り込み（候補 P22）

### 仕様
- 背景: `list` / `targets` / `firsts` / `admin players` / `admin <player> list` / `admin <player> targets` / F6 の `lookup` は、全件を1行ずつ送る。Minecraft のチャット欄が保持するのは最近の 100 件のメッセージなので、数百件のターゲットを持つ Mod パックでは先頭が読めない
- 次のコマンドに、省略可能なページ番号（`IntegerArgumentType.integer(1)`）を足す。**ページ番号を省略したときは従来通り全件**で、出力は 1.0.1 と同じ（制約2）:
  - `list [<page>]`、`targets [<page>]`、`firsts [<page>]`、`lookup <player> [<page>]`
  - `admin players [<page>]`、`admin <player> list [<page>]`、`admin <player> targets [<page>]`
- 絞り込み: `targets pending [<page>]`（未記録だけ）と `targets done [<page>]`（記録済みだけ）。行の形式は `targets` と同じ
- ページを指定したとき:
  - 1ページ 15 件。並びは全件のときと同じ。見出しの行は全件のときと同じ（件数は全体の件数）
  - 最後に1行、`message.itemmilestonelog.page_footer`（%s = ページ、%s = 全ページ数）を出し、前後のページがあれば、その後ろにクリックできる `[前へ]` / `[次へ]` を付ける。クリックで、同じコマンドのページ番号だけを変えたもの（`/milestonelog list 3` の形。プレイヤー名は入力されたとおり）を実行する（`ClickEvent` の `run_command`。下線付き）
  - 全ページ数を超える番号は `message.itemmilestonelog.page_out_of_range`（%s = ページ、%s = 全ページ数）
- `run_command` の確認画面: 26.x のクライアントは、クリックされたコマンドを自分の持つコマンドの木で検査し、権限が要るもの（解析できないもの・署名が要るものも）は実行の前に確認画面を出す（26.1.2 のクライアントの `ClientPacketListener.sendUnattendedCommand` / `verifyCommand` で確認）。`admin` 系の `[次へ]` は OP でも確認画面が出る。動作に問題はないので README に書く。権限の要らない `list` などはそのまま実行される。1.21.1 のクライアントでも動作を確かめる
- `ClickEvent` の API はバージョンで異なる（1.21.1 は `new ClickEvent(ClickEvent.Action.RUN_COMMAND, ...)`、26.x は `new ClickEvent.RunCommand(...)`。26.1.2 のクライアントに `ClickEvent$RunCommand` があることを確認済み）。F29 の `suggest_command` も同様
- 文面:
  - `message.itemmilestonelog.page_footer`: en "Page %s of %s" / ja 「%s / %s ページ」
  - `message.itemmilestonelog.page_prev`: en "[< Prev]" / ja 「[< 前へ]」、`message.itemmilestonelog.page_next`: en "[Next >]" / ja 「[次へ >]」
  - `message.itemmilestonelog.page_out_of_range`: en "There is no page %s (pages 1–%s)." / ja 「%s ページはありません（1〜%s ページ）。」
  - `message.itemmilestonelog.targets_pending_header`（%s = 件数）: en "Targets not yet obtained (%s):" / ja 「未取得の目標（%s件）:」
  - `message.itemmilestonelog.targets_done_header`（%s = 件数）: en "Targets already recorded (%s):" / ja 「記録済みの目標（%s件）:」
  - `message.itemmilestonelog.targets_pending_none`: en "All your targets are recorded." / ja 「すべての目標を記録済みです。」
  - `message.itemmilestonelog.targets_done_none`: en "None of your targets are recorded yet." / ja 「記録済みの目標はまだありません。」
  - ターゲットが1件も無いときは、どちらも既存の `no_targets`
- Config は追加しない（ページ番号を付けなければ従来通り。制約8の例外）
- 1.2.0 で足す: F8b のタグ表示名、F12 のホバー、F48 のプレイ時間・Day 表記（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- ページの切り出しと全ページ数の計算、クリックで実行するコマンド文字列の組み立ては純粋関数にして単体テストする

---

## F31. `exportall` に世界初を含め、`importall` で戻す（候補 P23）

### 仕様
- 背景: `MilestoneExport.exportAll` は `players` だけを書き、世界初を含めない。サーバーの引っ越しやワールドの作り直しで、世界初だけが失われる
- `all-players.json` に `worldFirsts` 配列を足す。各要素: `key`（エンコード済み TargetKey、例 `minecraft:diamond#100`）、`uuid`、`name`（記録時の名前）、`ticks`、`day`、`epoch`（記録の `ticks` / `day` / `epoch` と同じ名前と意味）
  - `format` の値は変えない（読む側は知らないキーを無視する。1.0.1 の1人分の `import` は、`players` を持つファイルをそもそも拒否する）
- F22 の `importall` は世界初も読み込む
  - 既に同じキーの世界初がある場合: 両方の epochMillis が分かれば早い方、どちらかが不明（0）なら既存の方を残す
  - パースできない要素はスキップし、F22 のスキップの件数に数える
  - 新しく入った世界初では、通知・Discord・イベントを動かさない
  - `worldFirstEnabled` が false でも読み込む（このスイッチは新しい記録を止めるもので、既存のデータの移動は止めない）
- F22 の完了の文面に世界初の件数を加える（F22 の節の `importall_done` に反映済み）
- 1.2.0 で足す: F8a/F8b のタグの世界初の読み込み、F17 の世界初の件数の書き込み（F2 は動かさない）（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 世界初の読み込みとマージは「現在の世界初、読み込んだ要素 → 結果と件数」の純粋関数にして単体テストする（早い方の規則、不明な epochMillis、壊れた要素）

---

## F32. 書き込み用の API（目標の追加・削除、記録）（候補 P24）

### 仕様
- 背景: `ItemMilestoneLogApi` は読み取り専用（`getRecord` / `getTargets` / `getTargetKeys` / `getWorldFirst`）。KubeJS やクエスト Mod から「この章に進んだらターゲットを足す」「クエストの報酬で記録する」といった連携ができない。AE2 などのストレージへの直接搬入はインベントリに入らないので検出できないが、パック製作者が自分で検出して記録させれば補える
- 追加するメソッド（すべて `ItemMilestoneLogApi` の static メソッド）:
  - `boolean addTarget(MinecraftServer server, UUID player, TargetKey target)`: 個人ターゲットに足す。検証と上限はコマンドの `add` と同じ（アイテムがレジストリにあり、空気でない。上限 1024 件）。そのプレイヤーのデータが無ければ作る（F22 と同じ）。足したら true
  - `boolean removeTarget(MinecraftServer server, UUID player, TargetKey target)`: 個人ターゲットから外す（`defaultTargets` のキーは外せない）。§1.1 の `remove` と同じ後片付けをする。外したら true
  - `boolean recordNow(ServerPlayer player, TargetKey target)`: 所持を確かめずに、通常の記録の経路で記録する（通知・世界初・F27・イベント・Discord・F1 の後片付け・F5）。次のときは何もせず false: 実効ターゲットに無い、記録済み、記録の一時停止中（`recordingPaused`）、FakePlayer
  - どれも、オンラインのプレイヤーの GUI に再同期する
- スレッド: サーバースレッドからだけ呼べる。`server.isSameThread()`（`recordNow` は `player.level().getServer()` で判定）が false なら `IllegalStateException` を投げる（読み取りの API は従来通り検査しない）
- null の扱い: 既存の API と同じく、`server` / `player` が null なら `NullPointerException`。`target` が null なら false
- 再入: `MilestoneRecordedEvent` の処理の中から `recordNow` を呼んでもよい（連鎖する記録）。入れ子の深さが 16 を超える呼び出しは false を返して warn ログを出す（無限の連鎖でスタックを使い切らないようにする）
- 記録の原因: `MilestoneRecordedEvent#getCause()` と列挙型 `RecordCause`（`GAMEPLAY` / `API` / `ADMIN_GRANT`）を `api` パッケージに足す。F20 のコンストラクタに加えて原因を受け取るコンストラクタを足し、既存と F20 のコンストラクタは `GAMEPLAY` とする。`recordNow` は `API`、F33 の `grant` は `ADMIN_GRANT`
  - 理由: 記録ごとに報酬を配るスクリプトが、管理者の付与や自分の API 呼び出しを区別できるようにする
- 1.1.0 ではタグのキーに false を返す
- 1.2.0 で足す: F8a/F8b のタグキー対応、`recordNow` からの F2 トリガー・F7 コマンド・F17 スコアの実行（`FEATURE_SPEC_1.2.0.md` 参照）
- イベントの Javadoc（「通常のプレイで記録が確定したとき」）と README の API の節を、`recordNow` と `grant` でも起きることに合わせて直す。README に KubeJS の例（クエストの完了で `recordNow`）を書く
- Config は追加しない（他の Mod が使う側で opt-in する。T の方針と同じ）

### 実装メモ
- `recordNow` は `Tracker.record` を共用する（記録の経路を2つにしない）
- 単体テスト: 戻り値の各条件、スレッドの検査、入れ子の深さの上限

---

## F33. 管理者による他プレイヤーの目標の操作と記録の付与（候補 P25）

### 仕様
- 背景: 管理者は他プレイヤーの記録を見る・消すことはできるが、ターゲットを足す・記録を付与することはできない。イベントで特定の人に目標を配る、Mod を入れる前に手に入れていた分を個別に直す、といった運用ができない
- `admin <player>` の下に追加する（`admin` は既存どおり権限レベル2）:

| コマンド | 動作 | オフラインの対象 |
|---|---|---|
| `admin <player> add <item> [<count>]` | 対象の個人ターゲットに足す（`<item>` は `add` と同じ `ItemArgument`） | 可 |
| `admin <player> remove <item> [<count>]` | 対象の個人ターゲットから外す（`<item>` は `remove` と同じ `IdentifierArgument`） | 可 |
| `admin <player> preset <name>` | 対象にプリセットを適用する（`presetsEnabled` が false なら T の無効化メッセージ） | 可 |
| `admin <player> grant <item> [<count>]` | 所持を確かめずに記録する（`<item>` は `ItemArgument`） | **不可** |

- `add` / `remove` / `preset` の検証・上限・後片付けは、本人のコマンドと同じ。対象がオンラインなら GUI に再同期する
- `grant`:
  - 対象がオンラインのときだけ使える（オフラインなら `admin_grant_offline`）。理由: `MilestoneRecordedEvent` は `ServerPlayer` を必要とし、記録のプレイ時間もオンラインでなければ正しく得られない（候補の段階の「オフラインなら最後の記録のプレイ時間」は、F13 の早い順の順位を実際より早く見せるのでやめた）
  - そのキーが対象の実効ターゲットに無ければ、先に個人ターゲットに足す（上限に達していたら `targets_limit_reached` と同じ理由で失敗）。記録済みなら `admin_grant_already`
  - 記録の一時停止（クリエイティブ・スペクテイター）は無視する（管理者が明示的に付与するため。F32 の `recordNow` とは異なる）
  - 記録の値はその時点のプレイ時間・Day・時刻。**世界初は登録しない**（管理者の操作で世界初が動かないようにする）
  - 行うこと: 本人への `granted_notice`（`notifyPlayer` に関係なく送る）、`MilestoneRecordedEvent`（原因 `ADMIN_GRANT`、世界初 false）、F1 の後片付け、F5 の判定（制覇したら `completedPresets` の更新、本人への `preset_completed`（`notifyPlayer` が true のとき）と F20 の `PresetCompletedEvent`）、サーバーログの info 行（実行者・対象・キー）
  - 行わないこと: ブロードキャスト、世界初のアナウンス、サウンド、Discord、F5 の全体アナウンス、F19
  - README に、1.0.1 の「導入前のアイテムの『推定』マークは作らない」という決定（FEATURE_SPEC_1.0.1 F2）とは別物であること（推定ではなく、管理者が明示的に付与する）を書く
- 管理者の操作なので F23 の取り消しの対象にしない
- 文面:
  - `message.itemmilestonelog.admin_added`（%s = 名前、%s = ターゲット表示名）: en "%s now tracks %s." / ja 「%s の目標に %s を追加しました。」
  - `message.itemmilestonelog.admin_removed`（同）: en "%s no longer tracks %s." / ja 「%s の目標から %s を外しました。」
  - `message.itemmilestonelog.admin_already_target`（同）: en "%s already tracks %s." / ja 「%s はすでに %s を目標にしています。」
  - `message.itemmilestonelog.admin_not_target`（同）: en "%s does not track %s as a personal target." / ja 「%s の個人の目標に %s はありません。」
  - `message.itemmilestonelog.admin_preset_applied`（%s = プリセット ID、%s = 名前、%s = 追加数、%s = スキップ数）: en "Preset '%s' applied to %s: %s targets added, %s skipped (item not present in this pack)." / ja 「プリセット '%s' を %s に適用: %s件追加、%s件スキップ（このパックに存在しないアイテム）」。上限で飛ばした分は既存の `preset_applied_limit` を次の行に足す
  - `message.itemmilestonelog.admin_granted`（%s = 名前、%s = ターゲット表示名）: en "Granted to %s: %s" / ja 「%s に記録を付与しました: %s」
  - `message.itemmilestonelog.admin_grant_already`（同）: en "%s has already recorded %s." / ja 「%s はすでに %s を記録しています。」
  - `message.itemmilestonelog.admin_grant_offline`（%s = 名前）: en "%s must be online to be granted a milestone." / ja 「記録を付与するには %s がオンラインである必要があります。」
  - `message.itemmilestonelog.granted_notice`（%s = ターゲット表示名、%s = プレイ時間）: en "An administrator recorded %s for you at playtime %s." / ja 「管理者があなたに %s の記録を付与しました（累計プレイ時間 %s）。」
- Config は追加しない（管理者のコマンド。制約8の例外）
- 1.2.0 で足す: `grant` からの F2 トリガー・F17 スコア更新（F7 は動かさない）、F12 のホバー、F43 の権限ノード `itemmilestonelog.admin`（`FEATURE_SPEC_1.2.0.md` 参照）

---

## F34. 英語・日本語以外の言語を追加しやすくする（候補 P26）

### 仕様
- 背景: 同梱の言語は en_us と ja_jp だけで、`ServerLang.BUNDLED_LANGUAGES` に固定で書かれている。`LangFilesTest` は ja_jp のキーが en_us と完全に一致することを検査するだけで、他の言語のファイルを足しても検査されない
- 同梱の言語の一覧は `ServerLang.BUNDLED_LANGUAGES` の1か所のままにする（F18 の `discordLanguage` の検証もこの一覧を使う）。言語を足す手順は「lang ファイルを置き、この一覧に足す」だけにし、README（翻訳の追加の手順）に書く
  - ビルド時に一覧を生成しない理由: 4バージョンが `common/resources` を共有しており、各バージョンの Gradle に生成の処理を足すより、次のテストで食い違いを止める方が単純で確実なため
- テストを次に変える:
  - lang フォルダの `*.json` の集合と `BUNDLED_LANGUAGES` が一致する（ファイルを置いて一覧に足し忘れる、またはその逆を検出する）
  - en_us を基準に、他のすべての言語で、**en_us に無いキーがあれば失敗**、同じキーのプレースホルダの並び（既存の `extractFormatSlots`）が違えば失敗
  - ja_jp は従来通り en_us と完全に一致する（キーの欠けも失敗）。それ以外の言語は欠けているキーを許す
- 欠けているキーの表示: サーバー側の `ServerLang.lookupFallback` は既にキー単位で en_us に戻る。Mod 入りのクライアントは Minecraft の既定の動作（en_us を読んだ上に選んだ言語を重ねる）で en_us が出る。どちらも変更は不要
- 翻訳の受け入れ方: 機械翻訳は同梱しない。利用者からの提供（PR など）を受け付ける（品質を確かめられないため）。CONTRIBUTING か README に書く
- Config は追加しない（利用者向けの機能ではない）

---

## F35. Discord の送信の改善（Retry-After、送れない Webhook、スレッドへの送信）（候補 P27）

### 仕様
- HTTP 429 のとき: 応答の `Retry-After` ヘッダー（秒。小数を含み得るものとして読む）が 5 秒以下なら、その時間だけ送信用のスレッドで待ち、1回だけ送り直す。ヘッダーが無い・読めない・5 秒を超える、または2回目も 429 なら、従来通り捨てて warn ログを出す
  - 根拠: Discord の文書（Rate Limits）は「再試行の時期は `Retry-After` ヘッダーか本文の `retry_after` で決めること」としている。本文は秒の小数（例 64.57）、ヘッダーは同じ例で整数（65）。送り直しを1回に限る理由: 401・403・429 の応答は「無効なリクエスト」として 10 分間に 10,000 件までに数えられ、超えると IP が一時的に制限される
  - 待つ間、後続の投稿はキューで待つ（キューの上限 100 件と、溢れたら古いものから捨てる動作は従来通り）。サーバーの停止時は既存の `shutdown` の待ち（2 秒）の後に止めるので、待っている投稿は捨てられる
- HTTP 404 または 401 のとき: その URL への送信を止め、warn ログを1回だけ出す（「Webhook が削除されたか URL が誤っている。設定を直すと再開する」）。`discordWebhookUrl` の値（F39 の `env:` を使う場合は解決した後の URL）が変わったら再開する（F49 の `discordtest` が成功したときも再開する）
  - 根拠: Discord の文書は「Webhook が 404 を返したら、その Webhook を再び使わないこと。繰り返すと一時的に制限される」としている。現在のコードは投稿のたびに送り、毎回 warn ログを出す
- スレッド・フォーラムへの送信（README に書くだけ。コードの変更は不要）: Webhook の URL の末尾に `?thread_id=<スレッドの ID>` を付けると、そのスレッドに投稿される。Webhook の送り先がフォーラム（またはメディア）チャンネルの場合は、`thread_id` が必須になる（Discord の文書の Execute Webhook。本 Mod は `thread_name` を送らない）。現在のコードは URL をそのまま使うので動くはずだが、README に書く前に実際の Webhook で確かめる
- Config は追加しない（失われていた投稿を減らすための改善で、切り替える意味が無い。制約8の例外）。Webhook の URL を秘密として扱う既存の方針（ログ・チャットに出さない）は変えない
- 1.2.0 で足す: F46 の2つの Webhook URL ごとの 429・404・401 制御（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- `Retry-After` の解釈（整数、小数、不正な値、上限）は純粋関数にして単体テストする
- 参照した文書: https://docs.discord.com/developers/topics/rate-limits 、https://docs.discord.com/developers/resources/webhook （2026-11-23 に確認。旧 URL の discord.com/developers/docs/... はここへ転送される）

---

## F36. 拾得のたびに行う無駄な処理を減らす（候補 P28）

### 仕様
- 背景: `Tracker.tryRecordForItem`（拾得と、クラフト/精錬の次 tick の確認で呼ばれる）は、そのアイテムが未記録のターゲットかどうかを確かめる**前に**インベントリ全体を数え（`countItemInPossession`）、さらに実効ターゲット（`defaultTargets` + 個人ターゲット）の集合を毎回新しく作っている。ターゲットと関係のないアイテムを拾うたびに、この処理が走る
- 次の順に変える（結果は変えない）:
  1. 記録の一時停止中なら何もしない（従来通り）
  2. そのアイテムの未記録のターゲットを、`defaultTargets` と個人ターゲットを**集合を作らずに**走査して集める
  3. そのアイテムが `carriedFromCreative` に無く、2 が空なら、ここで終える（数えない）
  4. それ以外は従来通り所持数を数え、`carriedFromCreative` の処理と記録を行う
  - 3 で `carriedFromCreative` にあるアイテムを除外しない理由: 従来は、ターゲットでないアイテムでも、拾ったときに「クリエイティブから持ち帰った」印を外す処理（拾う前の所持数が 0 なら印を外す）が走る。これを飛ばすと、後でそのアイテムをターゲットにしたとき、正当に拾った分が数えられなくなる
- 1.2.0 で足す: 2 に、そのアイテムを含むタグの未記録ターゲットも含める（F8b、`FEATURE_SPEC_1.2.0.md` 参照）
- 索引（アイテム ID → キーの一覧）は作らない。ターゲットの変更・記録・Config の変更・F28・F32・F33・F23 など、作り直しが要る箇所が多く、作り直しの漏れの危険が、走査の数マイクロ秒の節約に見合わないため。大量のターゲットを持つプレイヤーが多いサーバーで計測して問題が見つかったら、別に検討する
- Config は追加しない（挙動を変えない内部の改善。制約8の例外）

### 実装メモ
- 単体テスト: ターゲットでないアイテムで数えないこと、`carriedFromCreative` の印が従来通り外れること（拾う前の所持数が 0 のとき）

---

## F37. 古いプレイヤーデータの整理（`prunedata`）（候補 P29）

### 仕様
- 背景: `Tracker.onPlayerLoggedIn` は、参加したすべてのプレイヤーの PlayerData を作る（`MilestoneData.player` の `computeIfAbsent`）。記録もターゲットも無いプレイヤーのデータも残り続け、消す手段は1人ずつの `admin <player> purge` しかない。公開サーバーでは `admin players` の一覧や F13・F38 の集計が、一度来ただけのプレイヤーで膨らむ
- PlayerData に `lastSeen`（long、epochMillis、§1.1）を足し、ログインとログアウトのときに現在時刻を入れる
- `/milestonelog prunedata <days> [confirm]`（権限レベル2。`<days>` は 1〜3650）。`admin <player>` の `<player>` と同じ位置になりプレイヤー名と区別できなくなるため、ルート直下に置く
  - 対象: 次をすべて満たすプレイヤーのデータ
    - 記録が0件、個人ターゲットが0件、`carriedFromCreative` が空
    - 個人の設定が既定のまま（`muteBroadcasts`、F14 の `hideFromLookup`、F15 の `muteProgressAlerts`、F45 の `excludeFromDiscord` がすべて false）
    - オンラインでない
    - 最後にいた時刻が `<days>` 日より前。時刻は `lastSeen`、それが 0（1.1.0 より前のデータ）なら、そのプレイヤーのバニラのプレイヤーデータのファイル（`server.getWorldPath(LevelResource.PLAYER_DATA_DIR)` の `<uuid>.dat`）の更新時刻を使う。どちらも分からなければ対象にしない
  - `carriedFromCreative` が空でないものを除く理由: 消すと、クリエイティブから持ち帰ったアイテムを持ったまま戻ってきたときに、それが記録される。個人の設定があるものを除く理由: 本人の選んだ設定を黙って失わせない
  - フォルダの名前はバージョンで異なる（1.21.1 は `playerdata`、26.1.2 は `players/data`。26.1.2 のクライアントの `LevelResource` で確認）ので、パスを書かずに `LevelResource.PLAYER_DATA_DIR` を使う
  - `confirm` なし: 何も変更せず、対象の件数と、`confirm` を付けて実行する旨を返す
  - `confirm` あり: 対象のデータを消し、件数を返し、サーバーログに info で残す（実行者、件数）。世界初は消さない（記録をリセットした世界初の保持者も対象になり得るが、世界初には名前が保存されているので表示は変わらない）
  - 消されたプレイヤーが再び参加すると、新しいデータが作られ、初回のヒント（`showFirstJoinHint`）がもう一度出る。README に書く
- 文面:
  - `message.itemmilestonelog.prunedata_preview`（%s = 件数、%s = 日数）: en "%s player(s) have no records, no personal targets and no personal settings, and were last seen more than %s day(s) ago. Add 'confirm' to delete their data." / ja 「%s 人分のデータが対象です（記録・個人の目標・個人の設定が無く、最後に来てから %s 日を超えている）。消すには末尾に confirm を付けてください。」
  - `message.itemmilestonelog.prunedata_done`（%s = 件数）: en "Deleted the data of %s player(s)." / ja 「%s 人分のデータを消しました。」
- Config は追加しない（管理者のコマンド。制約8の例外）
- 1.2.0 で足す: F10 のバックアップは書かない、F17 のスコア削除、F43 の権限ノード `itemmilestonelog.prunedata`、F52 による `lastSeen` の参照（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 対象の判定は「PlayerData、オンラインか、最後にいた時刻（不明あり）、現在時刻、日数 → 対象か」の純粋関数にして単体テストする

---

## F38. サーバー全体の統計（`stats`）（候補 P30）

### 仕様
- `/milestonelog stats`（OP 不要。F6 の `allowLookup` に従い、false のときは `isPrivileged` を満たす者だけ）
- 出力:
  1. 記録の総数、記録が1件以上あるプレイヤーの数、世界初の数
  2. 記録した人の多いキーの上位5件（キーごとに、そのキーを記録したプレイヤーの数）。同数は同じ順位（F13 と同じ規則）で、並びは TargetKey の順
- F14 で記録を隠しているプレイヤーは、`isPrivileged` を満たさない閲覧者には 1 と 2 の数に含めない（閲覧者本人は含める。F13 と同じ）。世界初の数は全体の数（`firsts` と同じく公開されている情報のため）
- 誰も記録していなければ F13 の `top_empty`
- 文面:
  - `message.itemmilestonelog.stats_header`: en "Server milestone statistics:" / ja 「サーバー全体の記録の統計:」
  - `message.itemmilestonelog.stats_totals`（%s = 記録の総数、%s = プレイヤー数、%s = 世界初の数）: en "%s milestones by %s player(s), %s world first(s)" / ja 「記録 %s 件（%s 人）、世界初 %s 件」
  - `message.itemmilestonelog.stats_top_header`: en "Most recorded targets:" / ja 「記録した人の多い目標:」
  - `message.itemmilestonelog.stats_top_entry`（%s = 順位、%s = ターゲット表示名、%s = 人数）: en "%s. %s — %s player(s)" / ja 「%s. %s — %s 人」
- Config は追加しない（F6 の `allowLookup` に従う。制約8の例外）
- 1.2.0 で足す: F8b のタグ表示名、F12 のホバー、F43 の権限ノード `itemmilestonelog.viewhidden`（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 集計は F13 の純粋関数と同じ入力（PlayerData の集合、閲覧者）を取る関数にして単体テストする

---

## F39. Discord の Webhook URL を設定ファイルに書かずに済むようにする（候補 P33）

### 仕様
- 背景: `discordWebhookUrl` は設定ファイル（`config/itemmilestonelog-common.toml`、26.3 は `itemmilestonelog-local.toml`）に平文で書かれる。Webhook の URL を知っている人は誰でもそのチャンネルに投稿できる（`DiscordNotifier` はこの URL を秘密として扱い、ログやチャットに出さない）。シングルプレイではこの設定ファイルが全ワールドで共通（制約8・X1）なので、あるワールドのために設定した Webhook に別のワールドの記録も投稿される。Mod パックの製作者が config フォルダを含めてパックを書き出すと、URL も配られ、受け取った全員のシングルプレイの記録が製作者のチャンネルに投稿される
- `discordWebhookUrl` の値が `env:<変数名>`（例 `env:IML_DISCORD_WEBHOOK`）なら、その環境変数の値を URL として使う
  - `<変数名>` は `^[A-Za-z_][A-Za-z0-9_]*$`。接頭辞 `env:` は小文字のみ。前後の空白は従来通り無視する
  - 変数が無い・空・変数名が不正なら、URL が空のときと同じく何も送らない。warn ログを**値が変わるまで1回だけ**出す（変数名は出してよい。値は出さない）
  - 環境変数の値にも従来の検査（https であること）を行う。値はログにもチャットにも出さない（既存の方針）
  - 環境変数はプロセスの起動時に決まるので、値を変えたらサーバーの再起動が要る（README に書く）
  - 解決は「設定の値、環境変数を引く関数 → URL または無効の理由」の純粋関数にする。F35 の「URL が変わったら再開する」は、**解決した後の URL** で比べる
- 統合サーバー（シングルプレイ・LAN）でワールドを開いたとき（`ServerStartedEvent`、`!server.isDedicatedServer()`）、URL が解決できれば（`env:` を含む）、warn ログを1回出す: 「Discord の Webhook が設定されている。シングルプレイではこの設定は全ワールドで共通で、config フォルダを含めて Mod パックを書き出すと、受け取った人にも配られる」
- README: 上の2つの注意、`env:` の書き方、Mod パックを書き出す前に URL を消すこと
- 1.2.0 で足す: F46 の `discordWorldFirstWebhookUrl` での同じ `env:` 記法対応、F44 の設定画面に Webhook URL を出さない扱い（`FEATURE_SPEC_1.2.0.md` 参照）
- Config は追加しない（既存の設定の書き方を増やすだけで、空にすれば従来通り無効。制約8の例外）

### 実装メモ
- 既存の `discordWebhookUrl` には検証関数が無い（`define("discordWebhookUrl", "")`）ので、`env:` の値は NeoForge の検証で戻されない。検証関数を足す場合も `env:` を通すこと
- 単体テスト: `env:` の解決（変数あり・無し・空・不正な名前・`ENV:` の大文字）、https の検査が環境変数の値にも効くこと、warn ログに値が含まれないこと

---

## F40. 初めて参加したプレイヤーに、外せる目標を配る（`firstJoinPresets`）（候補 P34）

### 仕様
- 背景: `defaultTargets` は全員に固定で、個人では外せない（`remove` は個人ターゲットだけを外す。`not_target` の文面 "or it is a global default"）。サーバーが「最初の目標」を配りつつ、各自が要らないものを外せるようにする手段が無い
- Config `firstJoinPresets`（文字列リスト、既定 空 = 無効）。各要素はプリセット ID（`Presets.isValidId` の規則。形が不正な要素は Config の検証で弾く）
- 動作: ログインの時点（`Tracker.onPlayerLoggedIn`）で、そのプレイヤーの PlayerData が**無かった**とき（`MilestoneData.playerOrNull` が null）だけ、列挙したプリセットを順に、個人ターゲットとして適用する
  - 適用の処理は `preset <name>` と同じ関数を使う（存在しないアイテムの飛ばし方、上限 1024 件、F1 のベースライン、F8a の正規化も同じ）
  - PlayerData がある人（この機能を入れる前から参加している人、F22 の `importall` でデータが作られた人）には適用しない。それらの人に配るには F33 の `admin <player> preset <name>` を使う（README に書く）
  - F37 の `prunedata` や `admin <player> purge` で消されたプレイヤーが戻ってきたときは「初めて」とみなされ、もう一度配られる（`prunedata` で消されるのは目標が0件の人だけなので、困ることは少ない。README に書く）
  - 未知の ID（そのプリセットが無い）は飛ばし、warn ログを出す。サーバーの起動時（`ServerStartingEvent` の `Presets.reload` の後）に、未知の ID をまとめて1回 warn ログに出す。ログイン時は飛ばすだけ（ログを繰り返さない）
  - `presetsEnabled` が false なら何もしない
  - 管理者の操作ではないので F23 の取り消しの対象にしない（本人が要らないものは `remove` / F28 の `presetremove` で外す）
  - 1.2.0 で足す: F21 のデータパックプリセット ID の指定（`FEATURE_SPEC_1.2.0.md` 参照）
- 本人への通知: 1件以上追加したら、初回のヒント（`showFirstJoinHint`）より前に1行送る（`ServerLang.tr` で本人の言語）。0件なら送らない
  - `message.itemmilestonelog.first_join_presets`（%s = 追加した件数）: en "This server added %s starter targets to your list. Use /milestonelog remove to drop any you don't want." / ja 「このサーバーが用意した目標を %s 件、あなたの目標に追加しました。要らないものは /milestonelog remove で外せます。」
- Config の ON/OFF は `firstJoinPresets` が空かどうか（制約8の「設定値が空なら無効」）

### 実装メモ
- 「PlayerData があったか」は `data.player(uuid)` を呼ぶ**前に** `playerOrNull` で確かめる（`player` は `computeIfAbsent` で作ってしまうため）
- 単体テスト: PlayerData がある人に適用しないこと、未知の ID の飛ばし、`presetsEnabled` が false のとき、上限

---

## F45. Discord への投稿から自分を外す（`discordoptout`）（候補 P39）

### 仕様
- 背景: F14 は lookup などから記録を隠すが、Discord には従来通り投稿される（F14 の「隠していても変わらないもの」）。外部のサービスに自分の名前と記録が送られることを望まないプレイヤーが、それを止める手段が無い
- `/milestonelog discordoptout`（OP 不要。実行者はプレイヤーに限る）。実行するたびに切り替え、切り替え後の状態を返す（F14 の `privacy` と同じ形）
- PlayerData に `excludeFromDiscord`（boolean、§1.1）を足す
- 外している人の分は、Discord に送らない: 記録の投稿（世界初を含む）、F27 のまとめた投稿（その人の分だけ溜めない）、F19 の制覇の投稿
- 外していても変わらないもの（README に書く）: ゲーム内のブロードキャストと世界初のアナウンス、`firsts`、サーバーログ、書き出し、API のイベント（他の Mod や KubeJS のスクリプトが自分で Discord に送る場合は止まらない）
- Config `allowDiscordOptOut`（boolean、既定 true）。false なら、このコマンドは T の `feature_disabled` を返し、保存されている `excludeFromDiscord` は無視される（全員を投稿する方針のサーバー向け。値は消さないので、true に戻すと元の状態に戻る）
  - false のとき F29 の `help` に `discordoptout` を出さない
- Webhook が設定されていないサーバーでもコマンドは使える（状態を保存するだけ）
- 文面:
  - `message.itemmilestonelog.discordoptout_on`: en "Your milestones will no longer be posted to Discord." / ja 「あなたの記録を Discord に投稿しないようにしました。」
  - `message.itemmilestonelog.discordoptout_off`: en "Your milestones will be posted to Discord again." / ja 「あなたの記録を Discord に投稿するようにしました。」
- 1.2.0 で足す: F9 のダイジェストの行と F46 の世界初 Webhook からの除外、F17 のスコアは対象外（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 「送るかどうか」の判定（`allowDiscordOptOut` と `excludeFromDiscord`）は1つの関数にし、Discord に送るすべての箇所から呼ぶ
- 単体テスト: 判定、F27 の溜め方で外している人の分が除かれること

---

## F47. `firsts` に今の名前を出す（候補 P41）

### 仕様
- 背景: `firsts` は世界初の保持者の名前として、記録した時点の名前（`WorldFirstRecord.playerName`）を出している（`MilestoneCommands.firstsList`）。名前を変えたプレイヤーは古い名前で表示され、`lookup` や `admin`・F13 の名前（保存されている最後の名前 `lastKnownName`）と食い違う
- `firsts` の各行の名前:
  1. 保存データに保持者の UUID の PlayerData があり、`lastKnownName` が空でなければ、その名前
  2. 1 が記録時の名前（空でないもの）と違えば（大文字小文字を区別して比べる）、`message.itemmilestonelog.firsts_name_renamed`（%s = 今の名前、%s = 記録時の名前）: en "%s (was %s)" / ja 「%s（旧: %s）」
  3. PlayerData が無ければ（F37 や `purge` で消された）、従来通り記録時の名前、それも空なら UUID
  - 名前は `Component.literal` で埋め込む（制約12）
- 保存データ・書き出し（F31 の世界初を含む）・API の `WorldFirstRecord.playerName` は変えない（記録時の名前という意味を保つ）
- 1.2.0 で足す: F9 のダイジェスト・F51 の `info`・F52 のログイン時世界初サマリーでの同じ名前規則の適用（`FEATURE_SPEC_1.2.0.md` 参照）
- 名前の変更前後の対応が `firsts` で見えるようになるが、`admin players` と同じく保存されている最後の名前を出すだけで、Minecraft の名前は公開の情報なので、F14 の対象にはしない（README に書く）
- Config は追加しない（既存の表示の改善。§1.2 の例外として CHANGELOG に書く）

### 実装メモ
- 名前の選び方は純粋関数にして単体テストする（同じ名前、変わった名前、PlayerData なし、両方空）

---

## F49. Discord の送信テスト（`discordtest`）（候補 P44）

### 仕様
- 背景: Webhook の設定が正しいかを確かめるには、実際に何かを記録するしかない。1.1.0 では F35（404・401 で送信を止める）、F39（`env:`）、F4・F18（Embed・言語）と設定の組み合わせが増える。失敗はサーバーログの warn にしか出ず、管理者が気づきにくい
- コマンド追加:
  - `/milestonelog discordtest`（権限レベル2。F37 の `prunedata` と同じ理由でルート直下に置く。コンソール・RCON からも使える）
- 送信内容: 固定のテスト文面を F18 の `discordLanguage` で作り、F4 の Embed の設定に従って通常と同じ `DiscordNotifier` のキュー・送信経路で送る
  - プレーンテキスト: `discord.itemmilestonelog.test`（en "Test message from Item Milestone Log. Your Discord webhook is working." / ja 「Item Milestone Log からのテスト送信です。Discord Webhook は正常に動作しています。」）
  - Embed（F4 が有効なとき）: title = `discord.itemmilestonelog.test.title`（en "Webhook Test" / ja 「Webhook テスト」）、description = `discord.itemmilestonelog.test`、color = 0x3498DB（青系）、timestamp = 送信時刻
- F35 で送信を止めている URL にも送る（設定や Webhook を直した後の確認のため）。送信に成功したら、その URL に対する F35 の停止を解く
- 同時実行と結果の返却:
  - 同時に1件だけ実行できる（送信中は `discordtest_in_progress` を返す）
  - 送信は既存の別スレッドで行い、結果のメッセージは `server.execute(...)` でサーバースレッドに戻してから実行者に返す。実行者がプレイヤーで、結果が返る前にログアウトしていたら結果は捨てる（コンソール・RCON にはそのまま返す）
  - 事前チェック（送信前に同期で返す）: URL が未設定（空）、F39 の `env:<変数名>` で環境変数が無い・空・変数名が不正（**変数名だけ**を出し、値は出さない）、URL が `https://` で始まらないなど形式が不正
  - 送信後の結果: 成功（HTTP 状態コード、例 204 / 200）、HTTP 404 / 401（「Webhook が削除されたか URL が誤っている」）、HTTP 429、その他の HTTP エラー（状態コードのみ）、通信の失敗（例外の単純クラス名だけ）。**いずれの場合も Webhook URL は一切出さない**（既存の方針）
- 文面（en / ja）:
  - `message.itemmilestonelog.discordtest_sending`: en "Sending a test message to Discord..." / ja 「Discord へテスト送信しています…」
  - `message.itemmilestonelog.discordtest_in_progress`: en "A Discord test message is already being sent. Please wait." / ja 「Discord へのテスト送信がすでに実行中です。完了まで待ってください。」
  - `message.itemmilestonelog.discordtest_not_configured`: en "Discord webhook URL is not configured." / ja 「Discord の Webhook URL が設定されていません。」
  - `message.itemmilestonelog.discordtest_env_missing`（%s = 環境変数名）: en "Environment variable '%s' is not set or is empty." / ja 「環境変数「%s」が設定されていないか、空です。」
  - `message.itemmilestonelog.discordtest_invalid_url`: en "Discord webhook URL is invalid (it must start with https://)." / ja 「Discord の Webhook URL が不正です（https:// で始まる必要があります）。」
  - `message.itemmilestonelog.discordtest_success`（%s = HTTP 状態コード）: en "Discord test message sent (HTTP %s)." / ja 「Discord へテスト送信しました（HTTP %s）。」
  - `message.itemmilestonelog.discordtest_invalid_webhook`（%s = HTTP 状態コード）: en "Discord rejected the webhook (HTTP %s). The webhook may have been deleted or the URL is wrong." / ja 「Discord に拒否されました（HTTP %s）。Webhook が削除されたか、URL が誤っている可能性があります。」
  - `message.itemmilestonelog.discordtest_rate_limited`: en "Discord rate-limited the request (HTTP 429). Try again shortly." / ja 「Discord のレート制限に達しました（HTTP 429）。少し待ってから試してください。」
  - `message.itemmilestonelog.discordtest_http_error`（%s = HTTP 状態コード）: en "Discord returned HTTP %s." / ja 「Discord が HTTP %s を返しました。」
  - `message.itemmilestonelog.discordtest_network_error`（%s = 例外の単純クラス名）: en "Failed to reach Discord (%s)." / ja 「Discord への通信に失敗しました（%s）。」
- Config は追加しない（管理者のコマンド。制約8の例外）
- 1.2.0 で足す: `/milestonelog discordtest worldfirst`（F46）、権限ノード `itemmilestonelog.discordtest`（F43）、F53 の通知音なしフラグを付けない規則（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- `DiscordNotifier` に、送信結果のコールバックを受け取るテスト送信メソッドを追加し、既存の送信スレッドと HTTP クライアントを共用する。同時実行の制御は `AtomicBoolean` で行い、`finally` で必ず解除する（サーバー停止時のキャンセルでも残らないようにする）
- テスト結果の分類（HTTP 2xx / 401・404 / 429 / その他 / 例外クラス名）と、結果文字列に URL が含まれないことは純粋関数にして単体テストする

---

## F50. クリエイティブで記録が止まっていることを本人に伝える（候補 P45）

### 仕様
- 背景: クリエイティブ・スペクテイターから戻るとき、持っていたアイテムに「クリエイティブから持ち出した」印（`carriedFromCreative`）が付き（`Tracker.onGameModeChange`）、そのアイテムがインベントリから一度無くなるまで記録されない（スキャンは印のあるアイテムを数えず、印は持たなくなったときか、所持数 0 から拾ったときに外れる）。この規則は乱用を防ぐための 1.0.1 の決定だが、本人には何も知らされない。`targets` も印のあるアイテムの所持数を 0 と表示するため、持っているのに 0 と出て記録されない理由が分からず、不具合の報告につながりやすい
- 記録の規則自体は変えない。次の3か所で本人に理由を伝える:
  1. **ゲームモード切替時の通知**: `Tracker.onGameModeChange` でクリエイティブ・スペクテイターから離脱し `carriedFromCreative` に印が付いたとき（`ignoreCreativeAndSpectator` が true）、印が付いたアイテムのうち、本人の**未記録の実効ターゲット**（個人 + `defaultTargets`）に該当するものが1種類以上あれば、`notifyPlayer` が true のとき本人に1行だけチャット通知する（件数 = 該当するアイテム ID の種類数。同じアイテム ID に複数の数量ターゲットがあっても重複して数えない）
     - `message.itemmilestonelog.creative_carried_notice`（%s = 種類の数）: en "%s target item(s) you carried out of creative mode will not be recorded until they leave your inventory." / ja 「クリエイティブから持ち出した目標のアイテム（%s 種類）は、一度インベントリから無くなるまで記録されません。」
  2. **`targets` の行の末尾**: `/milestonelog targets`（および F30 の `targets pending`）で、未記録のターゲットのうち、そのアイテムが `carriedFromCreative` に含まれる行の末尾に補足文を付ける（所持数が 0 と出る理由や記録されない理由が分かるようにする）
     - `message.itemmilestonelog.target_creative_carried`: en " (carried from creative)" / ja 「（クリエイティブから持ち出し中）」
  3. **`/milestonelog`（概要）の1行**: `ignoreCreativeAndSpectator` が true で、実行者が現在クリエイティブまたはスペクテイターなら、概要の末尾に1行足す
     - `message.itemmilestonelog.summary_creative_paused`: en "Recording is paused while in Creative or Spectator mode." / ja 「クリエイティブ・スペクテイターの間は記録されません。」
- GUI は変えない（印は同期データに無く、制約1のデザイン凍結も維持する）
- Config は追加しない（既存の挙動の説明を出すだけ。制約8の例外。既存の表示が増えるため §1.2 の例外として CHANGELOG に書く）
- 1.2.0 で足す: F8b の未記録タグターゲットの該当判定・`targets` の補足・単体テスト（`FEATURE_SPEC_1.2.0.md` 参照）

### 実装メモ
- 「印の付いたアイテム集合、実効ターゲット、記録済みキー → 該当する未記録ターゲットのアイテム種類数」は純粋関数にして単体テストする（複数数量キーの重複排除、記録済みの除外）

---

## 3. テスト・検証（全機能共通）

- **〔P31〕1.1.0 の実装の最初の PR で、issue #44 の4点のテストを入れる**（既存のコードは変えず、テストだけを足す）: (1) `SyncMilestonesPayload` / `MilestoneActionPayload` の encode / decode（文字列の上限と 4096 件の上限を含む）、(2) `ModNetworking` のトークンバケット（補充の端数、ログアウト時・放置時の削除、片付けの間隔）と `MilestoneService`・`Tracker` の中心の処理、(3) `gui.itemmilestonelog.*` のキーが en_us.json にあることの静的な検査、(4) 26.x の `MilestoneDataTest` に、1.21.1 にある壊れた要素（ticks の欠け・負の値）の場合を戻す
  - 理由: 1.1.0 は通信（制約10）、権限（G1）、記録の経路（F1・F5・F20・F27・F32・F36）に手を入れる。変更の前にテストが無いと、既存の挙動を壊したことに気づけない
- 追加・変更した純粋ロジック（パース、検証、置換、整形、しきい値判定）には単体テストを追加する
- 〔X18〕次の純粋関数は必ず単体テストする（1.1.0 分）:
  - G1 の権限判定（専用/統合 × ホスト/ゲスト × 権限レベル × プレイヤー以外のソース）、G2 の表示名の引き継ぎ
  - F1 のしきい値判定（ベースライン、複数超え、最小個数）
  - F5 の制覇判定（判定対象キーが空、存在しないアイテム、個数の不一致、今記録したキーを含まないプリセット）
  - F4 の Markdown エスケープと切り詰め
  - §1.1 の新フィールドの往復（1.21.1 NBT と 26.x Codec）と、26.x で 1.21.1 形式の新フィールドを読めること
  - F11 のタイムゾーンの解決（空、IANA の ID、オフセット）と、GUI 用の版が従来の結果のままであること
  - F13 の順位付け（同順位、隠しているプレイヤーの除外と閲覧者本人の扱い、epochMillis が 0 の記録の除外）
  - F16 の集計（F5 と同じ R(S)、50 行の上限）
  - F18 の書式関数（`%s`、位置指定、`%%`）と、en_us の出力が 1.0.1 の文面と一致すること
  - F22 の全員分の読み込み（`exportall` の出力の読み戻し、UUID の無い要素のスキップ、1人分の形式の拒否）
  - F23 の取り消し（再記録との競合、`completedPresets` と `progressNotified` の戻し方、10 分の期限）
  - F26 の CSV（クォート、数式の対策、BOM、CRLF）
  - F27 の経路ごとのまとめ方（しきい値 3、最初の5件と「ほか N 件」、Discord の 20 行と文字数の上限）
  - F29 の表と登録済みのサブコマンド・lang キーの一致
  - F30 のページの切り出し、全ページ数、クリックで実行するコマンド文字列
  - F31 の世界初のマージ（早い方の規則、不明な epochMillis、壊れた要素）
  - F32 の戻り値の各条件、スレッドの検査、入れ子の深さの上限
  - F35 の `Retry-After` の解釈
  - F36 の早い終了と `carriedFromCreative` の印の扱い
  - F37 の整理の対象の判定
  - F38 の集計（隠しているプレイヤーの除外、同順位）
  - F39 の `env:` の解決（変数あり・無し・空・不正な名前）と、ログに値が出ないこと
  - F40 の適用の条件（PlayerData の有無、未知の ID、`presetsEnabled`）
  - F45 の送るかどうかの判定と、F27 の溜め方からの除外
  - F47 の名前の選び方
  - F49 のテスト結果の分類と、結果文字列に URL が含まれないこと
  - F50 のクリエイティブ持ち出し該当アイテム種類数の算出（複数数量キーの重複排除、記録済み除外）
- lang キーの追加は ServerLangTest / LangFilesTest が通ること（en / ja のキー集合とプレースホルダの一致。F34 の後は、lang フォルダのファイルと `BUNDLED_LANGUAGES` の一致も）
- CI の既存ボットテスト（記録・コマンド・移行）が全バージョンで通ること
- ボットテストに加えることが望ましいケース（1.1.0 分）: F13 の `top` / `recent` の出力、F22 の `exportall` → `importall` の往復（F31 の世界初を含む）、F27（`scanIntervalTicks` を大きくしたうえで、`/give` で3種類以上のターゲットを次のスキャンまでに渡し、まとめた1通がボットに届くこと）、F28 の `removeall` → `undo`、F30 のページ指定、F33 の `grant`
- 〔X18〕F29 の `suggest_command` と F30 の `run_command` のクリックは、mineflayer では押せないので、バニラの本物のクライアントで手動で確かめる（26.x では admin 系の `[次へ]` に確認画面が出ることも）
- 〔X2〕手動確認: 1.1.0 のサーバーに 1.0.1 の Mod 入りクライアント、1.0.1 のサーバーに 1.1.0 の Mod 入りクライアントで参加でき、GUI が開けること（§3.1 の CI が入った後は、参加と切断の有無は CI が確かめるので、手動では GUI の見た目だけを確かめる）
- バージョン表記を 1.1.0 に上げる（gradle.properties 等、1.0.1 へ上げたときと同じ箇所）
- 1.2.0 分のテスト要件は `docs/FEATURE_SPEC_1.2.0.md` の §3 を参照

### 3.1 版の違うクライアントとサーバーの参加を CI で確かめる（候補 P43）

- **1.1.0 の実装の前に入れる**（§2.2 の 1.1.0 の 1）
- 背景: 上の 〔X2〕 の確認（1.0.1 の Mod 入りクライアント ↔ 1.1.0 のサーバー、とその逆）は手動になっている。通信の互換（制約10）は、壊すと参加の時点で切断される影響の大きい部分なのに、自動の検査が無い。CI の mineflayer はバニラのプロトコルしか話さないので、Mod のチャンネルの交渉（NeoForge の `NetworkComponentNegotiator`）は確かめられない
- ジョブ: 仮想ディスプレイ（Xvfb）の上で本物のクライアント（ModDevGradle の `createClientLaunchScript` で作るスクリプト）を `--quickPlayMultiplayer localhost:<port>` 付きで起動し、別の版の Mod を入れた専用サーバー（既存の server-test の `.github/scripts/server-lib.sh`）に参加させる
  - 組み合わせ: (a) 開発中の版のクライアント → 1.0.1 のサーバー、(b) 1.0.1 のクライアント → 開発中の版のサーバー、(c) 同じ版どうし（対照）
  - まず 1.21.1 と 26.1.2 で入れる。クラウドでの起動は 1.21.1 で実績がある（2026-11-22、Mesa の llvmpipe で描画できた）。26.1.2 のクライアントは OpenGL を使う（同梱のライブラリが lwjgl-opengl）ので、同じく動く見込み。26.2 / 26.3 は起動を確かめてから足す
  - 1.0.1 の jar: タグとリリースがまだ無いので、1.0.1 のコミット（12763d2）を同じワークフローでビルドする（CI の jar はビット単位で再現する）。1.1.0 のリリース後は、そのリリースの jar を使う
  - 準備: `run/options.txt` を先に書く（GUI を開くキーの割り当て、`pauseOnLostFocus:false`、`onboardAccessibility:false`）。参加した後、xdotool で GUI を開くキーを押す
  - 判定: 両方のログに切断（NeoForge の交渉の失敗、`Disconnected`）が無いこと。加えて、開発中の版の側に、受け取りを示す debug ログを足し（クライアント: 同期を受け取った。サーバー: `RequestSyncPayload` を受け取った）、テストではそのロガーを debug にして行があることを確かめる。1.0.1 の側にはログを足せないので、開発中の版の側だけで判定する
    - (a): クライアントのログに「同期を受け取った」（1.0.1 のサーバーが応答した）
    - (b): サーバーのログに「`RequestSyncPayload` を受け取った」（1.0.1 のクライアントがチャンネルを持ち、要求を送れた）
  - 1.2.0 で足す: 1.1.0 との組み合わせ（両方向）および F25 の追加ペイロードの確認（`FEATURE_SPEC_1.2.0.md` 参照）
  - 時間: クライアントの起動に数分かかる。既存の server-test と並列のジョブにし、NeoForge のサーバーのインストールのキャッシュを共用する
- このジョブが入った後は、上の 〔X2〕 の手動確認は GUI の見た目の確認だけにする

### 3.2 4バージョン間のコード共有と同一ファイル検査（候補 P50）

- **1.1.0 の実装の前に入れる前提作業**（§2.2 の 1.1.0 の 1）
- 採否の方針: 提案時の (a)(b)(c) のうち **(a) と (c) を採用し、(b)（26.x のサーバー側の同一ファイルを `common26/java` に移して共有する案）は不採用とする**（将来の 26.x の API 変更で分け直すリスクを避けるため）
- **(a) 同一ファイルの CI 検査**:
  - 現時点でバイト単位で一致しているファイル群（26.1.2 / 26.2 / 26.3 間で一致する 22 ファイル、および全 4 バージョン間で一致する 8 ファイル: `api` の 5 ファイル、`DiscordNotifier`、`TargetEntry`、`ActionRowLayout`）が、変更後も一致したままであることを検査するスクリプトを追加し、CI のジョブで毎回実行する
  - 意図的にバージョン間で差異を設ける必要が出たファイルは、理由のコメントを添えて検査対象の一覧から外す
  - 既存の4バージョンのディレクトリ構成は変えず、反映漏れだけを自動で防ぐ
- **(c) 純粋ロジックの `common/java` 共有**:
  - `common/src/main/java`（および共通の単体テスト用 `common/src/test/java`）を設け、4バージョンすべての `build.gradle` の `sourceSets` に加える（既存の `common/resources` と同じ仕組み）
  - Minecraft / NeoForge の型に依存しない純粋なロジック（本書で新設する整形、Markdown / HTML / CSV のエスケープと組み立て、順位付け・統計、しきい値判定、`Retry-After` の解釈、`env:` の解決、テスト結果の分類、多言語プリセット名の解決など）は `common/src/main/java` に置き、単体テストも `common/src/test/java` で共有する
  - 既存の 4 バージョン配下のコードは無理に移さなくてよい（新規コードと、変更のついでに切り出す純粋ロジックから適用する）
  - `common/src/main/java` と `common/src/test/java` は 1.21.1（**Java 21**）と 26.x（**Java 25**）の両方からコンパイルされるため、Java 21 でコンパイルできる文法・標準ライブラリのみを使う

### 3.3 GameTest によるサーバー側処理の自動検証（候補 P51）

- **1.1.0 の前半に入れるテスト基盤**（§2.2 の 1.1.0 の 3、記録の経路の実装と合わせて導入する）
- 目的: 記録の経路（拾得・スキャン・クラフトの次 tick、F27 の tick 末のまとめ、F5 の制覇、F23 の取り消し、F33 の付与、F50 のクリエイティブ持ち出し、G1 の権限判定など）は純粋関数の単体テストだけでは経路全体を検証できず、CI の mineflayer ボットテストはバニラプロトコル越しで遅く不安定な面がある。GameTest により、本物のテストサーバーと `GameTestHelper.makeMockServerPlayerInLevel()` のモック `ServerPlayer` を使って高速・決定的に検証する
- 構成:
  - 各バージョンのテスト用ソースセットに GameTest を配置し、CI で ModDevGradle の `gameTestServer` 実行構成を使って回す（導入時に 4 バージョンすべてで起動と終了コードを確認する）
  - 1.21.1（`@GameTestHolder` / `RegisterGameTestsEvent`）と 26.x（`RegisterGameTestsEvent.registerTest` による `GameTestInstance` 登録）で登録 API が異なるため、登録のラッパーだけをバージョンごとに書き、テスト本体の共通ロジックは可能な範囲で共有する（§3.2 (c) の考え方）
- 検証対象（優先）: 拾得 → 記録、インベントリスキャン → 記録、クリエイティブ持ち出しの印と F50 の通知、F27 の同時記録のまとめ、F5 のプリセット制覇、F23 の `undo`、F33 の `grant`、G1 の権限判定
- 導入時に確認する技術事項: モック `ServerPlayer` で `PlayerTickEvent` が発火するか、`connection.hasChannel` が使えるか（使えなければネットワーク同期経路は GameTest の対象外とし §3.1 と単体テストに任せる）、ログインイベントは手動発火が必要か

## 4. ドキュメントとリリース 〔X17〕

1.1.0 のリリースで次を行う（1.2.0 分は `docs/FEATURE_SPEC_1.2.0.md` の §4 を参照）。各版の文書には、その版に入る機能だけを書く。

- `CHANGELOG.md` に `## 1.1.0` の節を追加する。`check-release.sh` が `mod_version` と同じ節を要求するので、**mod_version を上げるコミットと同時に**入れる（無いと CI の release-check が失敗する）。§1.1 のダウングレードの注意、既定で挙動が変わるもの（F27 の通知のまとめ）、既定で有効な表示の変更（F1・F47 の `firsts` の名前・F50 のクリエイティブ持ち出し通知）を必ず書く
- `README.md` / `CURSEFORGE.md`: 新しいコマンド・設定（§1.2 のすべての設定を README の Configuration の一覧に足す）と、次の注意:
  - F3 / F26 の出力場所、G2 の上書き時の挙動、F11 のタイムゾーンが GUI に効かないこと、F14 で隠れるもの・隠れないもの、F18 でアイテム名が英語のままになる場合、F20 / F32 の API の例と `getCause()`、F22 で UUID が変わる場合と 1.0.1 のファイルでは世界初が戻らないこと（F31）、F23 の期限と一括削除も取り消せること、F27 のまとめ方、F30 の `[次へ]` で 26.x の admin 系に確認画面が出ること、F33 の `grant` が「推定」マークとは別物であること、F34 の翻訳の追加の手順、F35 の `?thread_id=`（実機で確かめてから）、F37 で消えるもの・消えないもの、F39 の `env:` の書き方とシングルプレイ・Mod パックの書き出しの注意、F40 が初めて参加する人だけに配ること（既にいる人には F33 の `admin <player> preset`）、F45 で止まるもの・止まらないもの、F47 で `firsts` に今の名前が出ること、F49 の `discordtest` の使い方、F50 のクリエイティブ持ち出し時の表示
- `docs/updates.json`: 1.1.0 の項目と promos は **CurseForge / Modrinth で公開するときのコミットで**更新する。更新チェッカーは main の raw ファイルを読むため、実装の PR で先に更新すると、未公開の版の更新通知がプレイヤーに出る
- `GEMINI.md`: 「現在の開発バージョンは 1.0.1（未リリース）。バージョン番号は上げない」の記述を 1.1.0 の開発状態に合わせ、`docs/` の一覧に本書（および `FEATURE_SPEC_1.2.0.md`）を加える
- `docs/VERSION_NOTES.md`: §1.1 の新フィールドを「保存データの構造」に 1.1.0 の分だけ追記する

### 4.1 CurseForge / Modrinth への公開を CI から行う（候補 P53）

- **1.1.0 の公開までに導入する**
- 目的: 1回のリリースで 4 バージョン × 2 サイト = 8 個の jar を手作業でアップロードする負担と、Minecraft バージョンの付け間違いを防ぐ
- ワークフロー: **手動起動（`workflow_dispatch`）専用**の GitHub Actions ワークフローを追加する（公開は取り消しにくい操作のため、タグ push 等での自動起動はしない）。入力としてリリースタグ（例 `v1.1.0`）を指定する
- 処理:
  - 指定タグの GitHub Release に添付されている 4 バージョン分の jar（既存の `release` ジョブが生成・添付したもの）を取得し、CurseForge と Modrinth の API へアップロードする
  - jar のファイル名に含まれる Minecraft バージョンから、各サイトの対応ゲームバージョンおよびローダー（NeoForge）を決定する
  - 変更内容（changelog 本文）は `CHANGELOG.md` の該当バージョンの節を抽出して渡す
- 認証とセキュリティ:
  - API トークンはオーナーがリポジトリの GitHub Actions **secrets** に登録して渡す（Modrinth は個人アクセストークン、CurseForge は API トークン）。トークンをログに出さない
  - 第三者の GitHub Action を使う場合は、タグではなく**コミットのフル SHA で固定**する（トークンを渡すため）
- `docs/updates.json` の更新は従来通り、両サイトでの公開を確認してから手動コミットで行う
- 各サイトの API の細部（CurseForge のゲームバージョン ID の取得方法など）はワークフロー実装時に確認する

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
| X10 | F4 の各項目の固定、Markdown エスケープ、長さの上限、既定値の判断事項（その後オーナーの判断で false に決定。付録B） | 初版は「description または fields」で出力が一意に決まらない。`_Steve_` のような名前の `_` などが Markdown として解釈される（現行のプレーンテキストでも同じ）。Discord の上限を超えると投稿が拒否される |
| X11 | F5 の判定を「今記録したキーを含むプリセット」「判定対象キー」で定義、本人への通知を多くても1通に、表示名の出所 | 初版のままだと、導入後の最初の記録で導入前に条件を満たしていたプリセットの「制覇」が一斉に出る。Mod が入っていないプリセット（`atm_star`）は永遠に制覇できない／判定対象が空だと0件で制覇になる。`notifyPlayer` と全体アナウンスが両方 true だと本人に2通届く。組み込みの表示名（lang の値）は「プリセット: …」の形 |
| X12 | F6 の出力形式・権限・補完 | 既存の `list` に世界初の印は無く（`MilestoneCommands.list`）、「list と同じ形式」と「世界初の印」が両立しない |
| X13 | F7 の実行タイミング・出力抑止・名前の検証 | オフラインモードではプレイヤー名が Mojang の規則で検証されず、記号を含み得るため、初版のセキュリティ上の前提が成り立たない。記録処理の途中で任意のコマンドを実行すると、そのコマンドがデータを変更したときの影響が読めない。`give` などは OP のチャットにフィードバックを出す |
| X14 | F8 を F8a / F8b に分割、パース規則・正規化箇所・検証時期・専用コマンド・クリエイティブ除外・即時判定・表示名・API への影響 | `tryParseEncoded` は最初の `#` で分割する。`add` は `ItemArgument`、`remove` / `reset` は `IdentifierArgument` で `#` を受け付けず、既存コマンドではタグを扱えない（制約2で引数も変えられない）。正規化の各箇所が `Identifier.tryParse` でタグを落とす。Config の検証時点ではタグが未読込。プリセットの「存在しない ID を落とさない」方針と初版の「存在しないタグは弾く」が矛盾。`tryRecordForItem` はアイテム ID の完全一致だけを見る。`Tags.getTagTranslationKey` が NeoForge にある |
| X15 | F9 の初回・期間・範囲・長さの上限・既知の制限 | `lastDigestEpochMillis` の欠落時 0 のままだと、有効化した直後に全履歴を送る。`discordNotifyScope` との関係が未定義。プレーンテキストは 2000 文字が上限 |
| X16 | F10 の保存先をワールドフォルダに、メモリから書き出す方式に、復元手順 | config フォルダは全ワールド共通で、世代の削除が他のワールドのバックアップを消す。実ファイルのコピーは保存の書き込み方式・時期に依存する |
| X17 | ドキュメント・リリース手順の節を追加 | `check-release.sh` が CHANGELOG の節を要求する。`updates.json` は main の raw URL から読まれる。`GEMINI.md` は「1.0.1 未リリース・版を上げない」のままで本仕様と矛盾する |
| X18 | テスト項目の追加 | CI の専用サーバーでは LAN を再現できない。F2 は初めてのレジストリ登録で、バニラ参加互換の確認が要る |
| X19 | 実施順序を変更（G 先頭、F8a を前へ） | F8 が TargetKey の意味を変え、F1〜F9 がその上に乗るため |
| X20 | 日本語の文面の「ターゲット」を「目標」に揃える（F5・F16・F18・F22・F24 の ja、制約5） | 既存の ja_jp.json は target を 28 か所で「目標」と訳し、「ターゲット」は `preset_applied_limit` の 1 か所だけ。default target は GUI で「デフォルト目標」。同じ画面・チャットに2つの訳語が混ざらないようにする |

---

## 付録B. 追加機能の採用の記録（2026-11-23）

`docs/FEATURE_PROPOSALS_1.1.0.md` の候補 P1〜P18 について、オーナーが次のとおり決めた。

| 候補 | 本書の番号 | 結果 |
|---|---|---|
| P1 表示に使うタイムゾーン | F11 | 採用 |
| P2 チャットのアイテム名のホバー | F12 | 採用 |
| P3 `top` / `recent` | F13 | 採用 |
| P4 `privacy` | F14 | 採用 |
| P5 `muteprogress` | F15 | 採用 |
| P6 `presetprogress` | F16 | 採用 |
| P7 スコアボード連携 | F17 | 採用 |
| P8 Discord の言語 | F18 | 採用 |
| P9 Discord へのプリセット制覇通知 | F19 | 採用 |
| P10 API の拡張 | F20 | 採用 |
| P11 データパックのプリセット | F21 | 採用 |
| P12 `importall` | F22 | 採用 |
| P13 `undo` | F23 | 採用 |
| P14 アイテム ID の付け替え | F24 | 採用（コマンドは `admin` の下ではなくルート直下の `renameitem` にした。理由は F24） |
| P15 収納の中身も数える | — | **不採用**（1.0.1 の「収納の中身は数えない」の決定を維持する） |
| P16 タイトル演出 | — | **不採用** |
| P17 GUI の世界初の印 | F25 | 採用（追加ペイロードは、以後のデータの追加にも使える NBT の compound にした） |
| P18 CSV 出力 | F26 | 採用 |

同時に決めたこと: F4 の `discordUseEmbeds` の既定値は false。

移す際に、候補の段階では決めていなかった点（同順位の扱い、スコアを書き込むタイミング、データパックのプリセットの読み取り専用の扱い、取り消しと再記録の競合、付け替え先との衝突など）を各節で決めた。F1 の `progressAlertMinCount`、F2 の既存の記録への遡及、F8b のタグの表示名は、付録A の修正のとおりとする（オーナーから変更の指示が無いため）。

### 第2回の候補（P19〜P32）の採用（2026-11-23）

オーナーが、第2回の候補をすべて採用した（見送りを勧めた R7〜R11 は見送りのまま）。

| 候補 | 本書の番号・箇所 | 版 |
|---|---|---|
| P19 同時に起きた記録の通知をまとめる | F27 | 1.1.0 |
| P20 目標を一括で外す | F28 | 1.1.0 |
| P21 `help` | F29 | 1.1.0（1.2.0 のコマンドはそのときに足す） |
| P22 ページ分けと絞り込み | F30 | 1.1.0 |
| P23 世界初の書き出しと読み込み | F31 | 1.1.0 |
| P24 書き込み用の API | F32 | 1.1.0 |
| P25 管理者の目標の操作と記録の付与 | F33 | 1.1.0 |
| P26 言語を追加しやすくする | F34 | 1.1.0 |
| P27 Discord の送信の改善 | F35 | 1.1.0 |
| P28 拾得のたびの処理を減らす | F36 | 1.1.0 |
| P29 古いプレイヤーデータの整理 | F37 | 1.1.0 |
| P30 サーバー全体の統計 | F38 | 1.1.0 |
| P31 issue #44 のテストを先に | §3 の先頭、§2.2 の 1.1.0 の 1 | 1.1.0 |
| P32 2回のリリースに分ける | §2.1 | — |

移す際に、候補の段階から変えた点・新しく決めた点（オーナーの判断で戻せる）:
- P19 の既定値: 候補では「要判断（true を推奨）」としていた。推奨どおり **true** にした（既定のままで Discord に届かない状態を直すため）。1.0.1 からの挙動の変更になるので、§1.2 の例外として CHANGELOG に書く。false を望む場合は §1.2 と F27 の既定値を変えるだけでよい
- P19 のまとめ方: しきい値の 3 件を経路ごとに数えることにした（記録が3件で世界初が1件なら、世界初は従来の文面で送る）。Discord の Embed は、候補の「1通に最大 10 件の Embed」をやめ、Embed 1件に行を並べる形にした（Discord の 10 件・合計 6000 文字の制限を考えずに済み、F9 のダイジェストと同じ形になる）
- P20: 何も外さなかった操作は F23 の取り消しの対象を上書きしないことにした（F23 のリセットにも同じ規則を足した）
- P22: `[前へ]` と `admin players` のページ分け、範囲外のページの文面を足した。26.x のクライアントが admin 系のクリックに確認画面を出すことを、26.1.2 のクライアントで確かめて書いた
- P24: 目標の追加・削除は既存の API に揃えて `(MinecraftServer, UUID, TargetKey)` にし、`recordNow` だけ `ServerPlayer` にした。記録の原因 `getCause()`（`RecordCause`）と、入れ子の深さの上限（16）を足した
- P25: **`grant` はオンラインの対象だけにした**。候補では、オフラインなら「最後の記録の最大のプレイ時間」を使うとしていたが、`MilestoneRecordedEvent` は `ServerPlayer` を必要とするのでオフラインでは起こせず、プレイ時間も実際より早く見えて F13 の早い順の順位を歪めるため。実効ターゲットに無いキーは先に個人ターゲットに足すこと、クリエイティブ中でも付与できること、F5 の判定を本人への通知だけで行うことを決めた
- P26: 同梱の言語の一覧をビルド時に生成する案をやめ、一覧はコードの1か所のままにして、ファイルとの食い違いをテストで止める形にした（4バージョンの Gradle に生成の処理を足すより単純で確実なため）
- P27: Discord の文書を確かめた（`Retry-After` と `retry_after`、無効なリクエストの上限、フォーラムでの `thread_id` の必須）。あわせて、404・401 の Webhook への送信を止める改善を足した（Discord の文書が繰り返しの使用を禁じているため）
- P28: 索引は作らないことにした（作り直しの漏れの危険が利益に見合わない）。`carriedFromCreative` の印を外す処理を保つため、早い終了の条件に「その印が無いこと」を入れた
- P29: 対象から、`carriedFromCreative` が空でないもの・個人の設定があるもの・オンラインのものを除いた。`lastSeen` が無い 1.1.0 より前のデータには、バニラのプレイヤーデータのファイルの更新時刻を使う（フォルダの場所はバージョンで違うので `LevelResource.PLAYER_DATA_DIR` を使う）。消すのは空のデータだけなので、F10 のバックアップは書かない
- P32: 候補の分け方の例に、第2回の候補の版を足した。加えて、F8a の「正規化を1か所にまとめる」だけを 1.1.0 に入れることにした（1.2.0 でタグを受け付けるときの変更を小さくするため）。F13 の `toptag` と F23 の `resettag` の取り消しは、タグとともに 1.2.0 に入る

### 第3回の候補（P33〜P43）の採用（2026-11-23）

オーナーが、第3回の候補をすべて採用した（見送りを勧めた R12〜R14 は見送りのまま）。P35・P36・P38 の採用は制約1の例外の、P37 の採用は制約2・11の例外の承認を含む（各制約に明記した）。

| 候補 | 本書の番号・箇所 | 版 |
|---|---|---|
| P33 Webhook の URL を設定ファイルに書かずに済むようにする | F39 | 1.1.0 |
| P34 初めて参加したプレイヤーに目標を配る | F40 | 1.1.0 |
| P35 カーソルのアイテムをキー1つで目標に追加 | F41 | 1.2.0 |
| P36 ツールチップに記録の状態 | F42 | 1.2.0 |
| P37 権限 Mod で管理者のコマンドを許可 | F43 | 1.2.0 |
| P38 NeoForge の設定画面 | F44 | 1.2.0 |
| P39 Discord への投稿から自分を外す | F45 | 1.1.0（F9 の分は 1.2.0） |
| P40 世界初だけを別の Webhook に | F46 | 1.2.0 |
| P41 `firsts` に今の名前 | F47 | 1.1.0 |
| P42 プレイ時間と Day の表記を言語に合わせる | F48 | 1.2.0 |
| P43 版の違う組み合わせの参加を CI で確かめる | §3.1 | 1.1.0 の実装の前 |

移す際に、候補の段階から変えた点・新しく決めた点（オーナーの判断で戻せる）:
- P35: **候補の「26.1.2 はアクセストランスフォーマーが要る見込み」は誤りだった**。NeoForge が `AbstractContainerScreen` に公開メソッドを足している（1.21.1 / 26.1.2 / 26.2 は `getSlotUnderMouse()`、26.3 は `getHoveredSlot()`。各バージョンの NeoForge の下限 21.1.1 / 26.1.2.71 / 26.2.0.57 / 26.3.0.76 の userdev のパッチで確かめた）。候補は Minecraft 本体の jar だけを見ていた。あわせて、キーは `ScreenEvent.KeyPressed.Post` で受け取ることにした（Pre だと、クリエイティブの検索欄に打った文字でも目標が足される）。アクションバーに出すのは、このキーで送った操作の返事だけにした
- P36: ログイン時の同期は `PlayerLoggedInEvent` で送ることにした（`OnDatapackSyncEvent` はログインの途中で起こるため）。ツールチップの処理は表を引くだけにし、表は同期を受け取ったときに作り直す（JEI / EMI が多数のツールチップを作るため）。切断時に表を捨てる
- P37: ノードの一覧を決めた（`import` で他人のファイルを読む判定、G1 のプリセットの管理、`allowLookup` と F14 を越えて見る `viewhidden` を足した）。プレイヤー以外のソースでは権限 API を呼ばないことにした（API が `ServerPlayer` を必要とし、初期化がサーバーの起動時のため）。「または」の判定なので、ノードで OP から権限を取り上げられないことを明記した。権限 Mod を使うには NeoForge の `permissionHandler` の設定が要ることを書いた（21.1.1 / 26.1.2.71 / 26.2.0.57 のソースではワールドごとの SERVER 型の設定、26.3.0.76 では SYNCED 型の設定にある）
- P38: **Webhook の URL は設定画面に出さない**ことにした（画面に値がそのまま出るため。NeoForge の `ConfigurationScreen` のフィルターで除ける）。26.3 の LOCAL 型の設定が画面に出ることを 26.3.0.76 のソースで確かめた。説明の lang キーが無いと英語のコメントが出ること（NeoForge の `translatableWithFallback`）を確かめたうえで、ja のために en / ja の両方に足すことにした
- P39: コマンド名を `discordoptout` にした。`prunedata`（F37）の対象から、この設定がある人を除いた（ほかの個人の設定と同じ）
- P40: F27 のまとめ方を送り先ごとに分けること、`discordNotifyScope` が `world_first` だと `discordWebhookUrl` には記録が送られなくなること、F19 と F9 は `discordWebhookUrl` に送ることを決めた
- P41: PlayerData が無い（消された）保持者は記録時の名前のままにした。F9 のダイジェストにも同じ規則を使う（「旧:」は付けない）
- P42: GUI も対象にした（候補の「チャットと GUI」のとおり）。en_us の結果を従来と一字一句同じにし、テストで止めることにした
- P43: 1.0.1 の jar は、タグとリリースがまだ無いので、1.0.1 のコミットを CI でビルドして使う。判定は、開発中の版の側に足す debug ログで行う（1.0.1 の側にはログを足せないため）。1.2.0 の F25 の追加ペイロードの確認もこのジョブで行う

### 第4回の候補（P44〜P53）の採用（2026-11-23）

オーナーが、第4回の候補をすべて採用した（P50 は (a) と (c) を採用し (b) は不採用。見送りを勧めた R15〜R19 は見送りのまま）。

| 候補 | 本書の番号・箇所 | 版 |
|---|---|---|
| P44 Discord の送信テスト（`discordtest`） | F49 | 1.1.0（`worldfirst` と権限ノードは 1.2.0） |
| P45 クリエイティブで記録が止まっていることを本人に伝える | F50 | 1.1.0（タグ対象は 1.2.0） |
| P46 アイテム1つの状態を見る（`info <item>`） | F51 | 1.2.0 |
| P47 留守の間の世界初をログイン時に知らせる | F52 | 1.2.0 |
| P48 通常の記録を Discord の通知音なしで投稿する | F53 | 1.2.0 |
| P49 プリセットの表示名を言語ごとに書けるようにする | F54 | 1.2.0 |
| P50 4バージョンで同じコードを共有のソースにまとめる | §3.2 | 1.1.0 の実装の前（(a)+(c) を採用、(b) は不採用） |
| P51 GameTest でサーバー側の処理を確かめる | §3.3 | 1.1.0 の前半 |
| P52 実装を始める前に仕様書を版ごとに分ける | §2.1（次の工程で実施） | 候補追加の締め後 |
| P53 CurseForge / Modrinth への公開を CI から行う | §4.1 | 1.1.0 の公開まで |

移す際に、候補の段階から具体化した点・新しく決めた点（オーナーの判断で戻せる）:
- P44（F49）: 送信中・事前チェック・HTTP 状態コード別の lang キーと、`discordtest` では F53 の `discordSilentRecords` を付けないことを決めた。F29 の `help` の表と F43 の権限ノード表に反映した
- P45（F50）: ゲームモード切替時の通知は `notifyPlayer` に従い、同じアイテム ID に複数の数量ターゲットがあっても種類数として重複なく数えることにした。タグターゲットの `targets` での補足条件（タグ内の1アイテム以上が持ち出し中）を決めた
- P46（F51）: 各行の lang キー・補完の対象・関連タグ（最大 10 件）の仕様を決めた。`allowLookup` = false の非特権者には人数と順位だけを出さず、自分の状態と世界初は見られる形にした。F29 の `help` の表と F14・F43 に反映した
- P47（F52）: 同時刻の並び順（TargetKey 順）と lang キーを決め、§1.1 の `lastSeen` の用途に F52 を追記した
- P48（F53）: `discordSilentRecords` が false のときは `flags` フィールド自体を出さないこと、F49 の `discordtest` には付けないことを決めた
- P49（F54）: 言語コードキーの小文字正規化、無効値（空・非文字列）の無視、挿入順の保持（`LinkedHashMap`）、コンソール・RCON での解決（`en_us` 優先）を決めた
- P50（§3.2）: **(a) 同一ファイルの CI 検査と (c) 純粋ロジックの `common/java` 共有を採用し、(b) `common26/java` は不採用**とした。`common/src/main/java` は 1.21.1 と 26.x の両方からコンパイルされるため Java 21 互換に限ることを明記した
- P51（§3.3）: 1.1.0 の前半（§2.2 の段落 3、記録の経路の実装の直後）に導入する位置づけとした
- P52: 本書の §2.1 に F49〜F54・§3.2・§3.3・§4.1 の振り分けまでを反映し、版別へのファイル分割は次の工程で実施する
- P53（§4.1）: 手動起動（`workflow_dispatch`）専用・API トークンは secrets・第三者 Action のフル SHA 固定・`docs/updates.json` の手動更新維持を §4.1 に反映した
