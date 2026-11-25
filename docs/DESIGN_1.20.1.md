# Item Milestone Log 1.20.1 移植 詳細設計（ドラフト）

- 状態: **承認済み（2026-11-25、pen）**。§10 の決定: Q1=設定画面は省略、**Q2=版番号は 1.3.0 とし Forge 1.20.1 のみで出す（NeoForge 側の再リリースはしない。当初の 1.3.1 全系統同時案から変更）**、Q3=Forge 最小 47.3.0、Q4=GameTest はストレッチ、Q5=Fabric 需要ゲートは提案どおり。
- 基準: `main@087c299`（mod_version 1.3.0）。数値はこのツリーの grep 実測による。
- 位置づけ: `docs/FEATURE_SPEC_1.3.0.md` §11（「1.20.1 は 1.3.0 の次のマイルストーン。Forge 前提のローダー選定を含む設計を別途起こす」）を受けた設計書。
- 対象: Minecraft 1.20.1 / Forge 47.x（主）。Fabric 1.20.1 は §7 で判断材料のみ示す。

---

## 0. 結論サマリ

1. **Forge 1.20.1 は実現可能で、推奨する**。新プロジェクト `versions/1.20.1-forge/`（ForgeGradle 6 / JDK 17 / 公式マッピング + Parchment 2023.09.03）を `versions/1.21.1` のコピー＆改修で作る。`common/`（60 ファイル）はそのまま共有する。
2. 改修量の見積り（§4.3）: `versions/1.21.1` の 97 ファイルのうち **変更なし 約 46 / import 置換のみ 約 14 / 部分改修 約 23 / 書き換え 約 14**。書き換えはネットワーク（SimpleChannel 化）、エントリ／イベント配線、設定画面、トリガー、SavedData 取得に集中する。
3. Java 17 監査（§5）: grep の結果、**Java 18〜21 専用の API・構文は共有コードにも 1.21.1 ツリーにも見つからなかった**。CI で `--release 17` コンパイルをして確定させる。
4. 既存の McCompat はローダー差分の層まで届かない。1.20.1-forge ツリーの中に閉じた実装（`compat/v120_1`）を持たせ、NeoForge ツリーとの統一（N246 PlatformBridge）は**この移植ではやらない**（§4）。
5. データ互換: ワールドの 1.21→1.20.1 ダウングレードはバニラ自体が非対応なので、SavedData の移行は約束しない。NBT キー構成を変えないので、**バックアップの復元とエクスポート（CSV/HTML）はバージョンをまたいで互換**になる（§6）。
6. **Fabric 1.20.1 の判断: 「Forge の後で、需要ゲート付きで実施」**（§7）。共有できるのは `common/` だけで、版固有コードの約 7 割は再実装になる。Mixin が 4 箇所以上必要で、以降の機能追加と修正がすべて 3 ローダー分になる（1.4.0 のバックログは 244 件）。
7. 規模の見積り（§9）: Forge 移植の合計は **約 9〜13 人日相当（実装 6〜8 + 検証・CI・リリース 3〜5）**。

---

## 1. 前提・原則

| # | 原則 | 1.20.1 での具体化 |
|---|---|---|
| P1 | **サーバー側だけで成立し、バニラクライアントが参加できる**（最重要） | SimpleChannel を `NetworkRegistry.acceptMissingOr(PROTOCOL)` でクライアント・サーバー双方の受理条件に設定し、チャンネルを「任意」にする。送信前に `channel.isRemotePresent(connection)` で相手の有無を確認し、未導入のクライアントには送らない（1.21.1 の `registrar.optional()` と `ModNetworking` の送信ガードに相当）。`mods.toml` は `displayTest="IGNORE_ALL_VERSION"` を維持する |
| P2 | **GUI デザインは凍結** | 画面の構成、文言、色、配置は 1.21.1 と同一にする。API 差による描画呼び出しの書き換えは許容するが、見た目は変えない |
| P3 | **mod_id とパッケージは不変** | `itemmilestonelog` / `com.pengin0503.itemmilestonelog`。公開 API（`api/*`）のクラス名とメソッド署名も維持する（イベント基底クラスだけは Forge のものに変わる） |
| P4 | ゼロ依存 | Forge 以外の必須依存を追加しない（Cloth Config などは入れない） |
| P5 | データ形式不変 | SavedData の NBT キー構成、バックアップ形式、エクスポート形式を 1.3.x と同一にする |
| P6 | 機能パリティ | 1.3.0 の全機能を対象にする。落とす場合は §10 で個別に判断する（現状の候補は設定画面だけ） |

---

## 2. リポジトリ構成案

### 2.1 新規プロジェクト

```
versions/1.20.1-forge/
  settings.gradle        # pluginManagement に maven.minecraftforge.net、ForgeGradle [6.0,6.2)
  build.gradle           # net.minecraftforge.gradle、minecraft { mappings channel: 'parchment' ... }
  gradle.properties      # minecraft_version=1.20.1, forge_version=47.x, loader_version_range=[47,)
  src/main/java/...      # versions/1.21.1 からのコピー＆改修
  src/main/resources/pack.mcmeta          # pack_format 15（Forge 1.20.1 は自動生成しない前提）
  src/main/templates/META-INF/mods.toml   # neoforge.mods.toml を移植
  src/test/java/...
```

- `common/src/main/java`、`common/src/test/java`、`common/resources` は `versions/1.21.1/build.gradle` と同じく `srcDir('../../common/...')` で取り込む。
- Gradle は 8.x 系。ForgeGradle 6 の対応範囲に合わせ、ルートの wrapper とは別に**プロジェクト専用の wrapper を置く**（既存の `versions/*` も独立プロジェクトなので慣例と一致する）。
- **ジャーのマージはしない**。1.20.1-forge は単一バージョン・単一ローダーなので、`tools/merge-121x-jar.sh` などのマージ経路には載せない。

### 2.2 方式の選択: コピー＆改修 vs 共有版ソース

| 案 | 内容 | 評価 |
|---|---|---|
| **A. コピー＆改修（推奨）** | `versions/1.21.1/src` を丸ごとコピーし、Forge と 1.20.1 に合わせて改修する | リポジトリの既存慣例（バージョンごとの完全コピー + identical-files CI）と一致する。NeoForge 側のビルドとテストに一切影響しない |
| B. 共有版ソース | NeoForge/Forge 共通の版ソースを抽出し、ローダー差分を抽象化する | 実質的に N246（PlatformBridge）の先行実施になる。DESIGN_1.3.0 §0-2 の「N246 は単独実施」という決定と衝突し、レビュー単位が崩れる |

**A を推奨する。** 理由は次の 3 点。(1) import 置換だけで済むファイルが約 14 あり、コピーしても差分を読む負担が小さい。(2) 1.20.1 はコンポーネント以前の世代で、`compat/v121_1` 側の前提（`SavedData.Factory`、`Registries.TRIGGER_TYPE`、`StreamCodec`）がそもそも存在しない。(3) 失敗しても `versions/1.20.1-forge/` を消せば元に戻る。

### 2.3 identical-files CI での扱い

- `.github/scripts/check-identical-files.sh` の `ALL_VERSIONS=(1.21.1 26.1.2 26.2 26.3)` には **1.20.1-forge を追加しない**。
- 代わりに、配列 `IDENTICAL_FORGE_1201`（1.21.1 と 1.20.1-forge の間で同一であるべきファイル）を新設する。対象は §4.3 の「変更なし」のうち、`IDENTICAL_ALL_4` に含まれるものと同等の純ロジック系である（`DiscordNotifier.java`、`ItemInfoLogic.java`、`MilestoneRankings.java`、`WorldFirstNames.java`、`api/MilestoneRecord.java`、`api/TargetKey.java`、`network/TargetEntry.java`、`client/ActionRowLayout.java` と各テスト）。
- `IDENTICAL_ALL_4` に入っているファイルのうち 1.20.1-forge で必ず変わるものは、スクリプト冒頭の「Intentionally excluded files」コメントに理由付きで列挙する。該当は `api/MilestoneRecordedEvent.java` と `api/PresetCompletedEvent.java`（Event 基底クラス）、`MilestoneBackup.java`（`HolderLookup.Provider` 引数）、`MilestoneCommandRunner.java`（要確認）、`ScoreboardSync.java`（要確認）。

---

## 3. 差分棚卸し表

規模は S = 半日以内、M = 1〜2 日、L = 3 日以上。リスクは 低/中/高。

### 3.1 領域別

| 領域 | 現行（1.21.1） | Forge 1.20.1 での対応 | 規模 | リスク |
|---|---|---|---|---|
| Mod エントリ／バス | `ItemMilestoneLog.java`（`@Mod`、コンストラクタに `IEventBus`/`ModContainer` を注入、`NeoForge.EVENT_BUS`、`modContainer.registerConfig(COMMON, Config.SPEC)`）、`client/ItemMilestoneLogClient.java`（`@Mod(dist=CLIENT)`） | 引数なしコンストラクタ + `FMLJavaModLoadingContext.get().getModEventBus()`、`MinecraftForge.EVENT_BUS`、`ModLoadingContext.get().registerConfig(...)`。クライアントの初期化は `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` または `FMLEnvironment.dist` の分岐にする（Forge は `@Mod` の dist 指定で別クラスを読む方式を持たない） | M | 低 |
| イベント | `Tracker.java`（1,963 行）、`MilestoneCommands.java`、`MilestoneBackup.java`、`compat/v121_1/ReloadCompatImpl.java` ほか | §3.2 の対応表。主な差は Tick 系の Pre/Post 分割と `TickEvent.Phase` の違い、および拾得イベント | M | 中（拾得） |
| ネットワーク | `network/ModNetworking.java`（`RegisterPayloadHandlersEvent` / `PayloadRegistrar.optional()` / `IPayloadContext`、トークンバケット方式のレート制限）、`network/*Payload.java` 4 本（`CustomPacketPayload` + `StreamCodec`）、`compat/client/v121_1/NetworkCompatImpl.java`、`MilestoneService.java`（`PacketDistributor`） | `NetworkRegistry.newSimpleChannel(rl, () -> PROTOCOL, acceptMissingOr(PROTOCOL), acceptMissingOr(PROTOCOL))`。各ペイロードは `encode(FriendlyByteBuf)` / `static decode(FriendlyByteBuf)` / `handle(Supplier<NetworkEvent.Context>)`（`enqueueWork` + `setPacketHandled(true)`）にする。送信は `CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg)` / `CHANNEL.sendToServer(msg)`。**レート制限（`RATE_LIMITS`、`REFILL_INTERVAL_NANOS` など）は純ロジックなので、そのまま移植する**。送信前の `isRemotePresent` ガードを必須にする（P1） | L | 中 |
| 設定 | `Config.java`（387 行、`ModConfigSpec`）、`client/ItemMilestoneLogClient.java`（`ConfigurationScreen` + `IConfigScreenFactory`、`HIDDEN_CONFIG_FILTER`） | `ForgeConfigSpec`。Builder API は NeoForge 側がこれをフォークしたものなので、ほぼ import 置換で済む。`ModConfigEvent.Loading/Reloading` も同名。**Forge 1.20.1 には自動の `ConfigurationScreen` がない**ため、`ConfigScreenHandler.ConfigScreenFactory` で登録する画面を自作するか、画面を省略するか、を §10-Q1 で決める | M（省略）〜L（自作） | 中 |
| SavedData | `MilestoneData.java`（`save(CompoundTag, HolderLookup.Provider)`、`load(CompoundTag, HolderLookup.Provider)`）、`compat/v121_1/DataCompatImpl.java`（`SavedData.Factory` + `storage.computeIfAbsent(FACTORY, DATA_NAME)`） | 1.20.1 の取得は `storage.computeIfAbsent(MilestoneData::load, MilestoneData::new, DATA_NAME)`（`Function<CompoundTag,T>` と `Supplier<T>` を渡す形式）で、`save(CompoundTag)` に Provider 引数はない。`MilestoneBackup.java` の `toSavedDataFileTag(registries)` も Provider を外す。DataFixer 用の `DataFixTypes` は不要 | S〜M | 低 |
| NBT／アイテム | `compat/v121_1/NbtCompatImpl.java`、`MilestoneService.java`、`Tracker.java` | 1.21.1 の CompoundTag の getter は 1.20.1 と同じ非 Optional 形式なので、`NbtCompatImpl` はほぼ流用できる。**DataComponents の使用は 0 件**（`DataComponent`/`core.component`/`isSameItemSameComponents` を grep した結果、該当なし）。監査ポイントは次の 3 つ: ① `ItemStack` 生成時のレジストリ参照、② `getHoverName`・ツールチップ取得、③ ItemStack の NBT がアイテム ID 比較に混入しないこと（IML は文字列 ID で比較している） | S | 低 |
| ResourceLocation | `ResourceLocation.fromNamespaceAndPath/parse/tryParse` を 9 ファイルで使用（`Config`、`MilestoneService`、`TagTargets`、`Tracker`、`client/MilestoneScreen`、`network/` の 4 本） | 1.20.1 は `new ResourceLocation(ns, path)` / `new ResourceLocation(str)`。`tryParse` は存在する。`RegistryCompat` に `rl(ns, path)` / `parseRl(str)` を追加して吸収する（1.20.1 実装だけがコンストラクタを呼ぶ） | S | 低 |
| コマンド | `MilestoneCommands.java`、`MilestoneCommandRunner.java` | バニラ Brigadier はほぼ同一。`RegisterCommandsEvent` は `net.minecraftforge.event` の同名イベント。`Commands.literal` などは不変 | S | 低 |
| 進捗トリガー | `compat/v121_1/MilestoneRecordedTrigger.java`（`SimpleCriterionTrigger` + `Codec`/`RecordCodecBuilder` + `ContextAwarePredicate`）、`compat/v121_1/ModTriggers.java`（`DeferredRegister<CriterionTrigger<?>>` on `Registries.TRIGGER_TYPE`）、`compat/TriggerCompat.java`、`compat/v121_1/TriggerCompatImpl.java` | 1.20.1 では、トリガーはレジストリではなく `CriteriaTriggers.register(instance)` で登録する（可視性は着手時に確認し、非公開なら AT を使う）。インスタンスは `AbstractCriterionTriggerInstance`（`getCriterion()` が ResourceLocation ID を持つ）で、JSON 方式の `createInstance(JsonObject, ContextAwarePredicate, DeserializationContext)` と `serializeToJson` を実装する。Codec も `AdvancementHolder` もない。判定ロジックは `MilestoneTriggerLogic`（common）をそのまま使う | M | 中 |
| 権限 | `MilestonePermissions.java`（`PermissionGatherEvent`、`PermissionAPI`、`PermissionNode`、`PermissionTypes`）、`compat/v121_1/PermissionCompatImpl.java` | Forge 1.20.1 にも同じ設計の API がある（`net.minecraftforge.server.permission.*`。NeoForge 側の元になったもの）。基本は import 置換で済む | S | 低 |
| タグ表示名 | `TagTargets.java`（NeoForge `Tags.getTagTranslationKey`） | Forge 1.20.1 の `Tags` にはこのメソッドがない（要確認）。キー生成（`tag.item.<ns>.<path>`）を自前の 1 行に置き換え、フォールバックの `#ns:path` は現行どおり残す。慣習タグの名前空間は `c:` ではなく `forge:` が主流である（IML はタグを文字列で扱うので機能差はない） | S | 低 |
| クライアント | `client/ItemMilestoneLogClient.java`（`RegisterKeyMappingsEvent`、`ClientTickEvent`、`ClientPlayerNetworkEvent`、`ScreenEvent.KeyPressed.Post`、`KeyConflictContext`、`ItemTooltipEvent`）、画面 5 本（`MilestoneScreen`、`ItemPickerScreen`、`PresetListScreen`、`SavePresetScreen`、`ConfirmResetScreen`）、`compat/client/v121_1/{Scroll,Tooltip}CompatImpl.java` | イベントは同名のものが `net.minecraftforge.client.event` にある（`ClientTickEvent` だけ `TickEvent.ClientTickEvent` + Phase）。**`GuiGraphics` は 1.20 で導入済みなので、画面の描画呼び出しの大半は流用できる**。差分になりやすいのは、`renderBackground(GuiGraphics)`（1.20.1 は引数 1 つ）、`mouseScrolled(x, y, delta)`（1.20.2 で 4 引数化）、`AbstractSelectionList` のコンストラクタと `getRowLeft` 系。これらは `ScrollCompat` / 画面ファイルの差し替えで吸収する | M | 中 |
| GameTest | `gametest/MilestoneGameTestRegistration.java`（`RegisterGameTestsEvent`、`@GameTestHolder`、`@PrefixGameTestTemplate`）、`gametest/MilestoneGameTests.java`、`compat/v121_1/GameTestCompatImpl.java`、`common/resources/data/itemmilestonelog/structure/empty.nbt` | Forge 1.20.1 にも同等のもの（`net.minecraftforge.gametest.*`）がある。ただし**構造物のディレクトリは 1.20.1 では `structures/`（複数形）**なので、1.20.1-forge 側に `data/itemmilestonelog/structures/empty.nbt` を置く。**ゲートは単体テスト + 専用サーバー起動スモークとし、GameTest の移植はストレッチ目標にする** | S〜M | 低 |
| 言語／リソース | `common/resources/assets/itemmilestonelog/lang/{en_us,ja_jp}.json`、`logo.png`、`src/main/templates/META-INF/neoforge.mods.toml` | 言語 JSON の形式は同一で、そのまま共有できる（`LangFilesTest` が通ることをゲートにする）。`pack.mcmeta` は pack_format 15。`META-INF/mods.toml`（loaderVersion `[47,)`、依存は `forge` `[47.3.0,)` と `minecraft` `[1.20.1]`、`updateJSONURL` と `displayTest` は維持） | S | 低 |
| パス | `Presets.java`（`FMLPaths`） | `net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR`。import 置換だけで済む | S | 低 |
| その他ユーティリティ | `DatapackPresetReloadListener.java`（`ServerLifecycleHooks`）、`Tracker.java` / `ModTriggers.java`（`FakePlayer`） | `net.minecraftforge.server.ServerLifecycleHooks`、`net.minecraftforge.common.util.FakePlayer`。import 置換だけで済む | S | 低 |
| 公開 API のイベント | `api/MilestoneRecordedEvent.java`、`api/PresetCompletedEvent.java`（`net.neoforged.bus.api.Event`） | `net.minecraftforge.eventbus.api.Event`。キャンセル可能にする場合は `@Cancelable` を付ける（現行はキャンセル不可なので不要） | S | 低 |

### 3.2 イベント対応表（NeoForge 21.1 → Forge 47.x）

| 用途 | NeoForge（現行） | Forge 1.20.1 | 注意点 |
|---|---|---|---|
| サーバー tick（通知のフラッシュ） | `ServerTickEvent.Post` | `TickEvent.ServerTickEvent`（`phase == END`） | Phase 判定を忘れると 2 回実行される |
| プレイヤー tick（インベントリ走査） | `PlayerTickEvent.Post` | `TickEvent.PlayerTickEvent`（`phase == END && side.isServer()`） | Forge は両サイドで発火するので、サイド判定を必須にする |
| 拾得 | `ItemEntityPickupEvent.Post` | `PlayerEvent.ItemPickupEvent`（拾得後。`getStack()` は拾った分のコピー） | **意味の差を要確認**。Neo の Post は元スタックと残りを持つ。IML は「拾った瞬間に記録」だけなので、コピーで足りる見込み（`TrackerTest` で担保する） |
| クラフト結果 | `PlayerEvent.ItemCraftedEvent` | 同名 | 低 |
| 製錬結果の取り出し | `PlayerEvent.ItemSmeltedEvent` | 同名 | 低 |
| ログイン／ログアウト | `PlayerEvent.PlayerLoggedIn/OutEvent` | 同名 | 低 |
| ゲームモード変更 | `PlayerEvent.PlayerChangeGameModeEvent` | 同名 | 低 |
| サーバーのライフサイクル | `ServerStarting/Started/Stopping/StoppedEvent` | `net.minecraftforge.event.server.*` の同名イベント | 低 |
| コマンド登録 | `RegisterCommandsEvent` | 同名 | 低 |
| リロードリスナー | `AddReloadListenerEvent` | 同名 | 低 |
| データパック同期・タグ更新 | `OnDatapackSyncEvent` / `TagsUpdatedEvent` | 同名 | 低 |
| ワールド保存（自動バックアップ） | `LevelEvent.Save` | `net.minecraftforge.event.level.LevelEvent.Save` | 低 |
| 権限ノード収集 | `PermissionGatherEvent.Nodes` | 同名 | 低 |
| ツールチップ | `ItemTooltipEvent` | 同名 | 低 |
| キー登録 | `RegisterKeyMappingsEvent`（mod バス） | 同名（mod バス） | 低 |
| クライアント tick | `ClientTickEvent.Post` | `TickEvent.ClientTickEvent`（`phase == END`） | 低 |
| クライアントの接続／切断 | `ClientPlayerNetworkEvent.LoggingIn/Out` | 同名 | 低 |
| 画面のキー入力 | `ScreenEvent.KeyPressed.Post` | 同名 | 低 |
| `@SubscribeEvent` / `EventPriority` | `net.neoforged.bus.api` | `net.minecraftforge.eventbus.api` | import 置換だけ |

---

## 4. McCompat / LoaderCompat の評価

### 4.1 現状

- `compat/McCompat.java` は `compat/v121_1` の facet 実装をリフレクションで読み込む。facet は Trigger、Registry、Nbt、GameTest、Reload、Data、Chat、Player、Permission と、クライアント側の Network、Scroll、Tooltip。
- これは **NeoForge の中での MC バージョン差**を吸収するために作られた（DESIGN_1.3.0 §0-1）。一方、ローダー差（エントリ、バス、ネットワーク、設定）は facet の**上の層**にあり、`ItemMilestoneLog`、`Tracker`、`ModNetworking`、`Config`、`ItemMilestoneLogClient` に直接書かれている。

### 4.2 方針

- **1.20.1-forge ツリーの中だけで完結させる。** facet の interface（`compat/*.java`）はそのまま流用し、実装は `compat/v120_1/` と `compat/client/v120_1/` に新設して、`McCompat` に 1.20.1 用の選択分岐を足す（1.20.1-forge のコピーにだけ足す）。
- interface の流用が無理なく済むものは流用する（Nbt、Chat、Player、Permission、Registry、Scroll、Tooltip）。署名が NeoForge 固有の型を含むもの（`TriggerCompat` は `IEventBus` を受け取り、`NetworkCompat` は `CustomPacketPayload` を受け取る）は、1.20.1-forge 側の interface を書き換えてよい（このツリーでは identical 対象外）。
- 「LoaderCompat」層を新設して NeoForge ツリーと統一することは、**しない**。N246 と同じ判断であり、今回は利益がない（1.20.1 は単一 JAR で、反射による切り替えも不要）。

### 4.3 ファイル別の見積り（97 ファイル: main 72 / test 25）

| 区分 | 件数（概算） | 主なファイル |
|---|---|---|
| 変更なし | 約 46 | `api/` の 5 本（`ItemMilestoneLogApi`、`MilestoneRecord`、`RecordCause`、`TargetKey`、`WorldFirstRecord`）、`DiscordNotifier`、`ItemInfoLogic`、`MilestoneRankings`、`MilestoneExport`、`WorldFirstNames`、`Playtime`、`ServerLang`、`ScoreboardSync`、`client/ActionRowLayout`、`network/{PresetEntry,TargetEntry}`、`compat/{McVersion,NbtCompat,ChatCompat,PlayerCompat,PermissionCompat,RegistryCompat,DataCompat,ReloadCompat,GameTestCompat}` の interface 群、テスト約 21 本 |
| import 置換のみ | 約 14 | `api/MilestoneRecordedEvent`、`api/PresetCompletedEvent`、`MilestonePermissions`、`Presets`、`DatapackPresetReloadListener`、`MilestoneCommands`、`compat/v120_1/{Permission,Player,Chat,Nbt,Reload}CompatImpl`、テスト 2 本（`MilestonePermissionsTest`、`TrackerTest`） |
| 部分改修 | 約 23 | `Tracker`（Tick の Phase、拾得、`ResourceLocation`）、`MilestoneData` / `MilestoneBackup`（Provider 引数）、`MilestoneService`（送信と RL）、`TagTargets`、`Config`（Spec の置換と RL）、`MilestoneCommandRunner`、画面 5 本、`compat/client/v120_1/{Scroll,Tooltip}CompatImpl`、`compat/v120_1/{Registry,GameTest}CompatImpl`、`gametest/*` 2 本、`McCompat`、テスト約 2 本（ペイロード系） |
| 書き換え | 約 14 | `ItemMilestoneLog`、`client/ItemMilestoneLogClient`、`network/ModNetworking`、`network/*Payload` の 4 本、`compat/client/NetworkCompat` とその実装、`compat/TriggerCompat`、`compat/v120_1/{TriggerCompatImpl,MilestoneRecordedTrigger,ModTriggers,DataCompatImpl}` |

根拠: `net.neoforged.*` を直接 import している main 側の 20 ファイル（§3 に列挙）と、1.20.1 のバニラ API 差（SavedData の取得と保存、`Registries.TRIGGER_TYPE` がないこと、Codec 方式のトリガーがないこと、`StreamCodec`/`CustomPacketPayload` がないこと〔`network/` と `NetworkCompat` の 6 ファイル〕、`ResourceLocation` の静的ファクトリがないこと〔9 ファイル〕）を、ファイル単位で突き合わせた。`common/` の 60 ファイルは全件「変更なし」を想定する（§5 の監査次第）。

---

## 5. Java 17 監査

### 5.1 grep 結果（`common/src` + `versions/1.21.1/src`、2026-11-25）

| 対象（Java 18〜21 で追加） | 検出 | 判定 |
|---|---|---|
| `SequencedCollection` 系（`List.getFirst/getLast/removeFirst/removeLast/addFirst/addLast/reversed`） | `common/.../MilestoneCommandLogic.java:241` の `ready.removeFirst()` | **問題なし**。`ready` は `Deque<E>`（`ArrayDeque`）で、`Deque.removeFirst` は Java 6 からある |
| 〃 `.reversed()` | `common/.../LoginWorldFirstSummaryLogic.java:98`、`versions/1.21.1/.../MilestoneRankings.java:208` | **問題なし**。どちらも `Comparator.reversed()`（Java 8） |
| switch のパターンマッチング / record パターン / `case null` / `when` ガード（21） | 0 件 | — |
| 無名変数 `_`（22 プレビュー） / String Templates | 0 件 | — |
| `Math.clamp`、`StringBuilder.repeat`、`String.splitWithDelimiters`、`Character.isEmoji`（21） | 0 件 | — |
| `HashMap.newHashMap` など（19）、`Thread.threadId`（19）、`Locale.of`（19） | 0 件 | — |
| `HttpClient` / `ExecutorService` を try-with-resources で使う（AutoCloseable 化は 19/21） | 0 件 | `DiscordNotifier.java:1759,1762` の `executor.shutdownNow()` は Java 5 の API |
| Java 17 で使えるもの（参考） | `instanceof` パターン 33 箇所、テキストブロック 80 箇所、`record`、`Stream.toList()`、`String.formatted` | Java 16/15 で導入済みなので可 |

### 5.2 ツールチェーン方針

- 1.20.1-forge の `build.gradle` は `java.toolchain.languageVersion = JavaLanguageVersion.of(17)` とし、`tasks.withType(JavaCompile) { options.release = 17 }` で **API の可用性もコンパイル時に検査する**。grep だけでは見落としがありうるので、これを最終判定とする（ローカルには JDK がないため、今回はコンパイルで確認できていない）。
- `common/` に Java 21 専用 API を入れない規約を `common/` の README（または CONTRIBUTING）に明記する。さらに CI の 1.20.1-forge ビルドで `common/` を `--release 17` でコンパイルし、**以降の機能追加での混入を機械的に止める**。
- NeoForge 側（1.21.x は Java 21、26.x は Java 25）のツールチェーンは変更しない。

---

## 6. データ互換

| 項目 | 保証 | 説明 |
|---|---|---|
| 1.21.x のワールドを 1.20.1 で開く | **保証しない** | バニラ自体がダウングレードに非対応（チャンク、レジストリ、アイテムの形式）。IML の SavedData だけを移行する手段も提供しない |
| 1.20.1 のワールドを 1.21.x に上げる | **ベストエフォート** | バニラの DataFixer がワールドを変換する。IML の SavedData のキー構成は同一なので、1.21.1 の `MilestoneData.load` がそのまま読める見込み。ゲートは移行テスト（§9 Phase 5、`run-migration-test.sh` の流用） |
| SavedData の NBT キー構成 | **同一を保証** | `MilestoneData.save/load` のキー名と構造は変えない。違いはメソッドの署名（Provider 引数）だけ |
| バックアップファイル（`<world>/itemmilestonelog/backups/`）の復元 | **相互互換を保証** | `MilestoneBackupLogic`（common）で同じ形式を読み書きする。1.21.x で取ったバックアップを 1.20.1 で復元でき、その逆も可能（テストで担保する） |
| CSV / HTML エクスポート | **同一形式を保証** | `CsvExportRenderer` / `HtmlExportRenderer`（common）を共有する |
| アイテム ID | **文字列 ID として互換** | 1.20.1 に存在しないアイテム（1.21 で追加された `minecraft:mace` など）の記録は、ID の文字列として保持する。表示は `RegistryCompat` の未知 ID フォールバックに従う（記録を消さない） |
| プリセット（config ディレクトリ、データパック） | **互換** | JSON 形式は同一。データパックのプリセットは、1.20.1 の pack_format 15 に合わせた `pack.mcmeta` が必要（利用者側の作業。README に記載する） |

---

## 7. Fabric 1.20.1 評価（pen の判断材料）

### 7.1 作業領域

| 領域 | 内容 | 規模 |
|---|---|---|
| エントリ／イベント | `ModInitializer` / `ClientModInitializer`。Fabric API で賄えるもの: `ServerTickEvents`、`ServerLifecycleEvents`、`ServerPlayConnectionEvents`（ログイン）、`CommandRegistrationCallback`、`ResourceManagerHelper`（リロード）、`ItemTooltipCallback`、`ClientTickEvents`、`KeyBindingHelper` | M |
| **Mixin が必要なフック** | ① アイテム拾得（`ItemEntity.playerTouch` / `Inventory.add`）、② クラフト結果の取り出し（`ResultSlot.onTake` / `checkTakeAchievements`）、③ 製錬結果の取り出し（`FurnaceResultSlot.checkTakeAchievements`）、④ 進捗トリガーの登録（`CriteriaTriggers.register` の Invoker/Accessor）、⑤ ゲームモード変更（`ServerPlayer.setGameMode`）、⑥ データパック同期とタグ更新の一部 | L |
| ネットワーク | Fabric Networking API（1.20.5 より前の形式: `Identifier` + `PacketByteBuf`、`ServerPlayNetworking.canSend` でバニラクライアントを判定）。ペイロード 4 本と `ModNetworking` を再実装する | L |
| 設定 | Fabric には設定システムも設定画面もない。**手製の JSON（推奨）**にするか、Cloth Config に依存する（ゼロ依存の方針 P4 を破る）。画面は Mod Menu 連携（任意依存）にするか、なしにする | M〜L |
| 権限 | Fabric には標準の権限 API がない。fabric-permissions-api（サードパーティ）を任意依存にし、ない場合は OP レベルで判定する | M |
| マッピング | 既定は Yarn。**Loom で Mojmap + Parchment を選べば、Forge ツリーとのコード共有率が上がる**（クラス名とメソッド名が一致する）。Yarn を選ぶと版固有コードはほぼ全件が書き直しになる | 判断事項 |
| クライアント | 画面はバニラの `GuiGraphics` なので、Mojmap を選べば Forge 版の画面 5 本を流用できる。キー登録は `KeyBindingHelper`、画面のキー入力は `ScreenEvents` | M |
| ビルド | 独立した Loom プロジェクト `versions/1.20.1-fabric/`（fabric.mod.json、mixins.json、JDK 17） | M |

### 7.2 共有率の見積り

- `common/`: 60 ファイルの **100%** を共有できる（Forge と同じ条件）。
- 版固有ロジック（97 ファイル相当）: Mojmap を選んだ場合でも、**そのまま共有できるのは約 30%**（§4.3 の「変更なし」のうちローダー非依存の部分）。残りの約 70% は、Fabric API と Mixin への再実装か改修になる。Yarn を選ぶと共有率は 10% 未満。
- 工数: **Forge の 1.5〜2 倍（約 15〜20 人日相当）**。Mixin の互換性検証（他 Mod との衝突）が加わる。

### 7.3 継続的な保守コスト

- 以後の機能追加と不具合修正が **NeoForge（1.21.x / 26.x）+ Forge 1.20.1 + Fabric 1.20.1 の 3 系統**に毎回波及する。1.4.0 のバックログは 244 件あり、そのうち版固有コードに触れるもの（GUI、イベント、ネットワーク）は 3 系統分の実装とテストが必要になる。
- Mixin の対象はバニラの内部メソッドなので、1.20.1 の Fabric API や Loader の更新よりも、**他 Mod との Mixin 競合**が主なリスクになる（大型 modpack ほど起きやすい）。
- CI: Loom ビルドとサーバースモークのジョブが増える（iml-builder の JDK 17 を共用する）。

### 7.4 推奨: **DO AFTER FORGE（需要ゲート付き）**

- 理由 1: Forge の約 2 倍のコストに対して、1.20.1 の大型 modpack 需要（ATM9 など）の大半は Forge 版で取れる。
- 理由 2: 3 系統の保守は、244 件のバックログの消化速度を直接下げる。
- 理由 3: Forge 版ができた時点で、その成果（Mojmap 前提のコード、Java 17 監査済みの `common/`）がそのまま Fabric の下地になる。後から始めても損が小さい。
- **需要ゲート**（目安。数値は §10-Q5 で pen が確定する）: Forge 1.20.1 版の公開から 3 か月以内に、次のいずれかを満たしたら着手を再検討する。(a) Fabric 版を求める Issue やコメントが独立した 5 件以上ある、(b) Forge 1.20.1 版のダウンロードが全体の 30% 以上を占める。
- **安価な保険**: Forge 版の新規コードでは、ロジックを `common/` に置ける場合は置く（例: 拾得とクラフトの判定、ネットワークのレート制限計算、設定値の検証）。ローダー API に触れる部分は薄いアダプタに留める。これはコストゼロで後の Fabric 移植を楽にする。

### 7.5 DON'T DO にしない理由

1.20.1 は Fabric 側の modpack も多い世代で、需要が出る可能性は否定できない。ゲートを置いて判断を先送りするほうが、選択肢を閉じずに済む。

---

## 8. リリース形態

### 8.1 publish-manifest.json への追加

```json
{
  "file": "itemmilestonelog-forge-1.20.1-{version}.jar",
  "game_versions": ["1.20.1"],
  "loader": "forge",
  "forge_min": "47.3.0",
  "java": 17
}
```

- 既存の 5 エントリには `"loader": "neoforge"` を明示的に追記する（後方互換のため、`check-release.sh` は `loader` がなければ neoforge とみなす）。
- `check-release.sh` と `publish.yml` は `neoforge_min` を前提にしているので、`loader` に応じて `forge_min` / `neoforge_min` を読み分けるようにする。

### 8.2 配布先のタグ

- CurseForge: Game Version `1.20.1`、Modloader `Forge`、Java 17。NeoForge 用のファイルとは別ファイルとして同じプロジェクトに上げる。
- Modrinth: `loaders: ["forge"]`、`game_versions: ["1.20.1"]`。NeoForge 1.20.1 はタグに含めない（NeoForge 20.1 は Forge 互換をうたうが、未検証のため）。

### 8.3 mod_version の採番

- **決定（pen、2026-11-25）: 版番号は 1.3.0 とし、Forge 1.20.1 のみで出す**。NeoForge 側は既に 1.3.0 を出荷済みなので再リリースしない。1.20.1-forge プロジェクトの `mod_version` は 1.3.0（他プロジェクトと同値）。`docs/updates.json` には `1.20.1-latest` / `1.20.1-recommended` を足す。
- 1.4.0 以降は全系統で同じ版番号にそろえる（機能パリティを原則とする。崩れる場合は CHANGELOG に系統ごとの注記を入れる）。

### 8.4 CI

- 新設ジョブ `build-1201-forge`（ビルド + 単体テスト、`--release 17`）、`server-test-1201-forge`（GCE の iml-builder で専用サーバーを起動し、スモークテスト。`run-server-tests.sh` を Forge のサーバー起動に対応させる）。
- **iml-builder に JDK 17 を導入する必要がある**（既存ジョブは 21/25 を使う）。導入は Phase 5 の前提作業とする。
- `release-check` の `needs` に、上の 2 ジョブを追加する。
- `publish.yml` の変更範囲: マニフェストのループで `loader` に応じたタグを付けること、ビルド成果物の収集パスに `versions/1.20.1-forge/build/libs` を足すこと、の 2 点に限る。

---

## 9. 実装ステップ案と検証ゲート

| Phase | 内容 | 規模 |
|---|---|---|
| 0 | `versions/1.20.1-forge/` の雛形（ForgeGradle 6、Parchment、mods.toml、pack.mcmeta）を作り、空の Mod が起動するところまで | 0.5〜1 日 |
| 1 | `common/` を取り込み、`--release 17` でコンパイルと単体テスト（`common/` の全テストが通ること） | 0.5 日 |
| 2 | `versions/1.21.1/src` をコピーし、import 置換、エントリとバス、イベント（§3.2）、権限、パス、コマンドを改修。サーバー側の機能が動くまで | 2〜3 日 |
| 3 | SavedData、NBT、ResourceLocation、トリガー（`compat/v120_1`）。進捗トリガーの JSON 方式化 | 1〜1.5 日 |
| 4 | ネットワーク（SimpleChannel 化、任意チャンネル、レート制限）と、クライアント（画面 5 本、キー、ツールチップ、設定画面〔§10-Q1 による〕） | 2〜3 日 |
| 5 | CI ジョブ、identical-files の配列、マニフェストと publish の対応、サーバースモーク、移行テスト（1.20.1 から 1.21.1 へ） | 1.5〜2 日 |
| 6 | pen による実機検証と、リリース作業（CHANGELOG、updates.json、README） | 1〜1.5 日 |
| — | （ストレッチ）GameTest の移植 | +0.5〜1 日 |
| **合計** | | **約 9〜13 人日相当** |

### 検証ゲート

1. 単体テスト: `common/` と 1.20.1-forge の全テストが `--release 17` で通る（現行の 1.21.1 は 479 件規模。1.20.1 で除外するテストがあれば理由を付けて列挙する）。
2. 専用サーバーの起動: Forge 47.3.0（最小）と 47.4.x（最新）の両方で起動と停止ができ、ログに ERROR がない。
3. **バニラクライアントの参加テスト（pen の実機での手動ゲート）**: Mod 未導入の 1.20.1 バニラクライアントで参加できる。拾得、クラフト、製錬でチャット通知が出る。コマンドが動く。切断されない。
4. Forge クライアントでの参加テスト: GUI（5 画面）、キー、ツールチップ、同期が動く。レート制限が効く。
5. データ: バックアップを相互に復元できること（1.21.1 のバックアップを 1.20.1 で、1.20.1 のバックアップを 1.21.1 で）と、1.20.1 から 1.21.1 への移行テスト。
6. （参考）大型 modpack 環境でのスモーク。1.20.1 は ATM9 をはじめとする大型 Forge modpack の主戦場なので、この移植は利用者層の拡大に直結する。他 Mod と共存できるかの確認を推奨する。

---

## 10. 未決事項（pen への質問）

| # | 質問 | 推奨する既定値 |
|---|---|---|
| Q1 | Forge 1.20.1 に自動の設定画面がない件。設定画面を自作するか、省略するか | **省略**（Mods 一覧の「設定」ボタンを出さず、`config/itemmilestonelog-common.toml` の編集とコマンドで代替する）。GUI 凍結（P2）とも矛盾しない。需要が出たら 1.4.x で自作する |
| Q2 | mod_version の採番（§8.3） | ~~1.3.1 で全系統同時~~ → **決定: 1.3.0、Forge 1.20.1 のみ** |
| Q3 | Forge の最小バージョン | **47.3.0**（過去の recommended で、利用者が多い）。47.4.x の新 API は使わない |
| Q4 | GameTest の移植を必須ゲートに含めるか | **含めない**（単体テスト + サーバースモーク + 実機ゲートで十分。GameTest は Phase 6 の後にストレッチとして実施） |
| Q5 | Fabric 1.20.1 の需要ゲートの数値（§7.4） | **公開後 3 か月以内に、独立した要望 5 件、または Forge 1.20.1 版のダウンロードが全体の 30% 以上** |
