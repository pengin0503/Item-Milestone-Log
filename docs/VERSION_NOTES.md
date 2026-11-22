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
> 26.1.2 以降では `RecordCodecBuilder` ベースの `Codec<MilestoneData>` に移行しています。保存される NBT の中身（`"players" -> "<uuid>" -> { "targets": [...], "records": { "<itemId>": <ticks> } }`）は 1.21.1 と同じですが、**ファイルの場所は異なります**（上の「保存データの場所」を参照）。そのため 1.21.1 のワールドを 26.x で開いても記録は引き継がれません（issue #13）。公開後はこのスキーマと 26.x のID・場所を変えないでください。変える場合は旧データの読み込み処理が必要です。

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
| キー入力基盤（GLFW → SDL） | `InputConstants.Type.KEYSYM` + `org.lwjgl.glfw.GLFW.GLFW_KEY_M` | `InputConstants.Type.KEYBOARD` + `InputConstants.KEY_M`（`org.lwjgl.glfw` 依存なし） | `ItemMilestoneLogClient` |

---

## 今後のメンテナンス・新バージョン追加チェックリスト

1. **共通修正の反映**: 仕様変更やバグ修正を行う場合は `versions/1.21.1`・`26.1.2`・`26.2`・`26.3` の 4 つすべてに反映し、翻訳は `common/resources/assets/itemmilestonelog/lang/` を更新する。
2. **互換性チェック**:
   - `MilestoneData` の保存 NBT 構造を壊さないこと（`MilestoneDataTest` でラウンドトリップを検証）。
   - `ModNetworking` の `.optional()` を維持し、バニラクライアント参加互換を保つこと。
   - クライアント専用クラスは `client` パッケージ＋`@Mod(value = MOD_ID, dist = Dist.CLIENT)` に隔離し、専用サーバー起動時に読み込まれないこと。
   - 新しいメッセージキーは `en_us.json` と `ja_jp.json` の両方に追加すること（`LangFilesTest` / `ServerLangTest` が、キーとプレースホルダ数の一致、コード中のキーが `en_us.json` にあることを検証）。
3. **新バージョン（例: 26.4）を追加する場合**:
   - 直近バージョン（`versions/26.3`）をコピーして `versions/<new_mc>` を作成。
   - `gradle.properties` の `minecraft_version` / `minecraft_version_range` / `neo_version` / `neo_version_range` / `neo_version_latest` を更新。
   - `.github/workflows/main.yml` の `matrix.mc` と `matrix.include` にエントリを追加（mineflayer が対応していれば `bot: true`）。
   - 設定ファイル名や保存データの場所が変わる場合は `.github/scripts/run-server-tests.sh` も更新。
   - ローカルで `./gradlew -p versions/<new_mc> build`（テスト含む）と GitHub Actions の全マトリックス成功を確認。

## CI（`.github/workflows/main.yml`）

各MCバージョン × NeoForge（下限・最新）の8ジョブで、次を行います。

1. `gradle build`（そのNeoForgeでコンパイル＋単体テスト）。最新版のジョブは、その後リリース用jarを下限で作り直します。
2. `.github/scripts/verify-jar.sh`: jar に mods.toml・ロゴ・翻訳・主要クラスがあるか、mods.toml の値と依存範囲、`${...}` の展開漏れ、開発用ファイルの混入を確認。
3. `.github/scripts/run-server-tests.sh`: NeoForge 公式インストーラーで本番の専用サーバーを入れ、jar を `mods/` に置いて起動し、`.github/integration/server-test.mjs` を実行。
   - 全バージョン: RCON でコマンド登録（`/milestonelog`・`/iml`・`/iml <サブコマンド>`）、設定ファイルが読まれたこと、ログにMOD由来のスタックトレース・リンクエラーがないこと。
   - 1.21.1 / 26.1.2: mineflayer（バニラプロトコル、MOD未導入扱い）が参加し、英語フォールバック付きメッセージ、各コマンド、拾得による即時記録、インベントリスキャンによる記録、プリセット、reset/resetall、サーバー再起動後のデータ保持を確認。mineflayer 4.39 は 26.2 / 26.3 に未対応のため、その2バージョンは RCON のみです。
4. 下限ジョブの jar を成果物 `itemmilestonelog-<mc>` としてアップロード（CurseForge 用）。

GUI（キー操作・画面）は自動テストの対象外です。
