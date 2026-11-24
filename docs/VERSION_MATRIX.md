# バージョン検証マトリクス（N150 / N251 / N252）

`docs/DESIGN_1.3.0.md` に基づく単一 JAR 化（N251 / N252）に向けて、Minecraft 各バージョンの検証状況と API 差分を追跡するためのマトリクス。
検証用プロジェクトは `tools/new-version-project.sh` で `versions/verify/<mc>/` 配下に生成して使用する。

| MC | 系統 | NeoForge (min / latest) | Java | 状態 | 差分・メモ |
|---|---|---|---|---|---|
| 1.21.1 | 1.21.x | 21.1.1 / 21.1.267 | 21 | サーバーOK（現行リリース対象） | 基準版。プロファイル V121_1（基準ソース）、JAR A に収録（stage 2b） |
| 26.1.2 | 26.x | 26.1.2.71 / 26.1.2.129 | 25 | サーバーOK（現行リリース対象） | 26.x 基準版。単一 JAR（26.x マージ）でもサーバー検証済み（N251 stage 3、bot 参加あり） |
| 26.2 | 26.x | 26.2.0.57 / 26.2.0.95 | 25 | サーバーOK（現行リリース対象） | gui.screen() 化・advancement パッケージ移動（DESIGN §1.1 A/B）。単一 JAR でもサーバー検証済み（N251 stage 3） |
| 26.3 | 26.x | 26.3.0.76 / 26.3.0.115 | 25 | サーバーOK（現行リリース対象） | getHoveredSlot・getUpdateCause 削除・トリガー型変更・Config LOCAL（DESIGN §1.1 A/C/D/E）。単一 JAR でもサーバー検証済み（N251 stage 3、Config LOCAL の振り分け含む） |
| 1.21 | 1.21.x | 21.0.143 / 21.0.167 | 21 | コンパイルOK | stage 1 検証済み: コンパイル成功、単体テスト 479 件中 478 件成功。唯一の失敗 LangFilesTest は検証プロジェクト配置（1 階層深い）で `../..` 相対パス解決が外れた検証環境側の事象であり、MC 1.21 の非互換ではない。範囲に含める（DESIGN §4.2）。stage 2b: プロファイル V121_1（基準ソース）、JAR A に収録、ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功） |
| 1.21.2 | 1.21.x | 21.2.0-beta / 21.2.1-beta | 21 | コンパイルOK | stage 2b: プロファイル V121_2、JAR A。ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功）。レジストリ get() の Optional 化（RegistryCompat）、NBT ゲッターの Optional 化（NbtCompat）、Item.getDescription() 削除（RegistryCompat.itemName）、DimensionDataStorage コンストラクタの Path 化（テストのみ）。stage 1 時点メモ: レジストリ参照が Optional<Reference<>> 化（ItemStack 構築・Item / SoundEvent の比較）。対象は MilestoneService / Tracker / TagTargets。この系統はベータのみ |
| 1.21.3 | 1.21.x | 21.3.56 / 21.3.100 | 21 | コンパイルOK | stage 2b: プロファイル V121_2（1.21.2 と同一）、JAR A。ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功） |
| 1.21.4 | 1.21.x | 21.4.121 / 21.4.161 | 21 | コンパイルOK | stage 2b: プロファイル V121_4、JAR A（上端）。ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功）。AddReloadListenerEvent → AddServerReloadListenersEvent（ReloadCompat）、getScrollAmount() → scrollAmount()（ScrollCompat）、getScrollbarPosition() の上位メソッド削除（@Override 除去）。stage 1 時点メモ: レジストリ Optional 化 + @Override 不一致（ItemMilestoneLog、イベント / ライフサイクルの署名変更） |
| 1.21.5 | 1.21.x | 21.5.74 / 21.5.101 | 21 | コンパイルOK | stage 2b: プロファイル V121_5、JAR B（下端）→ stage 2c で JAR B1（下端）。ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功）。SavedData.Factory 削除 → SavedDataType + codec（DataCompat、ディスク上の旧 `"data"` ラッパーを維持）、Tag.TAG_ANY_NUMERIC / getAsString() 削除（NbtCompat）、ClickEvent / HoverEvent の record 化（ChatCompat）、GameTest 登録の再設計（ファイル差し替え）。stage 1 時点メモ: NBT 大改訂の開始点: CompoundTag アクセサの Optional 返却化・contains / getList の署名変更（MilestoneData に集中）。GameTest の assertTrue が Component 引数化（テスト側のみ） |
| 1.21.6 | 1.21.x | 21.6.0-beta / 21.6.20-beta | 21 | コンパイルOK | stage 2b: プロファイル V121_6、JAR B → stage 2c で JAR B1。ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功）。WorldVersion の record アクセサ化（McVersion.versionName で反射検出）、renderTooltip(Font, ItemStack, …) 削除 → setTooltipForNextFrame（TooltipCompat）、テキスト描画が色のアルファを反映（共有クライアントコードの GUI 文字色をアルファ付き 0xFF…… に統一）。stage 1 時点メモ: 1.21.5 の差分 + ServerPlayer.server フィールドの非公開化（MilestoneCommands。getServer() への置換で吸収見込み）。この系統はベータのみ |
| 1.21.7 | 1.21.x | 21.7.0-beta / 21.7.25-beta | 21 | コンパイルOK | stage 2b: プロファイル V121_6（1.21.6 と同一）、JAR B → stage 2c で JAR B1。ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功）。この系統はベータのみ |
| 1.21.8 | 1.21.x | 21.8.9 / 21.8.57 | 21 | コンパイルOK | stage 2b: プロファイル V121_8、JAR B → stage 2c で JAR B1（上端）。ハーネスビルド + 単体テスト 479 件（検証配置由来の LangFilesTest を除き成功）。PacketDistributor.sendToServer 削除 → ClientPacketDistributor（NetworkCompat）。stage 1 時点メモ: 1.21.6 と同一プロファイル（stage 2b で独立プロファイルに変更） |
| 1.21.9 | 1.21.x | 21.9.0-beta / 21.9.16-beta | 21 | コンパイルOK | stage 2b: プロファイル V121_9、JAR B → stage 2c で JAR B2（下端。画面 Entry 描画の分岐により B から分割）。ハーネスビルド + 単体テスト 479 件全件成功。ServerPlayer.getServer() 削除 → level().getServer()（共有書き換え 48 箇所）、GameProfile の record 化（PlayerCompat）、リスト Entry の render → renderContent + MouseButtonEvent（3 画面のファイル差し替え）、KeyMapping.Category 等（ItemMilestoneLogClient のファイル差し替え）、SharedConstants.VERSION_STRING 削除。この系統はベータのみ。stage 1 時点メモ: 1.21.6 と同一プロファイル（stage 2b で独立プロファイルに変更） |
| 1.21.10 | 1.21.x | 21.10.63 / 21.10.64 | 21 | コンパイルOK | stage 2b: プロファイル V121_9（1.21.9 と同一）、JAR B（上端）→ stage 2c で JAR B2（上端）。ハーネスビルド + 単体テスト 479 件全件成功。stage 1 時点メモ: 1.21.6 と同一プロファイル（stage 2b で V121_9 に変更） |
| 1.21.11 | 1.21.x | 21.11.42 / 21.11.48 | 21 | コンパイルOK | stage 2b: プロファイル V121_11、JAR C（単独）。ハーネスビルド + 単体テスト 479 件全件成功（2026-11-25）。ResourceLocation → Identifier / ResourceLocationArgument → IdentifierArgument のバイナリ非互換リネームにより JAR B に収録不可、独立 JAR が必要。当初は 1.3.0 で出荷するか pen の判断待ちだったが、2026-11-25 に pen が 1.3.0 への収録を決定。リネームは `tools/prepare-121x-harness.sh` が生成プロジェクトに機械適用（リポジトリのソースはリネームなし）。残る適応は PermissionCompat・PlayerCompat.playNotifySound・DataCompatImpl（v121_11）の 3 点。stage 1 時点メモ: 1.21.6 の差分 + advancement criterion パッケージ移動（MilestoneRecordedTrigger、26.2 と同種の移動） |

## N252 stage 1 検証結果（2026-11-24）

- 検証方法: GCE VM（iml-builder）上で各 MC バージョンの検証プロジェクトを生成し、その系統の最小 NeoForge で `gradlew test`（Parchment ブロックは除去。Parchment はパラメータ名のみで検証対象外）。
- 第 1 回実行は生成スクリプトの不具合（検証プロジェクトが 1 階層深いため `../../common/` の相対パスが外れ、共有ソースが全滅）で全バージョン FAIL となった。PR #75 で生成時に `../../../common/` へ書き換えるよう修正し、再検証した結果が上表である。
- 差分は 2 つのセグメントに集約される:
  - セグメント A（1.21–1.21.4）: レジストリ参照の Optional 化が主差分（+ 1.21.4 の @Override 不一致）。
  - セグメント B（1.21.5–1.21.11）【stage 2b で更新: セグメント B は 1.21.5–1.21.10 に縮小し、1.21.11 は別扱い。下記「N252 stage 2b 検証結果」参照】: NBT アクセサの Optional 化が主差分（+ 1.21.6 の ServerPlayer.server 非公開化、1.21.11 のトリガーパッケージ移動。GameTest の Component 化はテスト側のみ）。
- 【stage 2b で更新: JAR B の範囲は [1.21.5, 1.21.10] に変更。1.21.11 は ResourceLocation → Identifier のバイナリ非互換リネームのため JAR B に含められず、独立 JAR が必要（1.3.0 での出荷は pen の判断待ちだったが、2026-11-25 に収録を決定し JAR C = [1.21.11] とした。配布物は計 4 JAR）。【stage 2c で更新: JAR B は 1.21.9 を境に JAR B1 = [1.21.5, 1.21.8] と JAR B2 = [1.21.9, 1.21.10] に分割し、配布物は計 5 JAR。下記「配布 JAR」参照】以下は stage 1 時点の記述として残す】
  - **分割判断（DESIGN §4.3）: 1.21.5 を境界に 2 JAR へ分割する。** 根拠: 分割しない場合、セグメント B の範囲内に NbtCompat と TriggerCompat（1.21.11）が重なり、さらに RegistryCompat も全域で必要となるため、基準 (b)「1 セグメント内に新規 compat システム ≥2」に該当する。分割後は JAR A = [1.21, 1.21.4]（RegistryCompat 主体）、JAR B = [1.21.5, 1.21.11]（NbtCompat 主体 + 1.21.11 のトリガー変種）となり、各 JAR の compat 負担は N251 の 26.x 統合 JAR（4 系統）以下に収まる。配布物は 26.x 統合 JAR と合わせて計 3 JAR になる。
- LangFilesTest の失敗は検証配置に起因するもので製品コードの非互換ではない（`resolveLangDir` が作業ディレクトリからの `../..` を前提とするため）。stage 2 では通常配置の実プロジェクトで再確認する。

## N252 stage 2b 検証結果（2026-11-25）

- 検証方法: GCE 上で `tools/prepare-121x-harness.sh` + N150 検証プロジェクトによるハーネスビルドと単体テスト 479 件の実行。
- 結果: **1.21、1.21.1（基準）、1.21.2、1.21.3、1.21.4、1.21.5、1.21.6、1.21.7、1.21.8、1.21.9、1.21.10 でビルド成功・テスト 479 件実行。** これまでに観測された失敗は `LangFilesTest.bundledLanguagesMatchLangDirectoryFiles` のみで、これは検証プロジェクト配置（lang ディレクトリを `../..` で解決）に起因する検証環境側の事象であり、MC の非互換ではない。1.21.9 / 1.21.10 は全件成功。1.21.11 は当初プロトタイプのみで未検証（出荷判断待ち）だったが、2026-11-25 に pen が 1.3.0 への収録を決定し、同日ハーネス検証を完了した（BUILD SUCCESSFUL、テスト 479 件、失敗なし）。これにより **1.21.x 系 1.21–1.21.11 の全バージョンが検証済み**となった。

### compat プロファイル

`McVersion` のプロファイル。変種ソースは `versions/1.21.1/compat-variants/` 配下。

| プロファイル | 対象バージョン |
|---|---|
| V121_1 | 1.21、1.21.1（基準ソース） |
| V121_2 | 1.21.2 – 1.21.3 |
| V121_4 | 1.21.4 |
| V121_5 | 1.21.5 |
| V121_6 | 1.21.6 – 1.21.7 |
| V121_8 | 1.21.8 |
| V121_9 | 1.21.9 – 1.21.10 |
| V121_11 | 1.21.11（JAR C。Identifier リネームはハーネスが生成プロジェクトに適用、後述） |

### 配布 JAR（stage 1 の分割判断を更新）

- 【stage 2c で更新】1.21.x 系の配布 JAR は **JAR A = [1.21, 1.21.4]、JAR B1 = [1.21.5, 1.21.8]、JAR B2 = [1.21.9, 1.21.10]、JAR C = [1.21.11]**。26.x 統合 JAR と合わせて**計 5 JAR**。
  - JAR A: プロファイル v121_1（ベース）+ v121_2 + v121_4 をマージ。`itemmilestonelog-neoforge-1.21-1.21.4-<v>.jar`
  - JAR B1: プロファイル v121_5（ベース）+ v121_6 + v121_8 をマージ。`itemmilestonelog-neoforge-1.21.5-1.21.8-<v>.jar`
  - JAR B2: プロファイル v121_9 単独（JAR C と同様、プロファイル JAR の versionRange を書き換えるのみでマージなし）。`itemmilestonelog-neoforge-1.21.9-1.21.10-<v>.jar`
  - JAR C: プロファイル v121_11 単独。`itemmilestonelog-neoforge-1.21.11-<v>.jar`
- 1.21.11 は独立 JAR が必要（後述のリネームのため）。当初は 1.3.0 で出荷するか pen の判断待ちとしていたが、2026-11-25 に pen が 1.3.0 への収録を決定し、JAR C として出荷する。
- stage 1 で計画した JAR B = [1.21.5, 1.21.11] はこれにより置き換えられる。
- stage 2b 時点では JAR B = [1.21.5, 1.21.10] を 1 本の JAR として計画していた（計 4 JAR）。stage 2c の JAR 組み立て検証で、クライアント画面（MilestoneScreen / ItemPickerScreen / PresetListScreen の Entry 描画）が 1.21.9 で分岐する（`render` → `renderContent`、加えて `mouseClicked` / `KeyMapping.Category` の変更）ことが判明した。同一 FQCN のクラスは 1 つの JAR に共存できないため DESIGN §4.3 の分割基準を適用し、2026-11-25 に pen が 1.21.9 を境に JAR B1 / JAR B2 へ分割することを決定した。
- なお GameTest 登録のファイル差し替え（MilestoneGameTestRegistration）は v121_5–v121_9 で同一内容のため、マージ上の衝突にはならない。クライアント画面系のファイル差し替えは v121_9 / v121_11 にのみ存在する。

### バージョン別の API 破壊と吸収方法

各破壊は compat 層、バージョン非依存の共有書き換え、またはプロファイル別ファイル差し替えのいずれかで吸収した。

- **1.21.2**: レジストリ `get()` の Optional 返却化（RegistryCompat）、NBT ゲッターの Optional 返却化（NbtCompat）、`Item.getDescription()` 削除（RegistryCompat.itemName）、`DimensionDataStorage` コンストラクタの Path 引数化（テストのみ）。
- **1.21.4**: `AddReloadListenerEvent` 削除 → `AddServerReloadListenersEvent`（ReloadCompat）、`getScrollAmount()` → `scrollAmount()`（ScrollCompat）、上位型の `getScrollbarPosition()` 削除（@Override を除去）。
- **1.21.5**: `SavedData.Factory` 削除 → `SavedDataType` + 既存 NBT load/save を経由する codec 往復。ディスク上の旧 `"data"` ラッパーを維持し、ワールドファイルを 1.21.x 全域で相互に読めるようにした（DataCompat）。`Tag.TAG_ANY_NUMERIC` と `Tag.getAsString()` 削除（NbtCompat.ANY_NUMERIC / tagAsString）。ClickEvent / HoverEvent の record 化（ChatCompat）。GameTest 登録が RegisterGameTestsEvent / GameTestInstance に再設計（ファイル差し替え）。
- **1.21.6**: `WorldVersion` の record アクセサ化。バージョン検出は反射で行う（McVersion.versionName）。`renderTooltip(Font, ItemStack, …)` 削除 → `setTooltipForNextFrame`（TooltipCompat）。テキスト描画が色のアルファを反映するようになったため、共有クライアントコードの GUI 文字色はすべてアルファ付き（0xFF……）にした。
- **1.21.8**: `PacketDistributor.sendToServer` 削除 → `ClientPacketDistributor`（NetworkCompat）。
- **1.21.9**: `ServerPlayer.getServer()` 削除 → `level().getServer()`（共有書き換え、48 箇所）。GameProfile の record 化 — `name()` と `NameAndId`（PlayerCompat、isSingleplayerOwner を含む）。リスト Entry の `render` → `renderContent` + `MouseButtonEvent`（3 画面のファイル差し替え）。`KeyMapping.Category` + `registerCategory` + `InputConstants.getKey(KeyEvent)`（ItemMilestoneLogClient のファイル差し替え）。`SharedConstants.VERSION_STRING` 削除（検出は反射でフォールバック）。
- **1.21.11**: `ResourceLocation` → `Identifier`、`ResourceLocationArgument` → `IdentifierArgument`（バイナリ非互換のリネームで、1 つの JAR で 1.21.10 ↔ 1.21.11 をまたげない）。リネームは機械的なもので、`tools/prepare-121x-harness.sh` がプロファイル v121_11 のときに生成プロジェクトへ適用する（リポジトリのソースはリネームなしのまま）。残る適応は 3 点: (1) 権限 — `PermissionCompat`（従来は `hasPermission` / `hasPermissions`、1.21.11 では `permissions()` に対して `Commands.LEVEL_*` の PermissionCheck で判定）、(2) サウンド — `PlayerCompat.playNotifySound` は 1.21.11 では `player.connection` 経由で `ClientboundSoundPacket` を送信、(3) `SavedDataType.Factory` が 1.21.11 では `ServerLevel` を直接受け取る（v121_11 の DataCompatImpl のみ）。また `Commands.LEVEL_GAMEMASTERS` の型が 1.21.11 で int → PermissionCheck に変わるため、この定数を参照していた唯一の箇所は PermissionCompat にリテラルのレベル 2 を渡す形に変更した。プロトタイプ段階（pen の判断待ち）を経て、2026-11-25 にハーネスビルド + 単体テスト 479 件全件成功を確認（検証済み）。

### 実クライアントでの後日確認事項

- 1.21.9 以降の差し替え画面における行レイアウトのオフセットは 26.x の行に合わせている（−2px）。実際の 1.21.9 クライアントでは未確認。
- 1.21.6 のアルファ付き文字色への変更はコード比較でのみ確認済み。

## 記入ルール

- 状態は `未検証` / `検証中` / `コンパイルOK` / `サーバーOK` / `手動確認OK` のいずれかで記入する
- 検証を1つ進めるごとに状態を更新し、差分・メモにコンパイルエラーや API 差分の要点を残す
- NeoForge の min/latest は実在するバージョンを公式プロジェクトページで確認してから記入する（推測で書かない）
- 分割判断は DESIGN §4.3 の基準に従い、判断したらその根拠を該当行に記録する
