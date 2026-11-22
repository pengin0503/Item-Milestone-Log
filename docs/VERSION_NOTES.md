# Multi-Version Porting & Breaking Changes Notes

Item Milestone Log の複数バージョン対応（`1.21.1` / `26.1.2` / `26.2` / `26.3`）で対応した Minecraft / NeoForge の破壊的変更と、今後の機能追加・新バージョン移植時の注意点をまとめた開発者向けメモです。

---

## 対応バージョン一覧

| Minecraft | NeoForge 下限（`neo_version`） | NeoForge 最新（`neo_version_latest`） | Java | ModDevGradle | Gradle | `neoforge.mods.toml` |
|---|---|---|---|---|---|---|
| `1.21.1` | `21.1.1` | `21.1.267` | Java 21 | `2.0.148` | `9.2.1` | `modLoader` / `loaderVersion` 必須・Parchment あり |
| `26.1.2` | `26.1.2.71` | `26.1.2.129` | Java 25 | `2.0.148` | `9.2.1` | `modLoader` / `loaderVersion` 不要・非難読化（Parchment 不要） |
| `26.2` | `26.2.0.57` | `26.2.0.95` | Java 25 | `2.0.148` | `9.2.1` | 同上 |
| `26.3` | `26.3.0.76` | `26.3.0.115` | Java 25 | `2.0.148` | `9.2.1` | 同上 |

翻訳ファイルとロゴ（`logo.png`）は `common/resources/` に集約しており、全バージョンが共通参照します。

### NeoForge のバージョン方針
- `neo_version` は**コンパイルに使う版であり、同時に `neo_version_range`（mods.toml の下限）**です。リリース用jarは下限でコンパイルするので、使っているAPIが下限に存在することがビルドで保証されます。下限を上げると、その版より古いNeoForgeを使うModパック（例: ATM10 8.4 は 21.1.265）で読み込めなくなるため、必要なAPIが増えたときだけ上げてください。
- `neo_version_latest` は CI で確認する最新版です。CI は「最新版でコンパイル＋単体テスト」と「下限でコンパイルしたリリース用jarを最新版の本番サーバーで起動」の両方を行います。新しい NeoForge ビルドが出たら `neo_version_latest` を更新して CI を通してください。
- ローカルで別の版を試すときは `-Pneo_version=<version>` を付けます。

### 保存データの場所
| Minecraft | ファイル | 取得方法 |
|---|---|---|
| `1.21.1` | `<world>/data/itemmilestonelog_data.dat` | `server.overworld().getDataStorage()`（1.21.1 のオーバーワールドのデータは `<world>/data/`） |
| `26.1.2`+ | `<world>/data/itemmilestonelog/milestones.dat` | `server.getDataStorage()` + ID `itemmilestonelog:milestones` |

26.1 から SavedData の ID は `Identifier` になり、ファイルは `data/<namespace>/<path>.dat` に置かれます。ワールド全体のデータは次元ではなく `MinecraftServer#getDataStorage()` に置きます。

26.x の `MilestoneData.get` は、新しい場所にデータがまだないとき `<world>/data/itemmilestonelog_data.dat`（1.21.1 の形式: 圧縮NBTの `"data"` タグに同じ構造）を読み込みます（issue #13）。旧ファイルは変更・削除しません。CI の移行テスト（下記）で、1.21.1 のサーバーで作ったワールドを 26.1.2 のサーバーで開き、記録が引き継がれることを確認しています。

### 保存データの構造（全バージョン共通）
`"players" -> "<uuid>" -> { "targets": [...], "records": { "<itemId>": <ticks> }, "carriedFromCreative": [...] }`

`carriedFromCreative` は、クリエイティブ／スペクテイターから戻ったときに持っていたアイテムのIDで、空のときは書き出しません（issue #16、下記）。

### クリエイティブ／スペクテイター（`ignoreCreativeAndSpectator`、既定 true）
- この2つのモードでは、スキャン・拾得・クラフト・精錬のどれでも記録しません。
- サバイバル／アドベンチャーに戻るとき（`PlayerEvent.PlayerChangeGameModeEvent`、LOWEST）に持っているアイテムのIDを `carriedFromCreative` に保存し、それがインベントリにある間は記録しません。スキャンでインベントリから消えたことを確認したら外し、次に手に入れたときに記録します。
- 判定はアイテムID単位です（スタックごとの区別はしません）。チェストに預けてから取り出した場合などは、その時点で記録されます。

### GUIのキー
既定キーはありません（`InputConstants.UNKNOWN`）。`M` は ATM10 に入っている FTB Chunks の全画面マップの既定キーで、NeoForge では同じキーの割り当てが両方とも反応するためです（issue #17）。

### 設定ファイル名
| Minecraft | 種別 | ファイル |
|---|---|---|
| `1.21.1` / `26.1.2` / `26.2` | `ModConfig.Type.COMMON` | `config/itemmilestonelog-common.toml` |
| `26.3` | `ModConfig.Type.LOCAL` | `config/itemmilestonelog-local.toml`（CI の実サーバーで確認） |

---

## 1.21.1 → 26.1.2 の破壊的変更

### ビルド・メタデータ
- **Java 25 必須**: `java.toolchain.languageVersion = JavaLanguageVersion.of(25)`。
- **非難読化（Deobfuscated）**: Minecraft 26.1 以降は公式 Mojang マッピング名でそのまま配布されるため、`build.gradle` の `parchment` 設定は不要。
- **`neoforge.mods.toml`**: `modLoader="javafml"` / `loaderVersion="..."` が不要になり削除。
- **DataGen タスク**: `runs.data` の `data()` → `clientData()`。

### ID・レジストリ・コマンド
| 項目 | 1.21.1 | 26.1.2+ | 該当クラス |
|---|---|---|---|
| リソースID型 | `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` | `Config`, `MilestoneData`, `MilestoneService`, `network/*`, `client/*` |
| コマンドID引数 | `ResourceLocationArgument` | `IdentifierArgument` | `MilestoneCommands` |
| レジストリ取得 | `BuiltInRegistries.ITEM.get(rl)` | `BuiltInRegistries.ITEM.getValue(id)` | `MilestoneService`, `MilestoneScreen` |
| コマンドアイテム引数 | `ItemArgument.getItem(ctx, "item").getItem()` | `ItemArgument.getItem(ctx, "item").item().value()` | `MilestoneCommands` |
| アイテム表示名 | `item.getDescription()` | `item.getName(ItemStack)` | `MilestoneService`, `MilestoneScreen`, `ItemPickerScreen` |

### プレイヤー・サーバー・SavedData
| 項目 | 1.21.1 | 26.1.2+ | 該当クラス |
|---|---|---|---|
| サーバー参照 | `player.server` | `player.level().getServer()` | `Tracker`, `MilestoneService`, `MilestoneCommands` |
| プレイヤー名（ログ） | `player.getGameProfile().getName()` | `player.getGameProfile().name()` | `Tracker` |
| SavedData 登録・永続化 | `SavedData.Factory` + `save(CompoundTag, Provider)` / `load(...)` | `SavedDataType<T>(Identifier, Supplier<T>, Codec<T>)` | `MilestoneData` |

> **互換性維持の注意点（`MilestoneData`）**
> 26.1.2 以降では `RecordCodecBuilder` ベースの `Codec<MilestoneData>` に移行しています。保存される NBT の中身は 1.21.1 と同じですが、**ファイルの場所は異なります**（上の「保存データの場所」を参照）。1.21.1 のファイルは `MilestoneData.readLegacy` で読み込みます。公開後はこのスキーマと 26.x のID・場所を変えないでください。変える場合は旧データの読み込み処理が必要です。

### ネットワーク・クライアント通知
| 項目 | 1.21.1 | 26.1.2+ | 該当クラス |
|---|---|---|---|
| C2S パケット送信 | `PacketDistributor.sendToServer(...)` | `ClientPacketDistributor.sendToServer(...)`（`net.neoforged.neoforge.client.network`） | `ClientMilestoneHandler` |
| アクションバー通知 | `mc.player.displayClientMessage(msg, true)` | `mc.player.sendOverlayMessage(msg)` | `ClientMilestoneHandler` |

> **バニラクライアント互換性**
> `ModNetworking.registerPayloads` の `event.registrar(PROTOCOL_VERSION).optional()` は全バージョン共通で維持する必要があります。
> サーバーから送るメッセージは `ServerLang.tr(key, args...)`（`Component.translatableWithFallback` に jar 内 `en_us.json` の英語を付ける）を使ってください。`Component.translatable` だと、MOD未導入のクライアントには翻訳キーがそのまま表示されます。引数は `String` / `int` / `Component` にしてください（`long` は NBT の long として送られ、一部のクライアントでは表示されません）。

### GUI・描画・入力
| 項目 | 1.21.1 | 26.1.2+ | 該当クラス |
|---|---|---|---|
| 画面描画メソッド | `Screen.render(GuiGraphics, int, int, float)` | `Screen.extractRenderState(GuiGraphicsExtractor, int, int, float)` | `MilestoneScreen`, `ItemPickerScreen` |
| リスト行描画 | `Entry.render(GuiGraphics, index, top, left, width, height, ...)` | `Entry.extractContent(GuiGraphicsExtractor, mouseX, mouseY, hovered, partialTick)`（座標は `getContentX()` / `getContentY()` / `getContentWidth()` / `getContentHeight()` を使用） | `MilestoneScreen`, `ItemPickerScreen` |
| ウィジェット描画 | `button.render(guiGraphics, ...)` | `button.extractRenderState(graphics, ...)` | `MilestoneScreen` |
| テキスト・アイテム描画 | `drawString` / `drawCenteredString` / `renderItem` / `renderTooltip` | `text` / `centeredText` / `item` / `setTooltipForNextFrame` | `MilestoneScreen`, `ItemPickerScreen` |
| テキスト色指定 | RGB（例: `0xFFFFFF`）でも描画可能 | **ARGB（アルファ `0xFF` 必須、例: `0xFFFFFFFF`）**。上位バイトが `0x00` だと透明になる | `MilestoneScreen`, `ItemPickerScreen` |
| スクロール関連 | `getScrollAmount()` / `getScrollbarPosition()` | `scrollAmount()` / `scrollBarX()` | `MilestoneScreen`, `ItemPickerScreen` |
| クリックイベント | `mouseClicked(double x, double y, int button)` | `mouseClicked(MouseButtonEvent event, boolean isDoubleClick)`（`event.button()`） | `ItemPickerScreen` |
| キーカテゴリ | 文字列 `"key.categories.itemmilestonelog"` | `KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "general"))` + `event.registerCategory(KEY_CATEGORY)` | `ItemMilestoneLogClient` |
| キーカテゴリ翻訳キー | `key.categories.itemmilestonelog` | `key.category.itemmilestonelog.general`（両方 `common/resources` に定義済み） | `en_us.json`, `ja_jp.json` |

---

## 26.1.2 → 26.2 の破壊的変更

| 項目 | 26.1.2 | 26.2+ | 該当クラス |
|---|---|---|---|
| 現在表示中の画面取得 | `mc.screen` | `mc.gui.screen()` | `ItemMilestoneLogClient`, `ClientMilestoneHandler`, `MilestoneScreen` |
| 画面切替 | `mc.setScreen(screen)` | `mc.gui.setScreen(screen)` | `ClientMilestoneHandler`, `MilestoneScreen`, `ItemPickerScreen`, `ConfirmResetScreen` |

---

## 26.2 → 26.3 の破壊的変更

| 項目 | 26.2 | 26.3+ | 該当クラス |
|---|---|---|---|
| コンフィグ種別（FML 12） | `ModConfig.Type.COMMON` | `ModConfig.Type.LOCAL`（`SERVER` は `SYNCED` に改称。`LOCAL` はバニラクライアントへ同期しないため参加互換を維持できる） | `ItemMilestoneLog` |
| キー入力基盤（GLFW → SDL） | `InputConstants.Type.KEYSYM` + `org.lwjgl.glfw.GLFW.GLFW_KEY_*` | `InputConstants.Type.KEYBOARD` + `InputConstants.KEY_*`（`org.lwjgl.glfw` 依存なし）。既定キーなしの `InputConstants.UNKNOWN` は全バージョン共通 | `ItemMilestoneLogClient` |

---

## 今後のメンテナンス・新バージョン追加チェックリスト

1. **共通修正の反映**: 仕様変更やバグ修正を行う場合は `versions/1.21.1`・`26.1.2`・`26.2`・`26.3` の 4 つすべてに反映し、翻訳は `common/resources/assets/itemmilestonelog/lang/` を更新する。
2. **互換性チェック**:
   - `MilestoneData` の保存 NBT 構造を壊さないこと（`MilestoneDataTest` でラウンドトリップと、26.x では 1.21.1 形式のファイルの読み込みを検証）。
   - `ModNetworking` の `.optional()` を維持し、バニラクライアント参加互換を保つこと。
   - クライアント専用クラスは `client` パッケージ＋`@Mod(value = MOD_ID, dist = Dist.CLIENT)` に隔離し、専用サーバー起動時に読み込まれないこと。
   - 新しいメッセージキーは `en_us.json` と `ja_jp.json` の両方に追加すること（`LangFilesTest` / `ServerLangTest` が、キーとプレースホルダ数の一致、コード中のキーが `en_us.json` にあることを検証）。
3. **新バージョン（例: 26.4）を追加する場合**:
   - 直近バージョン（`versions/26.3`）をコピーして `versions/<new_mc>` を作成。
   - `gradle.properties` の `minecraft_version` / `minecraft_version_range` / `neo_version` / `neo_version_range` / `neo_version_latest` を更新。
   - `.github/workflows/main.yml` の `build` ジョブの `matrix.mc` と `matrix.include` にエントリを追加（mineflayer が対応していれば `bot: true`）。`server-test` は同じ matrix（YAML アンカー `&versions`）を使います。
   - 設定ファイル名や保存データの場所が変わる場合は `.github/scripts/server-lib.sh`（`use_server`）も更新。
   - `.github/scripts/check-release.sh` の `MC_VERSIONS` に追加。
   - ローカルで `./gradlew -p versions/<new_mc> build`（テスト含む）と GitHub Actions の全マトリックス成功を確認。

## CI（`.github/workflows/main.yml`）

ジョブの流れ: `build` → `server-test` と `migration`（並行）→ `release-check` → `release`（タグのみ）。

`build`（各MCバージョン × NeoForge（下限・最新）の8ジョブ）:

1. `./gradlew build`（そのNeoForgeでコンパイル＋単体テスト）。最新版のジョブは、その後リリース用jarを下限で作り直します。Gradle のバージョンは `gradle/wrapper/gradle-wrapper.properties` だけで決まります。Gradle のキャッシュは main への push だけが保存し、PR とタグは読むだけです。
   - NeoForm Runtime のキャッシュ（`~/.gradle/caches/neoformruntime`: 逆コンパイル・パッチ済みの Minecraft とゲームアセット、MC バージョンごとに約 0.8〜1.2 GB）は setup-gradle のキャッシュから外し、MC バージョンごとに1つの actions/cache エントリにしています。キーは `versions/<mc>/gradle.properties` と `versions/<mc>/*.gradle` のハッシュなので、保存されるのはそれらが変わった後の main への push だけです。書くのは両方の NeoForge でビルドする最新版のジョブで、下限のジョブは読むだけです。前のエントリから復元して保存するときは、`.github/scripts/prune-neoform-cache.sh` がそのビルドで使わなかった結果（古い NeoForge 用など）を消してから保存します。
2. `.github/scripts/verify-jar.sh`: jar に mods.toml・ロゴ・翻訳・主要クラスがあるか、mods.toml の値と依存範囲、`${...}` の展開漏れ、開発用ファイルの混入を確認。
3. 下限ジョブの jar を成果物 `itemmilestonelog-<mc>` としてアップロード（CurseForge 用、`server-test`・`migration`・リリースでも使用）。

`server-test`（同じ8ジョブ。`build` の後）: 下限でコンパイルしたリリース用jar（成果物 `itemmilestonelog-<mc>`）を、そのジョブのNeoForge（下限・最新）の本番サーバーで動かします。

- `.github/scripts/run-server-tests.sh`: NeoForge 公式インストーラーで本番の専用サーバーを入れ、jar を `mods/` に置いて起動し、`.github/integration/server-test.mjs` を実行。インストール結果（`build/server/<mc>-<neo>/libraries`）は MC と NeoForge のバージョンをキーに actions/cache で再利用し、導入済みならインストーラーは動きません。
  - 全バージョン: RCON でコマンド登録（`/milestonelog`・`/iml`・`/iml <サブコマンド>`）、設定ファイルが読まれたこと、ログにMOD由来のスタックトレース・リンクエラーがないこと。
  - 1.21.1 / 26.1.2: mineflayer（バニラプロトコル、MOD未導入扱い）が参加し、英語フォールバック付きメッセージ、各コマンド、拾得による即時記録、インベントリスキャンによる記録、プリセット、reset/resetall、クリエイティブで記録されないこと（持ち帰ったアイテムの扱いを含む）、サーバー再起動後のデータ保持を確認。mineflayer 4.39 は 26.2 / 26.3 に未対応のため、その2バージョンは RCON のみです。
  - テスト用サーバーのスキャン間隔は 240 tick（12秒、`server-lib.sh` の `SCAN_INTERVAL_TICKS`）。拾得の記録は4秒以内を求めるので、拾得フックとスキャンを区別できます。

並行するジョブとその後:

- `migration`（`.github/scripts/run-migration-test.sh`、`build` の後に `server-test` と並行）: 1.21.1 のサーバー（最新NeoForge）で記録を作り、そのワールドを 26.1.2 のサーバーで開いて、記録が引き継がれること、新しいファイルが書かれ旧ファイルが残ることを確認します。
- `release-check`（`.github/scripts/check-release.sh`、`build`・`server-test`・`migration` がすべて成功した後）: 4つのjarがそろっていること、全バージョンの `mod_version` が同じこと、`CHANGELOG.md` にその版の節があることを毎回確認し、リリースノートを作ります。
- `release`: `v<mod_version>` のタグを push したときだけ動き、4つのjarを添付した GitHub Release を作ります（`GITHUB_TOKEN` のみ使用）。

GUI（キー操作・画面）は自動テストの対象外です。
