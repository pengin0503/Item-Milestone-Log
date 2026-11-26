# Item Milestone Log — 追加機能 実装仕様書（v1.2.0）

この文書は、AI（または開発者）が v1.2.0 の実装を完遂できるように書かれた仕様書である。対象リポジトリ: pengin0503/Item-Milestone-Log

> **文書の構成（候補 P52 により分割）**: v1.1.0 と v1.2.0 の合本から v1.2.0 の仕様を分離した文書である。共通制約（§1、うち制約1の例外 F41・F42・F44、制約2・11の例外 F43、制約13のタグ形式キーを含む）、リリースの振り分け表（§2.1）、設計レビュー経緯（付録A）、追加機能の採用記録（付録B）は `docs/FEATURE_SPEC_1.1.0.md` を参照すること。

## 0. 前提

- **v1.1.0 がリリース済みであること**: `docs/FEATURE_SPEC_1.1.0.md` の全機能（G・T・1.1.0 の各 F 節、F8a のうち「正規化を1か所にまとめる」、§3.1 の参加互換 CI、§3.2 のコード共有化と同一ファイル検査、§3.3 の GameTest 基盤、§4.1 の公開 CI）が実装・リリース済みであることを前提とする
- Mod: Item Milestone Log（mod_id: `itemmilestonelog`、パッケージ: `com.pengin0503.itemmilestonelog`）
- 対応: Minecraft 1.21.1 / 26.1.2 / 26.2 / 26.3（`versions/<ver>/` 構成、共通アセットは `common/`、共通純粋ロジックは `common/src/main/java`）。全バージョンで機能は同一でなければならない
- 共通制約（全機能に適用）は `docs/FEATURE_SPEC_1.1.0.md` §1 をすべて適用する。特に 1.2.0 で関わる制約:
  - 制約1の例外（オーナー承認済み）: F41（キー割り当て・アクションバー）・F42（ツールチップ）・F44（NeoForge 設定画面）。本 Mod 自身の画面レイアウトは変更しない
  - 制約2・11の例外（オーナー承認済み）: F43（権限ノードによる管理者判定の拡張）
  - 制約10（ネットワーク互換）: F25 は既存ペイロード・`PROTOCOL_VERSION` を変えず、新しい optional ペイロード `itemmilestonelog:sync_extra` を追加する
  - 制約13（タグ形式のキー）: F8a 以降、`TargetKey.itemId` は `#` で始まるタグ ID になり得るため、1.2.0 で追加・変更する全機能は `TargetKey.isTag()` を考慮する
- 付録A（レビューで見つかった不備と修正の理由 〔X番号〕）および付録B（採用の記録）は `docs/FEATURE_SPEC_1.1.0.md` を参照

## 1. 永続化フィールド・Config（1.2.0 分）

### 1.1 新しく永続化するフィールド（1.2.0 分）

1.21.1（NBT）と 26.x（Codec）で**同じキー名・同じ型**にし、lenient デコード・要素単位スキップの方針は `docs/FEATURE_SPEC_1.1.0.md` §1.1 と同じとする。

| 場所 | NBT キー | 型 | 欠落時の値 | 書き出し | 用途 |
|---|---|---|---|---|---|
| MilestoneData（`players` と同じ階層） | `lastDigestEpoch` | long | 0 | 0 なら書かない | F9 |

- 1.1.0 の F37 で追加済みの PlayerData `lastSeen`（long、epochMillis）は、F52 でもログイン時に書き換える前の値（`previousLastSeen`）として参照する
- F8a のタグキー（`#namespace:path` / `#namespace:path#count`）は、既存の `targets` / `records` / `worldFirsts` / `progressNotified` にそのまま入る（詳細は F8a の「保存形式」）
- 後片付けの規則（1.2.0 で追加・拡張される箇所）:
  - `resettag` / `admin <player> resettag`（F8b）: そのタグキーの `progressNotified` を消す（`reset` と同じ）
  - F24 `renameitem`: F24 の節の規則に従う
- ダウングレード:
  - **1.2.0 → 1.0.1**: **タグのターゲット・記録・世界初（F8）は 1.0.1 の検証で弾かれ、次の保存で失われる**。`lastDigestEpoch` も読み捨てられて次の保存で消える
  - **1.2.0 → 1.1.0**: 1.1.0 もタグを受け付けないので、タグのターゲット・記録・世界初は同じく失われる。`lastDigestEpoch` も失われ、1.2.0 に戻すと F9 の初回の扱い（送らずに時刻だけ記録する）になる。1.2.0 の CHANGELOG と README に明記する

### 1.2 追加する Config の一覧（1.2.0 分）

すべて既存の `Config` クラスのトップレベルに追加する（`docs/FEATURE_SPEC_1.1.0.md` §1 制約8）。既存の機能の挙動は、既定値のままなら 1.1.0 と変わらない。例外は、設定を持たない表示の改善の F48（日本語などのクライアントでプレイ時間と Day の表記が変わる。英語は変わらない）で、CHANGELOG に書く。既定で有効な新機能（F10、F12、F25、F42）も §4 のとおり CHANGELOG に書く。

| キー | 型 | 既定値 | 範囲・検証 | 機能 |
|---|---|---|---|---|
| `milestoneCommand` | 文字列 | "" | | F7 |
| `discordDigestEnabled` | boolean | false | | F9 |
| `discordDigestIntervalDays` | int | 7 | 1〜30 | F9 |
| `autoBackupEnabled` | boolean | true | | F10 |
| `autoBackupGenerations` | int | 5 | 1〜20 | F10 |
| `chatItemHover` | boolean | true | | F12 |
| `scoreboardObjective` | 文字列 | "" | 空、または `^[A-Za-z0-9_.+-]{1,32}$` | F17 |
| `scoreboardWorldFirstObjective` | 文字列 | "" | 同上 | F17 |
| `guiWorldFirstMark` | boolean | true | | F25 |
| `itemTooltipStatus` | boolean | true | クライアントが自分のファイルの値を使う | F42 |
| `discordWorldFirstWebhookUrl` | 文字列 | ""（空 = 使わない） | 空、https の URL、または `env:<変数名>`（F39） | F46 |
| `loginWorldFirstSummary` | boolean | false | | F52 |
| `discordSilentRecords` | boolean | false | | F53 |

- F44 の設定画面には、上の表と 1.0.1・1.1.0 の設定のうち、Webhook の URL の2つ（`discordWebhookUrl`、`discordWorldFirstWebhookUrl`）を除いたすべてを出す（表示名と説明の lang キーが要る）

Config を持たない新機能（制約8の例外、1.2.0 分）: F2（データパックが無ければ何もしない）、F8（使う側がターゲット設定で選ぶ）、F21（データパックが無ければ何もしない）、F24（管理者のコマンド）、F41（キーが既定で割り当てられていないことが OFF）、F43（権限 Mod を入れて設定しなければ従来通り）、F44（使わなければ何も変わらない）、F48（表示の改善。英語の結果を変えない）、F51（コマンドを使わなければ何もしない。人数と順位は `allowLookup` に従う）、F54（プリセットの JSON で使う側が選ぶ）。

## 2. 実施順序（1.2.0）

**1.2.0**

1. F8a（タグの記法）→ F8b（タグの検出・コマンド・表示、1.1.0 の機能のタグへの対応、F13 の `toptag`、F50 のタグ対象）
2. F12 → F48 → F17 → F7 → F2
3. F10 → F24 → F9 → F46 → F49 の `worldfirst` → F53
4. F25 → F21 → F54
5. F51 → F52 → F43（1.2.0 の管理者用コマンドが揃ってから。F49 のノードと F51 の `viewhidden` を含む）
6. F41 → F42 → F44（設定画面は 1.2.0 の設定が揃ってから。lang キーを全設定に足すため）

- 1.2.0 では、タグ（F8a / F8b）を先に入れ、後続の 1.2.0 の機能は最初からタグを考慮して作る（初版の F8 を最後にした順序では各機能を作り直すことになる、という 〔X19〕 の理由は 1.2.0 の中で保つ）
- F17 のスコアの書き込みは、記録数を変える機能（1.1.0 の F22・F23・F31・F33・F37 と 1.2.0 の F24）から呼ぶ。F17 を入れるときに、1.1.0 の機能の各箇所にも足す。F24 は F10 のバックアップを使う
- F25 の追加ペイロードは F21 の GUI の表示に使うので、F21 より先に入れる
- F8b は規模が大きいので PR を分けてよい
- 上記の依存関係を守れば、順序の入れ替え自体は可。分けない場合も、PR は上の段落ごとに小さく分ける

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
- 発火タイミング: 記録確定時（`Tracker.record` 経由。F32 の `recordNow` を含む）と F33 の `grant`、達成したプレイヤーに対してのみ
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

## F7. マイルストーン達成時のコマンド実行

### 仕様
- Config:
- `milestoneCommand`（文字列、既定 "" = 無効）: 記録確定時にサーバーコンソール権限で実行するコマンド（先頭の `/` は付けない形式で記述）
- 置換プレースホルダ: `%player%`（プレイヤー名）、`%uuid%`、`%item%`（アイテム ID。タグターゲットでは `#c:ingots/iron` のようなタグ ID になる）、`%count%`（ターゲット数量）、`%playtime%`（整形済みプレイ時間。`27h 15m 30s` のように空白を含む）、`%worldfirst%`（`true` / `false`）
- 例: `give %player% minecraft:firework_rocket 3`
- 複数の処理をしたい場合は `function <namespace>:<path>` を使う（README に例を書く）
- 〔X13〕実行のタイミング: 記録確定時にキューに積み、`ServerTickEvent.Post` で順に実行する（1 tick あたり最大 20 件、残りは次の tick）。記録処理の途中（スキャンのループ中など）でコマンドを実行しない。`server.execute(...)` はサーバースレッドからは即時実行になり得るので、遅延の手段として使わない
  - 同時に複数のキーが記録された場合（×1 と ×100 を同時に達成など）は、記録1件につき1回、TargetKey の順に実行する
  - import・reset・admin 操作（F33 の `grant` を含む）では実行しない（記録確定時のみ）。F32 の `recordNow` は通常の記録の経路なので実行する
  - サーバー停止時（`ServerStoppingEvent`）: 未実行分を 1 tick あたりの上限を外してすべて実行してから停止処理を続ける（停止直前に積まれた報酬コマンドを取りこぼさない）。同じタイミングで F27 の未送信通知も送ってから空にする
- 〔X13〕実行するソース: `server.createCommandSourceStack()`（権限レベル4相当）に出力の抑止（`withSuppressedOutput` 相当）を付ける。OP のチャットに「[Server: ...]」のフィードバックを出さない。失敗（構文エラー・実行時エラー・戻り値 0）はコールバック（`withCallback` 相当）で検出してサーバーログに warn で残す
- 失敗しても記録処理に影響させない
- 〔X13〕プレイヤー名の検証: `%player%` は、名前が `^[A-Za-z0-9_.*+-]{1,20}$` に一致するときだけ置換する（Geyser / Floodgate 経由の Bedrock プレイヤー名のプレフィックス（`.`・`*`）と16文字超の名前に対応するため。`@` は含めないのでセレクタとしては解釈されない）。一致しない場合はそのプレイヤーについてはコマンドを**実行せず** warn ログを出す
  - 理由: `online-mode=false` のサーバーでは名前が Mojang のアカウント規則で検証されず、`@` などの記号を含み得る（`give %player% ...` が `give @a ...` になる）。初版の「プレイヤー名は Mojang の命名規則で検証済み」はオフラインモードでは成り立たない
- セキュリティ上の注意を Config のコメントと README に明記する: この値はサーバー所有者のみが設定できる（設定ファイル）。`%player%` は上記の検証を通った場合だけ置換される。プレイヤーの指定には `%uuid%` も使える。`%item%` は Mod 内で検証済みの ID（タグ ID を含む）だけが入る

### 実装メモ
- 実行は `server.getCommands().performPrefixedCommand(source, cmd)` 相当を使う（バージョン差異は既存コードに合わせる）
- 世界初かどうかは `Tracker.record` の戻り経路で分かる値を渡す
- 置換と名前の検証は純粋関数にして単体テストする

---

## F8. タグ指定ターゲット

〔X14〕初版の F8 を、データ表現の F8a と、検出・コマンド・表示の F8b に分けた。どちらも **1.2.0** に入る（§2.1）。ただし F8a の「正規化を1か所にまとめる」だけは、タグを受け付けない形で 1.1.0 のうちに行う。

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
- `resettag` は F23 の取り消しの対象にする（`reset` と同じ）。F13 の `toptag` もタグ用のコマンド
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

#### 1.1.0 の機能へのタグの対応（1.2.0）
F8 は 1.2.0 で入る（§2.1）。1.1.0 の機能はタグを扱わずに作られているので、F8b で次を足す（各機能の節の規則に従う）:

| 機能 | 足すこと |
|---|---|
| F1・Q9 | 進捗をタグの合計で数える（上の「検出」） |
| F3・F26・F30・F38 | 表示名を上の「表示名」の規則で出す |
| F4・F18・F27 | Embed とまとめた通知の表示名を同じ規則で出す（プレーンテキストは従来通りキーの文字列） |
| F5・F16 | 判定対象キー R(S) で、タグは「存在して空でない」ものだけを数える（F5 の定義のとおり） |
| F13 | `toptag` を足す |
| F22・F31 | 読み込むキーを F8a の正規化で受け付ける（タグのキーも読む） |
| F23 | `resettag` を取り消しの対象にする |
| F28 | `presetremove` でプリセットのタグのキーも外す（正規化した同じ関数を通すので、追加の処理は照合だけ） |
| F29 | 表の 1.2.0 の行（`addtag` / `removetag` / `resettag` / `toptag`、F24 の `renameitem`、F51 の `info`）を足す |
| F32 | `addTarget` / `removeTarget` / `recordNow` がタグのキーを受け付ける（`addTarget` は存在して空でないタグに限る） |
| F36 | 先に行う確認に、そのアイテムを含むタグの未記録のターゲットも含める |
| F50 | クリエイティブ持ち出し時の該当判定と `targets` の補足に、そのアイテムを含むタグのターゲットも含める |

- F33 の `admin <player> add` / `remove` / `grant` は、本人の `add` / `remove` と同じくアイテムだけを扱う（タグは `admin <player> preset` で配れる）。タグ用の admin コマンドは作らない（必要になったら別に検討する）

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
- F45 で Discord から外しているプレイヤーの行は含めない。行の名前は F47 の規則（保存されている最後の名前）。送り先は `discordWebhookUrl` だけ（F46 の世界初の Webhook には送らない）
- 文面は F4 の Embed 設定に従う（Embed 時はダイジェスト用 Embed 1件に箇条書きを入れる）。件数が多い場合は先頭20件 + 「他 N 件」とする。見出し・各行・「他 N 件」の文面は F18 のキー（`discord.itemmilestonelog.digest.*`）から `discordLanguage` で作る
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
- 頻度制限: 最短1時間に1回まで（〔X16〕メモリ上の前回バックアップ時刻で判定する。サーバー起動時に一度だけ最新のバックアップのファイル名から初期化し、バックアップが無ければ初回は必ず許可する。保存のたびのディレクトリ走査はしない）。加えてサーバー停止時にも1回試みる（頻度制限の対象外）
- 〔X16〕データが空（プレイヤーも世界初も無い）ときは書かない。同じ秒に2回書く場合はファイル名の末尾に `-1`、`-2` を付ける
- 〔X16〕世代の削除は `milestones-<8桁>-<6桁>(-<数字>)?.dat` に一致するファイルだけを対象にする（利用者が置いた他のファイルは消さない）
- 失敗してもサーバー処理に影響させない（ログのみ）

### 実装メモ
- トリガーは LevelEvent.Save（overworld のみ）+ ServerStoppingEvent を使う。ファイルサイズが小さいためサーバースレッドで同期的に書いてよい
- バックアップからの復元は手動（ファイルを戻す）でよい。復元コマンドは作らない
- 〔X16〕README に復元手順を書く: サーバーを止める → バックアップを上記の実ファイルの場所に、実ファイルの名前で置く → 起動する。26.x で 1.21.1 時代のバックアップを使う場合は、`milestones.dat` が無い状態で `data/itemmilestonelog_data.dat` として置く（26.x の既存の移行処理が読む）

---

## F12. チャットのアイテム名にホバーでアイテムを表示（候補 P2）

### 仕様
- サーバーがチャットに出すアイテム名（`MilestoneService.targetDisplayName` が作る Component）に、そのアイテムを表示するホバーイベント（`show_item`）を付ける。記録・ブロードキャスト・世界初・F1 の進捗アラート、`list` / `targets` / `firsts` / admin / F6 / F13 / F16 の各行と、1.1.0 で足した F27 のまとめた通知・F30 の各行・F33 の文面・F38 の上位の行が対象になる
  - 表示するスタック: そのアイテムの既定のスタック（個数1、データコンポーネント無し）
  - タグターゲット（F8）: タグに属するアイテムのうち、レジストリ ID の辞書順で最初のもの。タグが空・存在しない場合はホバーを付けない
  - レジストリに無いアイテム（Mod を外した等）にはホバーを付けない
- Config `chatItemHover`（boolean、既定 true）
- 文字列としての値（`getString()`）は変わらないので、GUI のフィードバック行・HTML・Discord・ログには影響しない
- バニラクライアント: `show_item` のホバーはバニラクライアントでも表示される。注意点として、ホバーの中身は文字列ではなくアイテムのデータ（アイテム ID とコンポーネント）として送られるので、クライアントが知らないアイテムを入れるとそのクライアントはメッセージを解釈できない。ホバーに入れるのはサーバーのレジストリにあるアイテムだけで、バニラクライアントが遊べる構成（サーバーに Mod のアイテムが無い構成）ではバニラのアイテムしか入らないため、問題は起きない見込み。§3 の mineflayer の参加テストで、ホバー付きのメッセージを受けても切断されないことを確認する

### 実装メモ
- ホバーイベントの API はバージョンで異なる（1.21.1 は `new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(stack))`、26.x は `new HoverEvent.ShowItem(stack)`）。既存コードに HoverEvent の使用例は無いので、各バージョンでコンパイルを確認する
- タグの最初のアイテムは F8b のタグのキャッシュから取る（チャットのたびにタグを解決しない）

---

## F17. スコアボード連携（候補 P7）

### 仕様
- Config:
  - `scoreboardObjective`（文字列、既定 "" = 無効）: 各プレイヤーの**記録数**を書き込む objective の名前
  - `scoreboardWorldFirstObjective`（文字列、既定 "" = 無効）: 各プレイヤーが保持している**世界初の件数**を書き込む objective の名前
  - どちらも、検証関数で `^[A-Za-z0-9_.+-]{1,32}$` に限る（コマンドで引用符なしに書ける文字だけにする）。2つに同じ名前が設定された場合は世界初の方を無効として warn ログを出す
- objective が無ければ、最初に書き込むときにダミー基準（`dummy`）で作る。表示名は `scoreboard.itemmilestonelog.records`（en "Milestones" / ja 「マイルストーン」）/ `scoreboard.itemmilestonelog.world_firsts`（en "World Firsts" / ja 「世界初」）を `ServerLang` のフォールバック付きで使う（バニラクライアントには英語で表示される）。既にあれば基準を問わずそのまま使う
- 表示位置は変えない。利用者がバニラの `/scoreboard objectives setdisplay sidebar <名前>` で選ぶ
- スコアの持ち主はプレイヤー名（オフラインのプレイヤーは保存されている最後の名前。空ならそのプレイヤーは書かない）
- 書き込むタイミング:
  - 記録数: サーバー起動時（全プレイヤー）、ログイン時、記録の確定（F32 の `recordNow` を含む）、F33 の `grant`、`reset` / `resetall`（コマンド・GUI）、admin の `reset` / `resetall` / `resettag`、`import`、F22 `importall`、F23 `undo`、F24 `renameitem`。admin の `purge` と F37 の `prunedata` では、そのプレイヤーのスコアを消す
  - 世界初の件数: サーバー起動時（全プレイヤー）、新しい世界初の登録、`firsts reset` / `firsts resetall` / `firsts resettag`、F24 `renameitem`、F31 の `importall` での世界初の読み込み
  - F17 は 1.2.0 で入るので、1.1.0 で入る機能（F22・F23・F31・F32・F33・F37）の書き込みは、F17 を入れるときに足す
  - ログイン時に名前が変わっていた（`lastKnownName` と違う）場合は、古い名前のスコアを両方の objective から消す
- 設定を空に戻しても、既に作った objective とスコアは消さない（利用者がバニラのコマンドで消す）。README に書く
- 他のデータパックが使っている objective の名前を設定すると、そのスコアを上書きする。README で専用の名前（例 `iml_records`）を勧める
- F14 で記録を隠していても書き込む（F14 の節を参照）

### 実装メモ
- スコアボードの API はバージョンで差がある（`ScoreHolder`、`addObjective` の引数）。既存コードに使用例は無いので、各バージョンで確認する
- 「プレイヤーデータ → 書き込むスコア」の計算と、名前の検証は純粋関数にして単体テストする
- CI のボットテストに「記録した後に `/scoreboard players get <bot> <objective>` が記録数を返す」を1ケース加えることが望ましい

---

## F21. データパックで配布できるプリセット（候補 P11）

### 仕様
- 読み込み先: `data/<namespace>/itemmilestonelog/presets/<id>.json`。ファイルの形式はカスタムプリセットと同じ（`name` は任意、`items` は必須。F8a のタグ記法も使える）
- ID はファイル名（拡張子を除く）。既存と同じ `^[a-z0-9_-]{1,32}$` で、予約語（`list` / `save` / `delete` / `reload`）でないこと。サブフォルダの中のファイルは無視して warn ログを出す（ID に `/` が入らないようにする）
- 同じ ID の優先順位: **組み込み > config フォルダのファイル > データパック**。負けた方は使わず warn ログを出す
  - 別々の namespace に同じ ID のファイルがある場合は、namespace の辞書順で先のものを使い、warn ログを出す（同じ namespace・同じパスのファイルは、通常のデータパックの優先順位で1つになる）
- 読み込み: サーバーのリロードリスナーで読み、`/reload` で読み直す。読み直したら GUI を使っているオンラインプレイヤーに再同期する（既存の `syncAllOnlineGuiPlayers`）
  - シングルプレイでは、ワールドを閉じたとき（`ServerStoppedEvent`）にデータパックのプリセットを消す（別のワールドに持ち越さない）
- データパックのプリセットは**読み取り専用**:
  - `preset save <id>` / GUI の保存で、データパックだけにある ID を指定したら拒否する（意図的な制約: ゲーム内操作でデータパックのプリセットを意図せず覆い隠すのを防ぐため。カスタマイズしたい場合は新しい ID で保存するか、config フォルダへ同じ ID のファイルを手で置く）（`message.itemmilestonelog.preset_datapack_protected`、%s = ID: "Preset '%s' comes from a datapack and cannot be overwritten or deleted." / 「プリセット「%s」はデータパックのもので、上書き・削除できません。」）。config フォルダに同じ ID のファイルを手で置いた場合は、上の優先順位でそちらが使われる
  - `preset delete <id>` / GUI の削除も同じ文面で拒否する
  - F25 を受け取ったクライアントの保存画面（`SavePresetScreen`）は、入力中の ID がデータパックのプリセットと一致したら「保存」ボタンを無効にする（G2 の組み込みの ID と同じ扱い）
  - `preset reload` は config フォルダだけを読み直す（データパックは `/reload` で読み直す）。README に書く
- 表示: `preset list` の種類の欄に `message.itemmilestonelog.preset_type_datapack`（en "datapack" / ja 「データパック」）。GUI へは既存の `PresetEntry` で「カスタム」（`builtin` = false）として送る（ペイロードの形式は変えない）。種類の表示と削除ボタンの扱いは F25 の追加ペイロードで行う（F25 を受け取れない 1.0.1 のクライアントでは削除ボタンが出て押せるが、サーバーが上の文面で拒否する）
- 適用・F5 の制覇・F16 の進捗・F19 の通知は、組み込み・カスタムと同じに扱う。`presetsEnabled` が false なら使えない
- Config は追加しない（データパックを入れなければ何も起きない = 使う側の opt-in。F2 と同じく制約8の例外）
- 利点（README に書く）: プリセットがワールド（のデータパック）と一緒に移動・配布できる。Mod パックの製作者は KubeJS の data フォルダなどで同梱できる

### 実装メモ
- リロードリスナーの登録はバージョンで異なる（1.21.1 は `AddReloadListenerEvent`、26.x は `AddServerReloadListenersEvent`。21.1.1 / 26.1.2.71 のソースで確認済み）。`SimpleJsonResourceReloadListener` も 1.21.1（Gson）と 26.x（Codec）で形が違うので、ファイルの一覧だけを受け取り、JSON の解釈は既存の `Presets.parsePresetFile` と共通の関数で行う
- `Presets` はデータパックのプリセットを別に持ち、config の読み込み（`reloadFrom`）とデータパックの読み込みのどちらが起きても、上の優先順位で `loadedPresets` を作り直す（どちらも `synchronized`）
- `PresetDefinition` に種類（組み込み / ファイル / データパック）を持たせる（公開 API ではないので変えてよい）
- 単体テスト: 優先順位、namespace の衝突、無効な ID、サブフォルダの無視

---

## F24. アイテム ID の付け替え（`renameitem`）（候補 P14）

### 仕様
- コマンド追加: `/milestonelog renameitem <oldId> <newId> [confirm]`（権限レベル2）
  - `admin` の下に置かない理由: `admin <player>` の `<player>` と同じ位置になり、`renameitem` という名前のプレイヤーと区別できなくなるため、ルート直下に置く
  - `confirm` なし: **何も変更せず**、変更される件数（プレイヤー数、ターゲット・記録・世界初の件数）と、末尾に `confirm` を付けて実行する旨を返す
  - `confirm` あり: 実行する
- 対象はアイテム ID だけ（タグは対象外）。`<oldId>` / `<newId>` は `IdentifierArgument`。`newId` はレジストリにあるアイテムでなければならない（打ち間違いを防ぐ）。`oldId` はレジストリに無くてよい（外した Mod の ID を付け替えるのが主な用途）。`oldId` = `newId` や `minecraft:air` は拒否する
- 書き換えるもの（個数はそのまま）: 全プレイヤーのターゲット、記録、`progressNotified` のキー、`carriedFromCreative`、ワールドの世界初
- 付け替え先に既にあるときの規則:
  - ターゲット: 重複を除く
  - 記録: playTicks の小さい方を残す
  - `progressNotified`: 大きい方を残す。付け替えの結果、記録済みになったキーのエントリは消す
  - 世界初: 両方の epochMillis が分かっていれば小さい（早い）方、どちらかが不明（0）なら付け替え先にあった方を残す
- 実行の前に F10 のバックアップを1回書く（`autoBackupEnabled` と頻度の制限に関係なく書く。世代の数には含める）。**バックアップが書けなかったら何も変更しない**
- 書き換えないもの: Config の `defaultTargets`、プリセットのファイル、データパック、export のファイル。`defaultTargets` か読み込み済みのプリセットに `oldId` が含まれていたら、その旨を出力に足す（手で直してもらう）
- 終わったら、件数を返し、サーバーログに info で残し、オンラインのプレイヤーに再同期し、F17 のスコアを書き込む。イベント・通知・F7 は動かさない
- 文面:
  - `message.itemmilestonelog.renameitem_preview`（%s = old、%s = new、%s = プレイヤー数、%s = ターゲット数、%s = 記録数、%s = 世界初の数）: "Renaming %s to %s would change %s player(s): %s target(s), %s record(s), %s world first(s). Add 'confirm' to apply (a backup is written first)." / 「%s を %s に付け替えると、%s 人分（目標 %s 件、記録 %s 件、世界初 %s 件）が変わります。実行するには末尾に confirm を付けてください（先にバックアップを書きます）。」
  - `message.itemmilestonelog.renameitem_done`（同じ並び）: "Renamed %s to %s for %s player(s): %s target(s), %s record(s), %s world first(s)." / 「%s を %s に付け替えました: %s 人分（目標 %s 件、記録 %s 件、世界初 %s 件）。」
  - 新しい ID が未登録、またはどちらかが `minecraft:air` のときは既存の `invalid_item`。2つが同じ ID のときは `message.itemmilestonelog.renameitem_same`: "The old and new IDs are the same." / 「付け替え前と後の ID が同じです。」
  - `message.itemmilestonelog.renameitem_backup_failed`: "Could not write a backup, so nothing was changed." / 「バックアップを書けなかったため、何も変更していません。」
  - `message.itemmilestonelog.renameitem_config_note`（%s = old）: "defaultTargets or a preset still contains %s. Edit them by hand." / 「defaultTargets かプリセットに %s が残っています。手で直してください。」

### 実装メモ
- 書き換えは「MilestoneData、old、new → 件数」の関数にし、付け替え先との衝突の規則を単体テストする

---

## F25. GUI の記録に世界初の印（候補 P17）

### 仕様
- 新しい optional ペイロード `itemmilestonelog:sync_extra`（S2C）を追加する（§1 制約10の方法。既存の `SyncMilestonesPayload` は変えない。登録は既存の registrar に足す。`PROTOCOL_VERSION` は変えない）
- 中身は NBT の compound 1つ（`ByteBufCodecs.COMPOUND_TAG` 相当）。受け取り側は**知らないキーを無視する**。以後クライアントへ送るデータを足すときは、新しいペイロード型を作らずにこの compound にキーを足す
  - `worldFirsts`: 文字列リスト。受信者本人が世界初を保持しているキー（エンコード済み TargetKey）
  - `readOnlyPresets`: 文字列リスト。F21 のデータパックのプリセットの ID
  - 各リストは最大 4096 件（既存の同期と同じ上限）。受け取り側は、TargetKey としてパースできない要素とプリセット ID として無効な要素を捨てる
- 送るタイミング: サーバーが `SyncMilestonesPayload` を送る直前に毎回、`player.connection.hasChannel(sync_extra の TYPE)` が true のときだけ送る（1.0.1 のクライアントとバニラクライアントには送らない）。順序を「extra → 既存の同期」にして、画面が既存の同期で描き直されるときに extra が届いているようにする
- クライアント:
  - 受け取った内容を保持する。サーバーから切断したら消す（別のサーバー・ワールドに持ち越さない）。1.0.1 のサーバーからは届かないので、空のまま（従来通りの表示）
  - 記録行の状態の文字列: 世界初を保持しているキーは `gui.itemmilestonelog.status.recorded_world_first`（en "✔ ★ %s" / ja 「✔ ★ %s」、%s は既存と同じ記録の整形）を使う。それ以外は従来の `gui.itemmilestonelog.status.recorded`。色・位置は変えない（制約1）
  - プリセット一覧: `readOnlyPresets` にある ID は、種類の表示を `gui.itemmilestonelog.presets.type.datapack`（en "Datapack" / ja 「データパック」）にし、削除ボタンを出さない（組み込みのプリセットの行と同じ扱い。既存の画面は組み込みの行に削除ボタンを作らないので、レイアウトは変わらない）
- Config `guiWorldFirstMark`（boolean、既定 true）。false のときは `worldFirsts` を空で送る。`readOnlyPresets` は常に送る
- README に「★ = あなたが世界初」と書く

### 実装メモ
- 同期の送信箇所（`MilestoneService.syncPlayerIfOpen`、`ModNetworking.handleRequestSync` / `handleAction`）を1つの送信関数にまとめ、そこで extra を先に送る
- 単体テスト: compound の往復、知らないキーと不正な要素の無視、上限の切り詰め

---

## F41. インベントリでカーソルを合わせたアイテムを、キー1つで目標に追加（候補 P35）

### 仕様
- 背景: 目標を足す手段は、GUI のアイテム選択画面での検索、`add`（ID の入力）、`addhand`（手に持つ）だけ。チェストの中やクリエイティブの一覧で見つけたアイテムを、そのまま足せない
- クライアントのキー割り当て「カーソルのアイテムを目標に追加」（`key.itemmilestonelog.add_hovered`）を足す。既定は割り当てなし（GUI を開くキーを既定なしにした issue #17 と同じ理由）。カテゴリは既存の GUI を開くキーと同じ。衝突の判定は `KeyConflictContext.GUI`
- 動作: コンテナの画面（`AbstractContainerScreen` とその派生。インベントリ・チェスト・クリエイティブなど）で押したとき、カーソルの下のスロットにアイテムがあれば、そのアイテム ID・個数 1 で、既存の `MilestoneActionPayload` の `ADD_TARGET` を送る
  - キーは NeoForge の `ScreenEvent.KeyPressed.Post` で受け取る（21.1.1 / 26.1.2.71 / 26.2.0.57 / 26.3.0.76 にある）。**Post は、画面がキーを処理しなかったときだけ起こる**ので、クリエイティブの検索欄や他 Mod の検索欄に文字を打っている間は反応しない（Pre を使うと、検索欄に打った文字でも目標が足される）
  - カーソルの下のスロットは NeoForge が足した公開メソッドで得る。**名前がバージョンで違う**: 1.21.1 / 26.1.2 / 26.2 は `getSlotUnderMouse()`、26.3 は `getHoveredSlot()`（各バージョンの NeoForge の下限の userdev のパッチで確認。Minecraft 本体の `hoveredSlot` は protected）。アクセストランスフォーマーは要らない
  - 送るのはアイテム ID だけ（ポーションやエンチャントの本など、データの違いは目標に含まれない。既存の `add` と同じ）
  - 既存のペイロードと値を使うので通信の形式は変わらず（制約10）、1.0.1 / 1.1.0 のサーバーでも動く。サーバーの検証・上限・速度の制限（`tryAcquireRateLimit`）は既存のまま
  - サーバーに Mod が無い（チャンネルが無い）ときは、GUI を開くキーと同じく `server_not_installed` をアクションバーに出す
- 結果の表示: サーバーは既存の処理どおりフィードバック付きの同期を返す。現在のクライアントは、Mod の画面が開いていないと同期を捨てる（`ClientMilestoneHandler.onSyncReceived`）。**Mod の画面以外が開いているとき、同期にフィードバックがあれば、それをアクションバーに出す**（`sendOverlayMessage`。26.x の既存の呼び方。1.21.1 は既存コードの書き方に合わせる）
  - このフィードバックは、このキーで送った操作の返事に限る（クライアントが、送ってから2秒以内の最初の同期だけを表示する）。記録のたびに届く同期（`syncPlayerIfOpen`）にはフィードバックが無いので、元から表示されない
- 制約1の例外（オーナーが 2026-11-23 に承認）: キー割り当ての追加と、アクションバーへの表示。Mod の画面（MilestoneScreen など）のデザインは変えない
- Config は追加しない（キーが既定で割り当てられていないことが OFF。制約8の例外）

### 実装メモ
- 手動の確認（バニラの本物のクライアントでしか確かめられない）: インベントリ・チェスト・クリエイティブの一覧で足せること、クリエイティブの検索欄に文字を打っても足されないこと、既に目標のアイテムで既存の文面がアクションバーに出ること、Mod の無いサーバーで `server_not_installed` が出ること
- 文面: キー名 `key.itemmilestonelog.add_hovered`: en "Add hovered item to targets" / ja 「カーソルのアイテムを目標に追加」

---

## F42. アイテムのツールチップに記録の状態を出す（候補 P36）

### 仕様
- 背景: そのアイテムが自分の目標か、記録済みかを確かめるには、GUI か `targets` を開く必要がある
- Mod 入りのクライアントで、アイテムのツールチップ（NeoForge の `ItemTooltipEvent`。21.1.1 / 26.1.2.71 / 26.2.0.57 / 26.3.0.76 にある）の末尾に行を足す。そのアイテム ID のキー（個人ターゲット + `defaultTargets` + 記録）ごとに1行:
  - 目標で未記録: `gui.itemmilestonelog.item_tooltip.target`（%s = 個数の表記。count が 1 なら空、2 以上なら ` ×N`）: en "Milestone target%s (not yet obtained)" / ja 「目標%s（未取得）」
  - 記録済み: `gui.itemmilestonelog.item_tooltip.recorded`（%s = 個数の表記、%s = プレイ時間）: en "Milestone recorded%s — %s" / ja 「記録済み%s — %s」。プレイ時間は F48 の書式（1.2.0 は F48 と同時なので、言語に合わせた書式）
  - 並びは TargetKey の順。最大3行、超えた分は `gui.itemmilestonelog.item_tooltip.more`（%s = 件数）: en "...and %s more" / ja 「…ほか %s 件」
  - 色は灰色（`ChatFormatting.GRAY`）。目標でも記録でもないアイテムには何も足さない
  - 1.2.0（F8b）: タグのターゲットは、そのアイテムを含むタグのキーも出す（表示名は F8b の規則）
- データ: クライアントは最新の同期（`SyncMilestonesPayload`）を保持し、受け取るたびに「アイテム ID → キーの一覧」の表を作り直す（ツールチップは JEI / EMI の一覧などで頻繁に作られるので、ツールチップの処理は表を引くだけにする）。切断したとき（`ClientPlayerNetworkEvent.LoggingOut`）に捨てる
  - サーバーは、Mod 入りのクライアント（`hasChannel(SyncMilestonesPayload.TYPE)`）に、**ログイン時にも**同期を送る（`openScreen` = false、フィードバックなし。`PlayerLoggedInEvent` で送る。`OnDatapackSyncEvent` はログインの途中で起こるので使わない）。1.0.1 / 1.1.0 のクライアントは画面が無いと同期を捨てるので、送っても害は無い
  - 現在のサーバーは記録のたびに Mod 入りの全クライアントへ同期を送っている（`syncPlayerIfOpen` はチャンネルの有無しか見ない）ので、記録・目標の変更はそのまま反映される。1.0.1 / 1.1.0 のサーバーではログイン時の同期が無いので、GUI を一度開くか、何かを記録するまで出ない（README に書く）
  - 既知の制限: `defaultTargets` の変更はプレイヤーごとの同期を起こさないので、次の同期まで古い表のまま
- Config `itemTooltipStatus`（boolean、既定 true）。COMMON（26.3 は LOCAL）の設定はクライアントでは各自の PC のファイルが読まれるので、各自が切り替えられる。サーバーの値は使わない
- 制約1の例外（オーナーが 2026-11-23 に承認）: ツールチップへの行の追加。Mod の画面のデザインは変えない。バニラクライアントでは何も変わらない
- `ItemTooltipEvent` はサーバー側でも起こり得る（`ItemStack.getTooltipLines` を呼ぶ Mod がある）ので、クライアント専用のエントリポイント（`Dist.CLIENT`）でだけ登録する

### 実装メモ
- 「同期の内容、アイテム ID → 足す行」は純粋関数にして単体テストする（目標・記録・複数キー・3行の上限）

---

## F43. 権限 Mod で管理者のコマンドを許可できるようにする（候補 P37）

### 仕様
- 背景: 管理者のコマンドは `.requires(hasPermission(2))`（OP の権限レベル2）だけで判定している。OP を渡さずに、一部のコマンドだけをスタッフに許可する運用ができない
- NeoForge の権限 API（`PermissionAPI`、`PermissionGatherEvent.Nodes`、`PermissionNode`。21.1.1 / 26.1.2.71 / 26.2.0.57 / 26.3.0.76 のソースで同じ API を確認）に、次の boolean のノードを登録する:

| ノード | 許可するもの |
|---|---|
| `itemmilestonelog.admin` | `admin` の下のすべて（F33 を含む。1.2.0 の F8b `admin <player> resettag` も） |
| `itemmilestonelog.firsts.reset` | `firsts reset` / `firsts resetall`（1.2.0 の F8b `firsts resettag` も） |
| `itemmilestonelog.export.all` | `exportall` / `exporthtmlall` / `exportcsvall` |
| `itemmilestonelog.import.all` | `importall`、および `import` で他のプレイヤーの書き出したファイルを読み込むこと（現在は権限レベル2で判定している箇所） |
| `itemmilestonelog.prunedata` | `prunedata` |
| `itemmilestonelog.renameitem` | `renameitem` |
| `itemmilestonelog.discordtest` | `discordtest`（F49） |
| `itemmilestonelog.presets.manage` | 専用サーバーでのカスタムプリセットの保存・削除（G1 の判定） |
| `itemmilestonelog.viewhidden` | `allowLookup` が false のときの `lookup` / `top` / `recent` / `stats` の使用（および F51 の `info` の人数と順位）と、F14 で隠した記録の閲覧 |

- 判定: プレイヤーのソースは「従来の判定（`.requires` の権限レベル2、または制約11の `isPrivileged`）を満たす、**または**ノードが true」。プレイヤー以外のソース（コンソール・RCON・コマンドブロック・関数）は従来の判定だけで、`PermissionAPI` を呼ばない（`PermissionAPI.getPermission` は `ServerPlayer` を必要とし、権限 API の初期化はサーバーの起動時に行われるため）
  - ノードの既定（`PermissionResolver`）は「そのプレイヤーが権限レベル2以上」。権限 Mod が無い（NeoForge の既定のハンドラ）なら、判定の結果は従来と同じ
  - 「または」なので、ノードで OP から権限を**取り上げることはできない**（許可を足せるだけ）。README に書く
  - 判定は1か所（`MilestonePermissions.check(source, node)` など）にまとめ、各コマンドの `.requires` と、制約11の `isPrivileged` を使う箇所から呼ぶ
- 権限 Mod の設定: NeoForge の設定 `permissionHandler` を、権限 Mod のハンドラにする必要がある（既定は `neoforge:default_handler`。詳細は権限 Mod の説明に従う）。README に書く
  - 設定の置き場所はバージョンで違う: 1.21.1 / 26.1.2 / 26.2 はワールドごとの SERVER 型の設定（`serverconfig/neoforge-server.toml`）、26.3 は SYNCED 型の設定（`NeoForgeSyncedConfig`）。各バージョンの NeoForge の下限のソースで確かめた。26.3 のファイルの場所は、README に書く前に 26.3 の NeoForge で確かめる
- コマンドの補完: クライアントに送るコマンドの木は `requires` で絞られ、ログイン時などに送られる。権限を変えた直後は、再参加するまで補完に出ないことがある（実行できるかは実行の時点で判定されるので、すぐ変わる）。権限 Mod が木を送り直すかは権限 Mod による。README に書く
- F29 の `help` は、各コマンドの `requires`（`CommandNode#canUse`）で出すかを決めているので、そのままノードに従う
- 制約2・11の例外（オーナーが 2026-11-23 に承認）: 既存の管理者用コマンドの `.requires` を「権限レベル2以上、またはノード」に広げる。OP と、権限 Mod を使わないサーバーの挙動は変わらない
- Config は追加しない（権限 Mod を入れて設定しなければ従来通り。制約8の例外）

### 実装メモ
- ノードは `PermissionGatherEvent.Nodes`（Mod のイベントバス）で登録する。登録していないノードを `getPermission` に渡すと `UnregisteredPermissionException` になる
- 26.x の Minecraft は独自の権限の仕組み（`Permissions` / `PermissionSet`。本 Mod の 26.x の `MilestoneService.isPrivileged` も使っている）を持つ。権限レベルの判定はそれを使い続け、ノードは NeoForge の API だけで判定する（全バージョンで同じ動作にする）
- 単体テスト: 判定（プレイヤー以外 × レベル、プレイヤー × レベル × ノード）、ノードの表と、それを使うコマンド・箇所の対応（表に無いノードを使わない）
- 手動の確認: LuckPerms などの権限 Mod を入れた専用サーバーで、OP でないプレイヤーにノードを与えて実行できること

---

## F44. NeoForge の設定画面を使えるようにする（候補 P38）

### 仕様
- 背景: 設定を変えるには toml を手で編集する必要がある。NeoForge には、Mod の設定から画面を自動で作る `ConfigurationScreen` と、それを Mod 一覧の「設定」ボタンに出す `IConfigScreenFactory` がある（21.1.1 / 26.1.2.71 / 26.2.0.57 / 26.3.0.76）が、本 Mod は登録していない
- クライアントのエントリポイントで、`IConfigScreenFactory` として `ConfigurationScreen` を登録する（`modContainer.registerExtensionPoint`。クライアントのエントリポイントのコンストラクタで `ModContainer` を受け取る）
- **Webhook の URL（`discordWebhookUrl`、F46 の `discordWorldFirstWebhookUrl`）は画面に出さない**。`ConfigurationScreen` のフィルター（`ConfigurationSectionScreen.Filter`。4バージョンにある）で除く。理由: 画面には値がそのまま出るので、配信やスクリーンショットで URL が漏れる（F39）。これらはファイルで設定する
- 各設定の表示名と説明の lang キーを en / ja に足す: `itemmilestonelog.configuration.<キー>` と `itemmilestonelog.configuration.<キー>.tooltip`（`ConfigurationScreen` がこの規則でキーを引く。説明のキーが無いと英語のコメントが出るが、日本語のクライアントのために ja にも足す。制約5により en にも同じキーが要る）。画面の題名 `itemmilestonelog.configuration.title`（%s = Mod の表示名）も足す
  - 対象は、画面に出す全設定（1.0.1 の 11 項目と §1.2 の全項目から、上の2つを除いたもの）
- 注意（README と、画面の題名の下の説明に使える箇所が無いので README に書く）: COMMON（26.3 は LOCAL）の設定はその PC のファイルなので、マルチプレイのサーバーに入っているときに変えても、サーバーには効かない（効くのは F42 の `itemTooltipStatus` など、クライアントが使う設定だけ）。シングルプレイでは全ワールドに効く（X1）
  - 26.3 の LOCAL 型の設定が画面に出ることは、26.3.0.76 のソースで確かめた（`ConfigurationScreen` は全種類の設定を並べ、編集を止めるのは SYNCED 型だけ）
- 制約1・R5 の例外（オーナーが 2026-11-23 に承認）: 本 Mod の画面ではなく、NeoForge の汎用の画面を使えるようにする。本 Mod の画面のデザインは変えない
- Config は追加しない（使わなければ何も変わらない。制約8の例外）

### 実装メモ
- 単体テスト（静的な検査）: `Config.SPEC` の全項目（画面に出さない2つを除く）について、en_us.json に表示名と説明のキーがあること。設定を足したときの書き忘れを防ぐ
- 手動の確認: 4バージョンで画面が開き、値を変えて保存できること、Webhook の URL が出ないこと

---

## F46. 世界初だけを別の Webhook に送る（候補 P40）

### 仕様
- 背景: `discordNotifyScope` は `all` か `world_first` のどちらか一方。全記録を記録用のチャンネルに、世界初だけをお知らせ用のチャンネルに、という分け方ができない
- Config `discordWorldFirstWebhookUrl`（文字列、既定 "" = 使わない）。F39 の `env:<変数名>` も使える。検査は `discordWebhookUrl` と同じ（https）
- 設定されていれば（解決できれば）、**世界初の投稿はこちらだけに送る**（二重に送らない）。`discordWebhookUrl` には、`discordNotifyScope` に従って世界初**以外**を送る
  - `discordNotifyScope` が `world_first` でこの設定があると、`discordWebhookUrl` には記録の投稿が何も送られない（F19 と 1.2.0 の F9 は送られる）
  - `discordWebhookUrl` が空でこの設定があれば、世界初だけが送られる
  - F27 のまとめ方: 送り先ごとに分けてから、それぞれで F27 のしきい値（3件）を適用する（世界初が2件・他が5件なら、世界初は1件ずつ、他はまとめた1通）
  - F19 の制覇の投稿と F9 のダイジェストは `discordWebhookUrl` に送る（この設定には送らない）
- F35 の 429・404・401 の扱いは URL ごとに行う（片方の Webhook が消されても、もう片方は送り続ける）
- F45 で外している人の分は送らない
- 世界初の文面・Embed は従来通り（F4・F18）
- Config の ON/OFF は値が空かどうか（制約8の「設定値が空なら無効」）

### 実装メモ
- 「投稿の種類、2つの URL の設定、`discordNotifyScope` → 送り先」は純粋関数にして単体テストする

---

## F48. プレイ時間と「Day」の表記をプレイヤーの言語に合わせる（候補 P42）

### 仕様
- 背景: チャットのプレイ時間は `Playtime.format` の `27h 03m 05s`、記録の整形は `Playtime.formatRecord` の `(Day 5, 2026-11-23)` で、日本語の文面の中でも英語の表記になる（F18 で「英語表記のまま」としたもの）
- **チャットと GUI だけ**、lang キーで組み立てる:
  - `message.itemmilestonelog.playtime_format`（%s = 時、%s = 分（2桁）、%s = 秒（2桁））: en "%sh %sm %ss" / ja 「%s時間%s分%s秒」。分と秒の0埋めはコードで行い、文字列として渡す
  - `message.itemmilestonelog.record_day_date`（%s = プレイ時間、%s = Day（1 始まり）、%s = 日付）: en "%s (Day %s, %s)" / ja 「%s（%s日目、%s）」
  - `message.itemmilestonelog.record_day`（%s = プレイ時間、%s = Day）: en "%s (Day %s)" / ja 「%s（%s日目）」
  - `message.itemmilestonelog.record_date`（%s = プレイ時間、%s = 日付）: en "%s (%s)" / ja 「%s（%s）」
  - Day も日付も不明なら、プレイ時間だけ（従来通り）。日付の書式は `yyyy-MM-dd` のまま、タイムゾーンは F11
  - **en_us の結果は従来の `Playtime.format` / `formatRecord` の文字列と一字一句同じにする**（英語のクライアントでは何も変わらない）
- サーバーから送る文面は `ServerLang.tr(受信者, ...)` で組み立てた Component を引数として入れ子にする（Mod 入りのクライアントは自分の言語で、バニラクライアントは `translatableWithFallback` の受信者の言語の値で表示される）。ブロードキャストと世界初のアナウンスは、既存どおり受信者ごとに組み立てる
- GUI（MilestoneScreen のヘッダーと記録の状態の行、F42 のツールチップ）はクライアントの言語で `Component.translatable` で組み立てる。記録の状態の行は既存の幅での切り詰め（`plainSubstrByWidth`）のままにし、日本語でどこまで見えるかを手動で確かめる
- **英語の書式のままにするもの**: Discord（F4・F18・F27・F19・F9）、サーバーログ、書き出し（JSON・F3 の HTML・F26 の CSV）、API。機械で読む人がいるため。F18 の「英語表記のまま」は Discord について引き続き有効
- 1.2.0（F12）: ホバーを付けたチャットのメッセージも同じ組み立て方にする
- Config は追加しない（表示の文面の改善で、en_us の結果は変わらない。§1.2 の例外として CHANGELOG に書く）

### 実装メモ
- `Playtime.formatRecord` は文字列を返し、チャット・GUI・Discord・ログで使われている（26.1.2 で `Playtime.format` / `formatRecord` の呼び出しは 14 か所）。文字列版は Discord・ログ・書き出し用に残し、チャットと GUI 用に Component を返す版を作って置き換える
- 単体テスト: en_us の Component の文字列が文字列版と一致すること（Day・日付の有無の4通り、0 時間、100 時間以上）、ja_jp の文面

---

## F51. アイテム1つの状態を見る（`info <item>`）（候補 P46）

### 仕様
- 背景: あるアイテムについて「自分の目標か・記録済みか・世界初は誰か・何人が記録したか」を知るには、`targets`、`firsts`、F13 の `top <item>` を別々に見る必要がある。1.2.0 の F42 のツールチップは Mod 入りのクライアントでしか出ないため、バニラクライアント向けの確認手段として追加する
- コマンド追加（OP 不要。実行者はプレイヤーに限る）:
  - `/milestonelog info <item> [<count>]`: `<item>` は既存の `reset` と同じ `IdentifierArgument`（Mod を外したアイテムの記録も指定できる）。`<count>` は省略時 1、範囲 1〜1,000,000
  - `/milestonelog info hand`: メインハンドのアイテム（個数 1）を対象にする。空手なら既存の `addhand` と同じ `empty_hand` の文面
- 出力（TargetKey = `<item>#<count>` について）:
  1. 見出し: ターゲット表示名（F12 のホバー付き）
  2. 自分の状態:
     - 記録済み: 記録の整形（F48 の書式・F11 のタイムゾーン）
     - 未記録かつ実効ターゲット（個人 + `defaultTargets`）にある: 目標であることを表示。数量ターゲット（count > 1）なら現在の所持数も表示（`carriedFromCreative` のアイテムは 0 とし、F50 の `target_creative_carried` を付ける）
     - 目標でも記録でもない: 目標ではない旨
  3. 世界初: そのキーの世界初があれば、保持者の名前（F47 の名前の規則）と記録の整形（F48 の書式）。無ければ「まだ無し」
  4. 記録したプレイヤーの数と自分の順位:
     - F13 の `top <item>` と同じ数え方（全プレイヤーが対象、F14 で隠しているプレイヤーは `isPrivileged` を満たさない閲覧者には数えない（閲覧者本人は含める）、同順位の規則も同じ）
     - **`allowLookup` が false で `isPrivileged`（F43 の `itemmilestonelog.viewhidden` を含む）を満たさない閲覧者には、この行を出さない**（1〜3 のみ出す）
  5. 関連するタグターゲット（F8b）: `<item>` がレジストリに存在し、そのアイテムを含むタグのターゲット（自分の実効ターゲット・自分の記録・世界初のいずれかにあるキー）があれば、そのタグキーごとに1行で自分の状態（記録済み / 目標・所持数 / 世界初のみ）を添える（最大 10 件、超えたら「ほか N 件」）
- 補完: `info <item>` は、自分の実効ターゲット・自分の記録・ワールドの誰かが記録しているアイテム ID（`allowLookup` = false で非特権の場合は自分の実効ターゲットと自分の記録だけ）
- 文面（en / ja）:
  - `message.itemmilestonelog.info_header`（%s = ターゲット表示名）: en "Item info: %s" / ja 「アイテム情報: %s」
  - `message.itemmilestonelog.info_status_recorded`（%s = 記録の整形）: en "Your status: Recorded (%s)" / ja 「あなたの状態: 記録済み（%s）」
  - `message.itemmilestonelog.info_status_target`: en "Your status: Target (not yet obtained)" / ja 「あなたの状態: 目標（未取得）」
  - `message.itemmilestonelog.info_status_target_progress`（%s = 現在数、%s = 目標数）: en "Your status: Target (%s / %s)" / ja 「あなたの状態: 目標（%s / %s）」
  - `message.itemmilestonelog.info_status_none`: en "Your status: Not a target" / ja 「あなたの状態: 目標ではありません」
  - `message.itemmilestonelog.info_world_first`（%s = 保持者名、%s = 記録の整形）: en "World First: %s (%s)" / ja 「世界初: %s（%s）」
  - `message.itemmilestonelog.info_world_first_none`: en "World First: None yet" / ja 「世界初: まだありません」
  - `message.itemmilestonelog.info_players_ranked`（%s = 人数、%s = 自分の順位）: en "Recorded by: %s player(s) (Your rank: #%s)" / ja 「記録した人: %s人（あなたの順位: %s位）」
  - `message.itemmilestonelog.info_players_unranked`（%s = 人数）: en "Recorded by: %s player(s)" / ja 「記録した人: %s人」
  - `message.itemmilestonelog.info_tag_header`: en "Related tag targets:" / ja 「関連するタグの目標:」
  - `message.itemmilestonelog.info_tag_entry`（%s = タグ表示名、%s = 状態の短い表記）: en "- %s: %s" / ja 「- %s: %s」
  - `message.itemmilestonelog.info_tag_more`（%s = 件数）: en "...and %s more" / ja 「…ほか %s 件」
- Config は追加しない（コマンドを使わなければ何もしない。人数と順位は F6 の `allowLookup` に従う。制約8の例外）

### 実装メモ
- 人数・順位の集計は F13 の純粋関数を共用する。世界初の保持者名は F47 の純粋関数を共用する
- 単体テスト: 自分の状態の分岐（記録済み / 目標 / 未目標）、`allowLookup` と F14 の除外、関連タグの抽出と 10 件の上限

---

## F52. 留守の間の世界初をログイン時に知らせる（候補 P47）

### 仕様
- 背景: 世界初のアナウンスはオンラインの人にしか届かない。F37 で保存する `lastSeen`（最後にいた時刻）を使えば、留守の間に起きた世界初を、戻ったときに知らせられる
- Config `loginWorldFirstSummary`（boolean、既定 false）
- 動作: ログイン時（`Tracker.onPlayerLoggedIn`）、`lastSeen` を新しい時刻で書き換える**前の値**（`previousLastSeen`）を取り出し、次の条件をすべて満たすときにチャットでサマリーを送る
  - `loginWorldFirstSummary` が true
  - 本人の `muteBroadcasts` が false（他人の記録のお知らせを止めている人には送らない）
  - `previousLastSeen > 0`（初参加や 1.1.0 以前からの移行直後は送らない）
  - 対象の世界初: `worldFirsts` のうち `epochMillis > previousLastSeen && epochMillis <= now` かつ、保持者の UUID が本人と異なるもの（`epochMillis == 0` の古い記録は含めない）
  - 対象が 0 件なら何も送らない
- 表示形式:
  - 対象を `epochMillis` の新しい順（降順。同時刻なら TargetKey の順）に並べ、先頭最大 5 件を表示する。6 件以上ある場合は末尾に「ほか N 件」を付ける
  - 保持者の名前は F47 の名前の規則（保存されている最後の名前。変わっていれば旧名併記）で `Component.literal` 埋め込み。アイテム名は F12 のホバー付き、記録の整形は F48 の書式・F11 のタイムゾーン
  - 1回のログインにつき見出し + 最大5行（+ 必要なら「ほか N 件」）で送る
- 既知の挙動（README に書く）: サーバーのクラッシュ等でログアウト時の `lastSeen` が書かれなかった場合は、前回のログイン以降の世界初が対象になる（重複して届くことがあるが実害は無い）
- 文面（en / ja）:
  - `message.itemmilestonelog.login_world_first_header`（%s = 件数）: en "★ %s World First(s) were recorded while you were away:" / ja 「★ 留守の間に %s 件の世界初が記録されました:」
  - `message.itemmilestonelog.login_world_first_entry`（%s = 保持者名、%s = ターゲット表示名、%s = 記録の整形）: en "- %s — %s (%s)" / ja 「- %s — %s（%s）」
  - `message.itemmilestonelog.login_world_first_more`（%s = 残りの件数）: en "...and %s more (use /milestonelog firsts)" / ja 「…ほか %s 件（/milestonelog firsts で一覧を見られます）」

### 実装メモ
- `Tracker.onPlayerLoggedIn` で `lastSeen` を更新する**前に** `previousLastSeen` をローカル変数に確保してから `lastSeen = now` を書き込む
- 「`worldFirsts`、本人の UUID、`previousLastSeen`、`now` → 対象の一覧（降順・最大5件＋残り件数）」は純粋関数にして単体テストする（`previousLastSeen == 0`、本人の世界初の除外、境界値 `== previousLastSeen` / `== now`、5件以下と6件以上）

---

## F53. 通常の記録を、Discord の通知音なしで投稿する（候補 P48）

### 仕様
- 背景: 記録の多いサーバーでは、投稿のたびにチャンネルの参加者に通知が飛ぶ。Discord の Execute Webhook は、リクエスト JSON の `flags` に `SUPPRESS_NOTIFICATIONS`（`1 << 12` = `4096`。「プッシュとデスクトップの通知を起こさない」）を受け付ける（Discord の開発者向け文書 Execute Webhook / Message Flags、2026-11-23 に確認）
- Config `discordSilentRecords`（boolean、既定 false）
- 動作: `discordSilentRecords` が true のとき、**世界初を含まない**通常の記録の投稿の JSON ペイロードに `"flags": 4096` を付ける（プレーンテキスト・F4 の Embed のどちらにも付ける）
  - F27 のまとめた投稿: そのまとめの中に世界初が1件も含まれない場合は `"flags": 4096` を付ける。世界初を1件でも含む場合は付けない（従来通り通知する）
  - 世界初の単独投稿、F19 のプリセット制覇通知、F9 の週次ダイジェスト、F49 の `discordtest` には付けない（従来通り通知する）
  - F46 で世界初を別の Webhook（`discordWorldFirstWebhookUrl`）に送っている場合、`discordWebhookUrl` へ送られる記録の投稿は世界初を含まないため、すべて `"flags": 4096` が付く
- `discordSilentRecords` が false のときは `flags` フィールド自体を JSON に出さない（既存の出力を変えない）

### 実装メモ
- `DiscordNotifier` のペイロード組み立て関数に `boolean suppressNotifications` 引数を足し、true のときだけ `payload.addProperty("flags", 4096)`（定数 `SUPPRESS_NOTIFICATIONS = 1 << 12`）を出力する
- 単体テスト: false で `flags` が出ないこと、true のとき通常記録と世界初無しの F27 まとめに `4096` が付き、世界初単独・世界初含む F27 まとめ・F19・F9・F49 には付かないこと

---

## F54. プリセットの表示名を言語ごとに書けるようにする（候補 P49）

### 仕様
- 背景: 組み込みのプリセットの表示名は lang キーで言語ごとに出るが、カスタム（JSON）と 1.2.0 の F21 のデータパックのプリセットの `name` は文字列1つだけ（`Presets.parsePresetFile`）。F21 でプリセットを配るパック製作者が、複数の言語の利用者に向けた名前を付けられない
- JSON の `name` フィールドの拡張:
  - 従来の文字列（例: `"name": "Starter Kit"`）に加えて、言語コード → 表示名の JSON オブジェクト（例: `"name": {"en_us": "Starter Kit", "ja_jp": "スターターキット"}`）を受け付ける
  - 言語コードのキーは小文字に正規化して扱う。空・空白のみのキーや、値が文字列でない／空白のみの要素は無視する。256 文字の上限は**各言語の値ごと**に適用する
  - オブジェクトに有効な値が1つも無い場合は、`name` 未指定と同じくプリセット ID を表示名にする
- 表示名の選び方（優先順）:
  1. 受け取るプレイヤー（または用途の言語）の言語コード（小文字）に一致する値
  2. `"en_us"` の値
  3. オブジェクトの最初の有効な値（JSON の記述順、`LinkedHashMap` で保持）
  4. プリセット ID
  - `name` が文字列だった場合は、すべての言語でその文字列を使う（従来通り）
- 使う箇所:
  - GUI の同期（`MilestoneService.createSyncPayload`）: サーバーは同期をプレイヤーごとに作っているので、受け取るプレイヤーの言語で解決した文字列を既存の `PresetEntry.displayName` に入れて送る。**ペイロードの形式は変えない**（制約10）
  - チャット（`preset list`、F5 の制覇通知、F16 の `presetprogress` の見出しなど）: 受け取るプレイヤーの言語で解決した文字列を `Component.literal` で埋め込む（制約12）。コンソール・RCON には `"en_us"` 優先で解決する
  - F19 の Discord 制覇通知: F18 の `discordLanguage` で解決した文字列を使う
- G2 の上書き保存との整合: 既存のカスタムプリセットを上書き保存するとき、既存ファイルに有効なオブジェクト形式の `name` があれば、文字列の `name` と同じく**オブジェクトの `name` をそのまま引き継いで書き出す**
- 後方互換（README に書く）: 1.0.1 / 1.1.0 はオブジェクト形式の `name` を読めないため、プリセット ID を表示名にする既存の動作になる（読み込み自体は失敗しない）
- Config は追加しない（プリセットの JSON で使う側が選ぶ。制約8の例外）

### 実装メモ
- `PresetDefinition` の表示名を「単一文字列または挿入順保持の `Map<String, String>`」で持ち、`resolveDisplayName(String langCode)` メソッドを追加する
- 単体テスト: 文字列 `name`、オブジェクト `name` の選択順（一致 → `en_us` → 先頭 → ID）、大文字小文字の正規化、空・非文字列値の無視、256 文字上限、G2 の上書き保存でオブジェクト `name` が保たれること

---

## 1.1.0 の機能へ 1.2.0 で足す変更の一覧

1.1.0 の機能（`docs/FEATURE_SPEC_1.1.0.md`）は 1.2.0 の機能が無くても完結するよう作られている。1.2.0 の各機能を実装するときに 1.1.0 の機能側へ足す変更をここに集約する（タグ対応の詳細は F8b の「1.1.0 の機能へのタグの対応（1.2.0）」も参照）。

### F13 の 1.2.0 追加分（`toptag`）

- コマンド追加（F8b と同時。OP 不要、権限は F13 と同じく `allowLookup` と `isPrivileged`（F43 の `itemmilestonelog.viewhidden` を含む）に従う）:
  - `/milestonelog toptag <tag> [<count>]`: `top <item> [<count>]` のタグターゲット版（F8b と同じく `<tag>` は `#` を付けずに書く。`<count>` は省略時 1、範囲 1〜1,000,000）
- 補完: ワールドの誰かが記録しているタグのキー。F6 と同じく、使えないソースには出さない
- 順位付け・表示形式・F14 非公開の扱いは F13 の `top <item>` と同じ（表示名は F8b、ホバーは F12、プレイ時間・Day 表記は F48）

### F29 の 1.2.0 追加分（`help` の 1.2.0 コマンド表）

1.2.0 で足すサブコマンド6件を、各機能の実装と同時に F29 の定数表と lang キー（`message.itemmilestonelog.help.<name>`）に追加する。F43 の権限ノードは Brigadier の `CommandNode#canUse` により自動で従う。

| name | 版 | プレイヤー専用 | en | ja |
|---|---|---|---|---|
| info | 1.2.0 | ○ | Show your status, world first and rank for one item | アイテム1つの自分の状態・世界初・順位を表示 |
| addtag | 1.2.0 | ○ | Track an item tag (optionally a quantity) | アイテムタグを目標に追加（個数も指定できる） |
| removetag | 1.2.0 | ○ | Stop tracking an item tag | タグの目標を外す |
| resettag | 1.2.0 | ○ | Clear one of your tag records | タグの記録を1件消す |
| toptag | 1.2.0 | | Show who recorded a tag target fastest | タグの目標を早く記録した順 |
| renameitem | 1.2.0 | | Move records from an old item ID to a new one (admin) | 古いアイテム ID の記録を新しい ID に付け替える（管理者） |

### F49 の 1.2.0 追加分（`discordtest worldfirst`・権限ノード・F53 対象外）

- F46 以降: `/milestonelog discordtest worldfirst` で世界初用の Webhook（`discordWorldFirstWebhookUrl`）を試す（動作・事前チェック・結果表示は `discordtest` と同じ）
- F43 以降: 権限ノード `itemmilestonelog.discordtest` を追加する
- F53 以降: テスト送信には `discordSilentRecords` のフラグ（`flags: 4096`）を付けない

### F50 の 1.2.0 追加分（タグターゲットへの対応）

- 1. ゲームモード切替時の通知（F8b 以降）: 未記録のタグターゲットに属するアイテムも該当に含める（アイテム ID 単位で重複なく数える）
- 2. `targets` の行の末尾（F8b 以降）: タグターゲットの場合、そのタグに属するアイテムのうち1つ以上が `carriedFromCreative` に含まれていれば同じ補足（`target_creative_carried`）を付ける
- 実装メモ: 純粋関数の単体テストに、タグターゲットを含むケースを追加する

### その他の 1.1.0 機能への 1.2.0 変更一覧（G・T・F1〜F47）

| 1.1.0 の機能 | 1.2.0 で足す内容 |
|---|---|
| **G1** | F43 で専用サーバーでのカスタムプリセット保存・削除の判定に権限ノード `itemmilestonelog.presets.manage` を足す |
| **G2** | F54 で既存のカスタムプリセットを上書き保存するとき、既存ファイルに有効なオブジェクト形式の `name` があればそのまま引き継いで書き出す |
| **T** | `presetsEnabled` = false のとき F21 のデータパックプリセットも使えない。`exportImportEnabled` = false は F10 の自動バックアップに影響しない。`worldFirstEnabled` = false のとき F2 の `world_first: true` 条件は一致せず、F7 の `%worldfirst%` は常に `false`（既存の世界初は F17 の件数・F25 の印に継続して反映） |
| **F1** | F8b のタグターゲットの所持数合算による進捗判定、F12 のホバー |
| **F3** | F8b のタグターゲットの表示名、F43 の `exporthtmlall` 権限ノード `itemmilestonelog.export.all` |
| **F4** | F8b のタグターゲットの表示名、F53 の世界初を含まない通常記録 Embed への `"flags": 4096` 付与 |
| **F5** | F8b の判定対象キー R(S) でタグは存在して空でないものを数える。F21 のデータパックプリセットを `Presets.all()` に含める。F54 の多言語 `name` を使う |
| **F6** | F12 のホバー、F43 の権限ノード `itemmilestonelog.viewhidden`、F48 のプレイ時間・Day 表記 |
| **F11** | F9 のダイジェスト、F48 の記録整形、F51 の `info`、F52 のログイン時世界初サマリーでも同じ `displayTimeZone` を使う |
| **F14** | 隠している間は `isPrivileged`（F43 の `itemmilestonelog.viewhidden` を含む）を満たさない他プレイヤーに対し、F51 の `info` の人数と順位にも出ない。F17 のスコアボードは隠していても対象外（従来通り書き込む） |
| **F16** | F8b の R(S) から除く対象に空のタグを含める。F12 のホバー。F21 のデータパックプリセットと F54 の多言語 `name` に対応 |
| **F18** | F9 用の lang キー2件を追加: `discord.itemmilestonelog.digest.title`（%s = 日数: en "Milestones in the last %s day(s)" / ja 「直近 %s 日間のマイルストーン」）、`discord.itemmilestonelog.digest.line`（%s = 名前、%s = アイテム、%s = プレイ時間: en "%s — %s (%s)" / ja 「%s — %s（%s）」、世界初の行は先頭に `★ `）。F8b のタグ表示名に対応。F48 導入後も Discord のプレイ時間と Day は英語表記のまま維持 |
| **F19** | F21 のデータパックプリセット、F54 の多言語 `name`（`discordLanguage` で解決）。F46 の世界初 Webhook には送らず `discordWebhookUrl` に送る。F53 の `"flags": 4096` は付けない |
| **F22** | F8a/F8b のタグキーを正規化関数経由で読み込む。完了時に F17 のスコアを書き込む（F7 は動かさない）。F43 の権限ノード `itemmilestonelog.import.all`（他人のファイルを読み込む `import` にも適用） |
| **F23** | F8b の `resettag` を取り消しの対象に加える（`reset` と同じ）。取り消し時に F17 のスコアを書き込む（F2 / F7 は動かさない）。管理者のリセットの復旧は F10 のバックアップで行う |
| **F26** | F8b のタグターゲットの表示名、F43 の `exportcsvall` 権限ノード `itemmilestonelog.export.all` |
| **F27** | 同じ `ServerTickEvent.Post` ハンドラ内で F27 の送信を F7 のコマンド実行より先に行う。F2 のトリガー・F7 のコマンド・F17 のスコアはまとめない（F7 は tick 末に実行）。F8b のタグ表示名、F12 のホバー、F48 のプレイ時間・Day 表記を適用。F46 の送り先ごとに分けてしきい値（3件）を適用。F53 で世界初を1件も含まないまとめに `"flags": 4096` を付ける |
| **F28** | F8b で `presetremove` 時にプリセットのタグキーも正規化関数経由で照合して外す |
| **F30** | F8b のタグ表示名、F12 のホバー、F48 のプレイ時間・Day 表記 |
| **F31** | F8a/F8b のタグの世界初の読み込みに対応。`importall` での世界初読み込み時に F17 の世界初の件数を書き込む（F2 は動かさない） |
| **F32** | F8a/F8b で `addTarget` / `removeTarget` / `recordNow` がタグキーを受け付ける（`addTarget` は存在して空でないタグに限る）。`recordNow` の通常記録経路から F2 のトリガー・F7 のコマンド・F17 のスコアを実行する |
| **F33** | `grant` で F2 のトリガーと F17 のスコアを実行する（F7 は動かさない）。F12 のホバー。F43 の権限ノード `itemmilestonelog.admin` |
| **F35** | F46 で2つの Webhook URL ごとに独立して 429・404・401 の制御を行う |
| **F36** | F8b で走査ステップ2にそのアイテムを含むタグの未記録ターゲットも含める |
| **F37** | F10 のバックアップは書かない（消すのは記録・ターゲット・設定の無いデータだけで、失われるのは最後の名前・ヒントの表示済み・`lastSeen` だけのため）。消したプレイヤーの F17 スコアを `purge` と同じく消す。F43 の権限ノード `itemmilestonelog.prunedata`。保存する `lastSeen` は F52 でも参照する |
| **F38** | F8b のタグ表示名、F12 のホバー、F43 の権限ノード `itemmilestonelog.viewhidden` |
| **F39** | F46 の `discordWorldFirstWebhookUrl` でも同じ `env:<変数名>` 記法を受け付ける。F44 の設定画面には Webhook URL を出さない |
| **F40** | F21 のデータパックプリセットの ID も使える（ログインの時点では読み込み済み） |
| **F45** | 外している人の分は F9 のダイジェストの行と F46 の世界初 Webhook にも送らない。F17 のスコアは対象外 |
| **F47** | F9 のダイジェストの世界初の行でも規則 1 と 3 で名前を出す（「旧:」は付けない。Discord の行を短く保つため）。F51 の `info` と F52 のログイン時世界初サマリーでも同じ名前規則を使う |

---

## 3. テスト・検証（1.2.0 分）

1.1.0 で導入済みのテスト基盤（§3.1 の参加互換 CI、§3.2 の同一ファイル検査・`common/java` テスト、§3.3 の GameTest 基盤）を前提として、1.2.0 では次を行う。

- 追加・変更した純粋ロジック（パース、検証、置換、整形、抽出）には単体テストを追加する。特に F8a の TargetKey パースは必須
- 〔X18〕次の純粋関数は必ず単体テストする（1.2.0 分）:
  - F8a の TargetKey パース（有効・無効の全例）
  - F7 の置換と名前の検証
  - F9 の Markdown エスケープと切り詰め、期間と範囲の抽出
  - F10 の世代削除のファイル名一致と、書き出したファイルの読み戻し
  - §1.1 の 1.2.0 新フィールド（`lastDigestEpoch`）およびタグキーの往復（1.21.1 NBT と 26.x Codec）と、26.x で 1.21.1 形式を読めること
  - F17 の書き込むスコアの計算と objective の名前の検証
  - F21 の優先順位・namespace の衝突・無効な ID
  - F24 の付け替え（付け替え先との衝突の各規則、件数）
  - F25 の追加ペイロードの往復（知らないキーと不正な要素の無視、上限）
  - F42 のツールチップの行（目標・記録・複数キー・3行の上限）
  - F43 の判定（プレイヤー以外 × レベル、プレイヤー × レベル × ノード）とノードの表
  - F44 の静的な検査（設定画面に出す全設定の表示名・説明の lang キー）
  - F46 の送り先の決定（投稿の種類 × 2つの URL × `discordNotifyScope`）
  - F48 の en_us の結果が従来の文字列と一致すること、ja_jp の文面
  - F50 のクリエイティブ持ち出し該当アイテム種類数の算出（1.2.0 のタグを含むこと）
  - F51 の状態判定、`allowLookup` と F14 の除外、関連タグの抽出と 10 件の上限
  - F52 の留守中の世界初の抽出（`previousLastSeen == 0`、本人除外、境界値、降順・最大5件＋残り件数）
  - F53 の `flags: 4096` の付与条件（false で出力しない、通常記録・世界初なし F27 のみ付与、世界初・F19・F9・F49 には付けない）
  - F54 の多言語 `name` のパース・選択順・256 文字上限・G2 上書き保存での引き継ぎ
- lang キーの追加は ServerLangTest / LangFilesTest が通ること（en / ja のキー集合とプレースホルダの一致、lang フォルダのファイルと `BUNDLED_LANGUAGES` の一致）
- CI の既存ボットテスト（記録・コマンド・移行）が全バージョンで通ること
  - F8b を実装したら、タグターゲットでの記録（`addtag` でタグターゲットを登録し、タグに属するアイテムを拾って記録されること）がボットテストで1ケース確認できることが望ましい
  - 〔X18〕F2 を入れた状態で mineflayer（バニラクライアント）の参加テストが通ること（本 Mod で初めてのレジストリ登録のため完了条件とする）。F12 のホバー付きのメッセージ（記録の通知）を受け取っても切断されないことも、このテストで確認する（mineflayer はバニラのプロトコルで受け取るので、パケットの形が壊れていないことを確認できる。バニラの本物のクライアントでの表示は手動で確認する）
  - ボットテストに加えることが望ましいケース（1.2.0 分）: F17 のスコア（`/scoreboard players get`）、F21 のテスト用データパックのプリセットの適用
- 参加互換 CI（`docs/FEATURE_SPEC_1.1.0.md` §3.1）の 1.2.0 追加分:
  - 組み合わせに **1.1.0 との組み合わせ（両方向）** も足す（1.1.0 のリリースの jar を使う）
  - F25 の追加ペイロード: (a) 開発中の版のクライアント → 旧サーバー、(b) 旧クライアント → 開発中の版のサーバーのそれぞれで、切断されないことと、開発中の版の側で送った・受け取ったログ（相手が 1.1.0 以前なら送らないこと）を確かめる
- 手動確認:
  - 〔X2〕1.1.0 と 1.2.0 の組み合わせ（両方向）および 1.0.1 との組み合わせで、参加・GUI の見た目・F25 の追加ペイロードの有無を確かめる（参加と切断の有無は §3.1 の CI が確かめるので、手動では GUI の見た目を中心に確認する）
  - クライアントの新しい表示（F41 のキー割り当てとアクションバー、F42 のツールチップ、F44 の設定画面（4バージョンで開き保存できること・Webhook URL が出ないこと）、F48 の GUI の文面と切り詰め、F12 のホバー表示）は、バニラの本物のクライアントで手動で確かめる（各節の実装メモ）
  - F43: LuckPerms などの権限 Mod を入れた専用サーバーで、OP でないプレイヤーにノードを与えて実行できることを確かめる
- バージョン表記を 1.2.0 に上げる（gradle.properties 等、1.1.0 へ上げたときと同じ箇所）

## 4. ドキュメントとリリース（1.2.0 分）

1.2.0 のリリースで次を行う（公開ワークフローは 1.1.0 の §4.1 で導入済みのものを使う）。

- `CHANGELOG.md` に `## 1.2.0` の節を追加する。`check-release.sh` が `mod_version` と同じ節を要求するので、**mod_version を上げるコミットと同時に**入れる（無いと CI の release-check が失敗する）。§1.1 のダウングレードの注意（1.2.0 → 1.1.0 / 1.0.1 でタグデータと `lastDigestEpoch` が失われること）、既定で有効な表示の変更（F12 のホバー・F25 の印・F42 のツールチップ・F48 のプレイ時間の表記）、既定で有効な新機能（F10）を必ず書く
- `README.md` / `CURSEFORGE.md`: 新しいコマンド・設定（§1.2 のすべての設定を README の Configuration の一覧に足す）と、次の注意:
  - F2 のデータパック導入直後の advancement チャット通知とリセットで取り消さないこと、F7 のセキュリティ上の注意と `function` の例、F8 のタグの書き方・API での `isTag()` 確認・1.0.1 / 1.1.0 のクライアントでの見え方、F9 の既知の制限（送信時刻更新・429 非再送・import 記録の扱い）、F10 の復元手順、F17 の objective の名前の選び方と設定を空に戻しても消えないこと、F21 のフォルダの場所・利点・`preset reload` で読み直されないこと、F24 で Config やプリセットのファイルは書き換えないこと、F25 の ★ の意味、F41 のキーの割り当て方、F42 が 1.0.1 / 1.1.0 のサーバーでは GUI を開くまで出ないことと `itemTooltipStatus`、F43 のノードの一覧（F49 の `discordtest` を含む）・`permissionHandler` の設定（26.3 のファイル場所は実装時に確認）・OP から権限を取り上げられないこと・補完の反映、F44 の設定画面がその PC の設定であること（サーバーには効かない）と Webhook の URL が出ないこと、F46 の送り分けと F49 の `discordtest worldfirst`、F48 で Discord・ログ・書き出しは英語の書式のままであること、F51 の `info` の使い方、F52 の `loginWorldFirstSummary`（クラッシュ時に前回のログイン以降が重複して届き得ること）、F53 の `discordSilentRecords`、F54 の多言語 `name` の書き方と 1.0.1 / 1.1.0 でのフォールバック
- `docs/updates.json`: 1.2.0 の項目と promos は **CurseForge / Modrinth で公開するときのコミットで**更新する
- `GEMINI.md`: 開発バージョンを 1.2.0 に合わせ、`docs/` の一覧を更新する
- `docs/VERSION_NOTES.md`: §1.1 の新フィールド（`lastDigestEpoch` とタグキーの保存形式）を「保存データの構造」に追記する
