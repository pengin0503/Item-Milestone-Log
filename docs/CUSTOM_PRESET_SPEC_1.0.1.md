# Item Milestone Log — カスタムプリセット実装計画（v1.0.1）

この文書は、AI（または開発者）がこの文書だけを読んで実装を完遂できるように書かれた自己完結の実装計画である。対象リポジトリ: pengin0503/Item-Milestone-Log

この計画は、オーナーと別 AI（Claude）とのセッションで途中まで設計され、オーナーの選択によって以下が決定済みの状態から引き継ぐ。**決定済み事項は再検討しないこと。**

## 0. 決定済み事項（前提）

1. プリセットを自作できるようにする。方式は **両方**:
   - (a) config フォルダに置く **JSON ファイル**での定義
   - (b) **ゲーム内での保存**（自分の現在の目標をプリセットとして保存）
2. ゲーム内で保存したプリセットの保存先は **config フォルダ（全ワールド共通）**。手書き JSON と同じ場所に保存し、他のワールドでも使える。**専用サーバーでは OP（権限レベル2）だけが保存・削除できる**
3. プリセットが3つ以上になるとメイン画面下段に並べ切れないため、GUI は **「プリセット...」ボタンからプリセット一覧画面を開く形**に変える
4. 本計画は `docs/FEATURE_SPEC_1.0.1.md`（F1/F3〜F7）と合わせて **バージョン 1.0.1** の範囲である。同文書の共通制約（既存コマンド不変・SavedData 後方互換・バニラクライアント参加可・en/ja 両対応・全4バージョン同一機能）は本計画にも適用する。ただし同文書の「GUI デザイン変更禁止」については、**上記3の一覧画面化だけがオーナー承認済みの例外**であり、それ以外のレイアウト変更はしない
5. ボタン幅の修正（ラベルの実測幅で1行/2行を切り替える）は PR #23 でマージ済み。本計画はその後の状態を前提とする

## 1. 現在の状態（実装の出発点）

- プリセットは `Presets.java` の固定2種のみ: `atm_star`、`vanilla_endgame`（`PRESETS: Map<String, List<String>>`、`names()` がソート済み名前一覧を返す）
- 適用はコマンド `/milestonelog preset <name>` と GUI の `MilestoneActionPayload.Action.APPLY_PRESET`（引数=プリセット名）の両方から行われ、存在しないアイテムはスキップして件数を報告する既存処理がある
- `SyncMilestonesPayload` は `presetNames: List<String>` を持ち、GUI（`MilestoneScreen`）は受け取った名前ごとにボタンを動的生成している。組み込みプリセットの表示名はクライアント側で lang キー `gui.itemmilestonelog.preset.<name>` を引いて解決している
- `MilestoneActionPayload.Action` の既存値: ADD_TARGET / ADD_HAND / REMOVE_TARGET / APPLY_PRESET / RESET_RECORD / RESET_ALL（引数は文字列1つ、最大256文字）

## 2. プリセットのモデルと保存場所

### 2.1 プリセットの構成要素
- `id`: 識別子。組み込みは `atm_star` 等。ファイルプリセットはファイル名（拡張子なし）
- `displayName`: 表示名。組み込みは lang キーで解決、ファイルプリセットは JSON の `name`（無ければ id）
- `items`: アイテム ID のリスト
- `builtin`: 組み込みかどうか（削除不可・上書き不可の判定用）

### 2.2 ファイルプリセットの置き場所と形式
- 場所: `config/itemmilestonelog/presets/<id>.json`（`FMLPaths.CONFIGDIR` 配下。サーバー側のみで完結し、クライアントにはファイルを持たせない）
- フォルダが無ければサーバー起動時に作成する。サンプルファイルは自動生成しない（書き方は README に記載）
- JSON 形式（Gson 使用、追加依存なし）:

```json
{
  "name": "My Pack Goals",
  "items": [
    "minecraft:diamond",
    "allthetweaks:atm_star"
  ]
}
```

- `name` は任意（UTF-8、表示名）。`items` が必須

### 2.3 読み込みルール
- 読み込みタイミング: サーバー起動時、および再読み込みコマンド実行時（§3）
- バリデーション:
  - JSON として壊れているファイルは、ファイル名を出して error ログを残し、そのファイルだけスキップする（サーバー起動は止めない）
  - `items` 内の ID は形式（`ResourceLocation` として解釈可能か）だけを検証する。**その Mod が入っていない等の理由でレジストリに存在しない ID は落とさない**（適用時に既存処理がスキップする。Mod パック用プリセットが任意 Mod のアイテムを含むため）
  - 重複 ID は除去する。有効なアイテムが0件のファイルはスキップして warn ログ
  - 組み込みと同名 id のファイルは無視して warn ログ（組み込みが優先）
- プリセットは SavedData に入れない（config 側の情報であり、ワールドとは独立）。このためワールドを別サーバーへ移してもプリセットは付いてこない、という仕様上のトレードオフを README に明記する

## 3. コマンド

既存の `/milestonelog preset <name>`（適用、OP 不要）はそのまま維持し、カスタムプリセットにも使えるようにする。追加は次の通り:

| コマンド | 権限 | 動作 |
|---|---|---|
| `/milestonelog preset list` | 不要 | 全プリセット（組み込み＋ファイル）を表示名・件数・種別つきでチャットに一覧表示 |
| `/milestonelog preset save <name>` | 下記参照 | 実行者の現在の個人目標（SavedData の targets。デフォルト目標は含まない）をプリセットとして JSON に保存 |
| `/milestonelog preset delete <name>` | 下記参照 | ファイルプリセットを削除（組み込みは削除不可でエラー） |
| `/milestonelog preset reload` | 下記参照 | presets フォルダを再読み込みし、オンライン中の GUI 利用者へ再同期する |

- 権限ルール（決定済み事項2）: **専用サーバーでは権限レベル2必須**。シングルプレイ（統合サーバー）では常に許可
- `<name>`（save 時）は id として使うため、使用可能文字は英小文字・数字・`_`・`-`、1〜32文字。不正な名前はエラーにする
- save の上書き: 同名のファイルプリセットが既にある場合は上書きし、その旨をフィードバックに含める。組み込みと同名は拒否
- save 時、個人目標が0件ならエラーにする
- 実装上の注意: `preset` の直下にリテラル（list / save / delete / reload）と名前引数が同居する。Brigadier ではリテラルを優先させ、「list」という名前のプリセットは裸の形式では適用できないことを許容し、README に注記する

## 4. GUI の変更

### 4.1 メイン画面（MilestoneScreen）
- 下段のプリセットボタンの動的生成をやめ、**「プリセット...」ボタン1つ**に置き換える（追加/手持ちを追加の行構成は PR #23 の幅判定ロジックをそのまま使う）
- この変更により、プリセット数によらずレイアウトは固定になる

### 4.2 プリセット一覧画面（新規: PresetListScreen）
既存画面（ItemPickerScreen 等）と同じバニラ標準部品・見た目で作る:
- 一覧の各行: 表示名、アイテム件数、種別（組み込み/ファイル）、ボタン:
  - **適用**: 既存の APPLY_PRESET アクションを送信し、メイン画面に戻る（結果フィードバックはメイン画面の既存表示に出る）
  - **削除**: ファイルプリセットのみ、かつ管理権限がある場合のみ表示。押すと確認画面（ConfirmResetScreen と同型の確認ダイアログ）を経て削除
- **「現在の目標をプリセットとして保存」ボタン**: 管理権限がある場合のみ表示。押すと名前入力画面（EditBox 1つ＋保存/キャンセル。ItemPickerScreen の検索ボックス実装を流用）へ。保存すると一覧に戻り、結果がフィードバック表示される
- **戻る**ボタンでメイン画面へ戻る
- ライブ同期: 一覧画面を開いている間に同期パケットが来たら、ItemPickerScreen と同様に内容を更新する（`ClientMilestoneHandler` の分岐に追加）

### 4.3 ネットワークの拡張
- `SyncMilestonesPayload`: `presetNames: List<String>` を `presets: List<PresetEntry>` に置き換え、`canManagePresets: boolean` を追加する
  - `PresetEntry`（新規 record）: `id`、`displayName`（ファイルプリセット用のリテラル文字列。組み込みの場合は空文字でよい）、`itemCount`、`builtin`
  - 表示名の解決はクライアント側: 組み込みは従来通り lang キー `gui.itemmilestonelog.preset.<id>`、ファイルプリセットは `displayName`（空なら id）
  - `canManagePresets` はサーバーがプレイヤーごとに判定する（専用サーバーなら権限レベル2、統合サーバーなら true）
- `MilestoneActionPayload.Action` に `SAVE_PRESET`（引数=名前）と `DELETE_PRESET`（引数=id）を追加する
- **サーバー側の検証（必須）**: SAVE_PRESET / DELETE_PRESET を受けたら、権限をサーバー側で再判定する（クライアントの表示制御を信用しない）。保存するアイテム一覧は送信者の SavedData からサーバー側で読む（クライアントからアイテム一覧を送らせない）。名前は §3 のルールで再検証する
- ペイロードは従来通り optional 登録とし、バニラクライアント参加可を維持する

## 5. バージョン別の注意

- 全4バージョン（1.21.1 / 26.1.2 / 26.2 / 26.3）に同一実装を行う
- 26.3 の名前入力 EditBox は、ItemPickerScreen の 26.3 実装（SDL3 / テキスト入力フォーカス処理）に倣う
- config フォルダの取得方法など、バージョン間で API が異なる箇所は既存コードの書き方に合わせる

## 6. ドキュメントと lang

- `README.md`: カスタムプリセットの節を追加（JSON の置き場所と書式例、ゲーム内保存の方法、権限ルール、ワールドに付いてこない旨）
- `CHANGELOG.md`: 1.0.1 の節に追記（`docs/FEATURE_SPEC_1.0.1.md` の項目と同じ節にまとめる）
- `CURSEFORGE.md`: 説明文にカスタムプリセット対応の1行を追記（英語＋日本語）
- lang（en_us / ja_jp）: 一覧画面タイトル、保存・削除・適用ボタン、種別表示、各種フィードバック/エラーメッセージのキーを追加

## 7. テストと完了条件

既存 CI のボットテストに追加する:
- 起動前に presets フォルダへ JSON を置いた状態でサーバーを起動し、`preset list` に含まれ、`preset <name>` で目標が追加されること
- OP のボットが `preset save` を実行するとファイルが作られ、内容が実行者の目標と一致すること。`preset delete` で消えること
- 壊れた JSON ファイルがあってもサーバーが正常に起動すること
- 組み込みと同名 id のファイルが無視されること

完了条件: GitHub Actions で全4バージョンのビルドとサーバーテストが成功すること。既存テストと既存のプリセット適用動作を壊さないこと。

## 付録: カスタムプリセット用 JSON Schema（v1.4.0 / N140）

v1.4.0 以降、カスタムプリセット（`config/itemmilestonelog/presets/<id>.json` と
`data/<namespace>/itemmilestonelog/presets/<id>.json`）の公式 JSON Schema を
`docs/preset-schema.json` に同梱しています（JSON Schema draft 2020-12、外部 `$ref` なしの
単一ファイル）。jar には含まれません。

エディタの補完・検証を使うには、プリセット JSON のルートに `$schema` キーを追加します。
値はスキーマファイルへの相対パスまたは URL です（例: スキーマをプリセットと同じフォルダに
置いた場合は `"./preset-schema.json"`、リポジトリの `docs/` を参照する場合は
`"../../docs/preset-schema.json"`）。

```json
{
  "$schema": "./preset-schema.json",
  "name": "My Pack Goals",
  "items": ["minecraft:diamond"]
}
```

`$schema` は mod のパーサーでは許可済みのキーとして扱われ、未知フィールドの警告対象には
なりません（mod の動作には影響しません）。スキーマは全 G-B フィールドを網羅し、ルート・
items のオブジェクト要素・groups の各要素で `additionalProperties: false` としているため、
タイプミスしたフィールド名もエディタ上で検出できます。プリセット ID は JSON 内ではなく
ファイル名で決まるため、スキーマにも ID フィールドはありません。
