package com.pengin0503.itemmilestonelog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JSON export/import helpers (F6, extended with quantities in Q7), standalone HTML summary export (F3),
 * and RFC 4180 CSV export (F26).
 */
public final class MilestoneExport {
    public static final int FORMAT_VERSION = 1;
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Shared per-player export snapshot gathered once for JSON, HTML, and CSV exports. */
    record PlayerExportSnapshot(
            UUID uuid,
            String playerName,
            List<TargetKey> personalTargets,
            Map<TargetKey, MilestoneRecord> records) {
    }

    @FunctionalInterface
    private interface WriterTask {
        void write(Writer writer) throws IOException;
    }

    public record ImportPayload(
            @Nullable UUID ownerUuid,
            @Nullable String ownerName,
            List<TargetKey> targets,
            Map<TargetKey, MilestoneRecord> records) {
        public ImportPayload(@Nullable UUID ownerUuid, List<TargetKey> targets, Map<TargetKey, MilestoneRecord> records) {
            this(ownerUuid, null, targets, records);
        }

        public ImportPayload(List<TargetKey> targets, Map<TargetKey, MilestoneRecord> records) {
            this(null, null, targets, records);
        }
    }

    public static boolean canImport(ImportPayload payload, UUID importerUuid, boolean isPrivileged) {
        if (isPrivileged) {
            return true;
        }
        return payload.ownerUuid() != null && payload.ownerUuid().equals(importerUuid);
    }

    public record ImportMergeStats(int addedTargets, int updatedRecords) {
    }

    /**
     * Parsed contents of an {@code exportall} JSON file (F22).
     *
     * <p>F31 seam: {@code rawWorldFirsts} preserves the optional top-level {@code "worldFirsts"} array
     * (or {@code null} when absent) so F31 can plug in world-first parsing and merging without altering
     * the command or file-reading flow.
     */
    public record AllPlayersImportPayload(
            List<ImportPayload> players,
            int skippedEntries,
            @Nullable JsonArray rawWorldFirsts) {
        public AllPlayersImportPayload(List<ImportPayload> players, int skippedEntries) {
            this(players, skippedEntries, null);
        }
    }

    /**
     * Aggregate counts returned by {@link #mergeAllInto} for {@code message.itemmilestonelog.importall_done}
     * (F22 + F31).
     */
    public record ImportAllMergeStats(
            int importedPlayers,
            int addedTargets,
            int updatedRecords,
            int updatedWorldFirsts,
            int skippedEntries) {
    }

    /** One parsed record update inside a {@link PlayerMergePlan}, kept in file order. */
    public record RecordUpdate(TargetKey key, MilestoneRecord record) {
    }

    /** Parsed, off-thread merge input for one player (F-140-3 importall). */
    public record PlayerMergePlan(
            UUID uuid,
            List<String> ownerNames,
            List<TargetKey> targets,
            List<RecordUpdate> recordUpdates) {
        public PlayerMergePlan {
            ownerNames = List.copyOf(ownerNames);
            targets = List.copyOf(targets);
            recordUpdates = List.copyOf(recordUpdates);
        }
    }

    /**
     * Off-thread importall computation: parsed player entries grouped by UUID, optional parsed
     * world-first entries, and the skip counts already known from parsing. Applying this plan to
     * the live {@link MilestoneData} stays on the server thread ({@link #applyMergePlan}).
     */
    public record MergePlan(
            List<PlayerMergePlan> players,
            int skippedEntries,
            @Nullable List<WorldFirstRecord> worldFirsts,
            int worldFirstSkippedEntries) {
        public MergePlan {
            players = List.copyOf(players);
            if (worldFirsts != null) {
                worldFirsts = List.copyOf(worldFirsts);
            }
        }
    }

    private MilestoneExport() {
    }

    public static Path exportsDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve(ItemMilestoneLog.MOD_ID)
                .resolve("exports");
    }

    public static boolean isSafeFileName(String fileName) {
        return fileName != null
                && !fileName.isEmpty()
                && !fileName.contains("..")
                && SAFE_FILENAME.matcher(fileName).matches();
    }

    /** Maximum size of a single-player import file read on the server thread (10 MiB). */
    static final long MAX_IMPORT_BYTES = 10L * 1024 * 1024;
    /** Maximum size of an all-players import file read on the server thread (32 MiB, F22). */
    static final long MAX_IMPORT_ALL_BYTES = 32L * 1024 * 1024;

    public static String sanitizePlayerFileBaseName(@Nullable String playerName) {
        String base = (playerName == null || playerName.isBlank()) ? "player" : playerName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (base.contains("..")) {
            base = base.replace("..", "_");
        }
        // Strip trailing dots (invalid/ambiguous on some filesystems), then re-check the ".." rule.
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '.') {
            end--;
        }
        base = base.substring(0, end);
        if (base.isEmpty()) {
            base = "player";
        }
        if (base.contains("..")) {
            base = base.replace("..", "_");
        }
        // Must not collide with export-all files (all-players.json, all-players.html, all-players.csv).
        if (base.equalsIgnoreCase(HtmlExportRenderer.ALL_PLAYERS_BASE_NAME)) {
            base = "all-players-player";
        }
        return base;
    }

    /**
     * #137: player-specific base name with a stable owner suffix. Sanitization alone is not
     * injective ({@code Bob!}, {@code Bob?} and {@code Bob_} all become {@code Bob_}; a name reused
     * by a different UUID also collides), so the owner UUID is appended verbatim. UUIDs are unique
     * and the value is stable across exports and server restarts, keeping the name recognizable.
     *
     * <p>When {@code uuid} is null the historical sanitized-only base is returned; production
     * export paths always pass the player UUID.
     */
    public static String sanitizePlayerFileBaseName(@Nullable String playerName, @Nullable UUID uuid) {
        String base = sanitizePlayerFileBaseName(playerName);
        return uuid == null ? base : base + "-" + uuid;
    }

    public static String sanitizePlayerFileName(@Nullable String playerName, @Nullable UUID uuid, String extension) {
        String ext = extension == null ? "" : extension;
        if (!ext.isEmpty() && !ext.startsWith(".")) {
            ext = "." + ext;
        }
        return sanitizePlayerFileBaseName(playerName, uuid) + ext;
    }

    public static String sanitizePlayerFileName(@Nullable String playerName, @Nullable UUID uuid) {
        return sanitizePlayerFileName(playerName, uuid, ".json");
    }

    public static String sanitizePlayerHtmlFileName(@Nullable String playerName, @Nullable UUID uuid) {
        return sanitizePlayerFileName(playerName, uuid, ".html");
    }

    public static String sanitizePlayerCsvFileName(@Nullable String playerName, @Nullable UUID uuid) {
        return sanitizePlayerFileName(playerName, uuid, ".csv");
    }

    public static String sanitizePlayerFileName(@Nullable String playerName, String extension) {
        String ext = extension == null ? "" : extension;
        if (!ext.isEmpty() && !ext.startsWith(".")) {
            ext = "." + ext;
        }
        return sanitizePlayerFileBaseName(playerName) + ext;
    }

    public static String sanitizePlayerFileName(String playerName) {
        return sanitizePlayerFileName(playerName, ".json");
    }

    public static String sanitizePlayerHtmlFileName(@Nullable String playerName) {
        return sanitizePlayerFileName(playerName, ".html");
    }

    public static String sanitizePlayerCsvFileName(@Nullable String playerName) {
        return sanitizePlayerFileName(playerName, ".csv");
    }

    public static List<String> listExportFiles(MinecraftServer server) {
        Path dir = exportsDir(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && isSafeFileName(name)
                            && !ExportSnapshotFiles.isSnapshotFileName(name))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not list exports directory {}: {}", dir, e.getMessage());
        }
        return names;
    }

    private static void writeAtomically(Path dir, String prefix, Path target, WriterTask task) throws IOException {
        Path temp = Files.createTempFile(dir, prefix + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                task.write(writer);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static void writeJsonAtomically(Path dir, String prefix, Path target, JsonObject root) throws IOException {
        writeAtomically(dir, prefix, target, writer -> GSON.toJson(root, writer));
    }

    private static void writeTextAtomically(Path dir, String prefix, Path target, String content) throws IOException {
        writeAtomically(dir, prefix, target, writer -> writer.write(content));
    }

    // ------------------------------------------- step 7 PR-1: shared export options / snapshots

    /** Per-session sequence for equal-second snapshot names; avoids directory I/O on the server thread. */
    private static final ConcurrentMap<String, AtomicInteger> SNAPSHOT_SEQUENCE = new ConcurrentHashMap<>();
    private static final AutoExportSchedule.Throttle AUTO_EXPORT_THROTTLE = new AutoExportSchedule.Throttle();
    private static volatile long lastAutoExportMillis = 0L;
    private static final AtomicBoolean FINAL_AUTO_EXPORT_WRITTEN = new AtomicBoolean(false);

    /** Clears the in-memory auto-export state for a new server session (no SavedData keys). */
    public static void resetAutoExportSession() {
        AUTO_EXPORT_THROTTLE.reset();
        lastAutoExportMillis = 0L;
        FINAL_AUTO_EXPORT_WRITTEN.set(false);
        SNAPSHOT_SEQUENCE.clear();
    }

    private static String relativePath(String fileName) {
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    private static String resolveSnapshotFileName(Path dir, String canonicalFileName,
                                                  Instant now, ZoneId zone) {
        String timestamp = ExportSnapshotFiles.formatTimestamp(now, zone);
        String key = dir.toAbsolutePath().normalize() + "|" + canonicalFileName + "|" + timestamp;
        int suffix = SNAPSHOT_SEQUENCE.computeIfAbsent(key, ignored -> new AtomicInteger()).getAndIncrement();
        List<String> taken = new ArrayList<>(suffix);
        int dot = canonicalFileName.lastIndexOf('.');
        String stem = "snapshot-" + canonicalFileName.substring(0, dot) + "-" + timestamp;
        String extension = canonicalFileName.substring(dot);
        for (int i = 0; i < suffix; i++) {
            taken.add(i == 0 ? stem + extension : stem + "-" + i + extension);
        }
        return ExportSnapshotFiles.chooseFileName(canonicalFileName, timestamp, taken);
    }

    /** Writes a fully rendered export on the I/O thread (or synchronously when no queue exists). */
    private static void writeText(Path dir, String fileName, String content) throws IOException {
        if (!isSafeFileName(fileName)) {
            throw new IOException("Invalid export file name");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName).normalize();
        if (!target.getParent().equals(dir.normalize())) {
            throw new IOException("Invalid export target path");
        }
        writeTextAtomically(dir, fileName, target, content);
    }

    private static void writeExportContent(Path dir, String fileName, String content, ExportOptions options)
            throws IOException {
        Runnable task = () -> {
            try {
                writeText(dir, fileName, content);
                if (options.snapshot()) {
                    pruneSnapshotFiles(dir);
                }
            } catch (IOException e) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Export write failed for {}: {}",
                        fileName, e.getClass().getSimpleName());
            }
        };
        if (!MilestoneIo.submit(MilestoneIoLogic.TaskKind.EXPORT, task)) {
            // No live executor (tests / early startup): keep the historic synchronous contract.
            writeText(dir, fileName, content);
            if (options.snapshot()) {
                pruneSnapshotFiles(dir);
            }
        }
    }

    /**
     * Deletes old dated snapshots only. Canonical {@code all-players.*} files, player exports and
     * hand-written files never match the dedicated pattern and are never touched (D-150-250).
     *
     * @return number of files actually removed
     */
    public static int pruneSnapshotFiles(Path dir) {
        List<String> names;
        try (Stream<Path> stream = Files.list(dir)) {
            names = stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not list snapshots in {}: {}",
                    dir, e.getClass().getSimpleName());
            return 0;
        }
        int pruned = 0;
        for (String name : ExportSnapshotFiles.selectFilesToPrune(
                names, ExportSnapshotFiles.DEFAULT_GENERATIONS)) {
            if (!ExportSnapshotFiles.isSnapshotFileName(name)) {
                continue;
            }
            Path target = dir.resolve(name).normalize();
            if (!target.getParent().equals(dir.normalize())) {
                continue;
            }
            try {
                if (Files.deleteIfExists(target)) {
                    pruned++;
                }
            } catch (IOException e) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Snapshot prune failed for {}: {}",
                        name, e.getClass().getSimpleName());
            }
        }
        return pruned;
    }

    private static JsonObject buildAllPlayersObject(List<PlayerExportSnapshot> snapshots,
                                                    Map<TargetKey, WorldFirstRecord> worldFirsts) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        JsonArray playersArr = new JsonArray();
        for (PlayerExportSnapshot snapshot : snapshots) {
            playersArr.add(buildPlayerObject(snapshot));
        }
        root.add("players", playersArr);
        root.add("worldFirsts", buildWorldFirstsArray(worldFirsts));
        return root;
    }

    private static void warnAutoExport(Exception e) {
        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Auto export failed: {}", e.getClass().getSimpleName());
    }

    /**
     * Writes the two files of one automatic batch. Each writer is isolated so a failed HTML file
     * never prevents the JSON file from being written (and vice versa).
     *
     * @return number of files actually written (0, 1 or 2)
     */
    public static int writeAutoExportBatchFiles(Path dir, String jsonName, String htmlName,
                                                String json, String html) {
        int written = 0;
        try {
            writeText(dir, jsonName, json);
            written++;
        } catch (IOException e) {
            warnAutoExport(e);
        }
        try {
            writeText(dir, htmlName, html);
            written++;
        } catch (IOException e) {
            warnAutoExport(e);
        }
        pruneSnapshotFiles(dir);
        return written;
    }

    /**
     * Called from the existing {@code ServerTickEvent.Post} hook. The one-hour tick throttle and
     * the configured hour window are both checked on the server thread; only the rendered files
     * are handed to the {@code itemmilestonelog-io} queue as one batch (F-150-90).
     */
    public static void autoExportTick(MinecraftServer server) {
        if (!AUTO_EXPORT_THROTTLE.tick()) {
            return;
        }
        if (!Config.AUTO_EXPORT_ENABLED.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        int intervalHours = Config.AUTO_EXPORT_INTERVAL_HOURS.get();
        if (AutoExportSchedule.decide(now, lastAutoExportMillis, intervalHours)
                != AutoExportSchedule.Decision.COLLECT) {
            return;
        }
        lastAutoExportMillis = now;
        queueAutoExportBatch(server);
    }

    /** Writes the final automatic batch exactly once during server stop. */
    public static void autoExportOnStop(MinecraftServer server) {
        if (!Config.AUTO_EXPORT_ENABLED.get()) {
            return;
        }
        if (!FINAL_AUTO_EXPORT_WRITTEN.compareAndSet(false, true)) {
            return;
        }
        queueAutoExportBatch(server);
    }

    private static void queueAutoExportBatch(MinecraftServer server) {
        try {
            Path dir = exportsDir(server);
            MilestoneData data = MilestoneData.get(server);
            Instant now = Instant.now();
            ZoneId zone = Config.displayTimeZone();
            // Snapshot collection stays on the server thread (MilestoneData is server-thread confined).
            List<PlayerExportSnapshot> snapshots = collectAllPlayerSnapshots(data);
            Map<TargetKey, WorldFirstRecord> worldFirsts = new LinkedHashMap<>(data.worldFirsts());
            Set<TargetKey> defaultTargets = Tracker.defaultTargets();
            Function<String, String> resolver = MilestoneExport::resolveItemDisplayName;
            String jsonName = resolveSnapshotFileName(dir, "all-players.json", now, zone);
            String htmlName = resolveSnapshotFileName(dir, HtmlExportRenderer.ALL_PLAYERS_HTML_FILE, now, zone);
            String json = GSON.toJson(buildAllPlayersObject(snapshots, worldFirsts));
            String html = renderAllPlayersHtml(snapshots, defaultTargets, worldFirsts, resolver, now, zone);

            Runnable batch = () -> writeAutoExportBatchFiles(dir, jsonName, htmlName, json, html);
            if (!MilestoneIo.submit(MilestoneIoLogic.TaskKind.EXPORT, batch)) {
                batch.run();
            }
        } catch (RuntimeException e) {
            warnAutoExport(e);
        }
    }

    static PlayerExportSnapshot collectPlayerSnapshot(UUID uuid, @Nullable String playerName, @Nullable MilestoneData.PlayerData pd) {
        String safeName = playerName != null ? playerName : "";
        if (pd == null) {
            return new PlayerExportSnapshot(uuid, safeName, List.of(), Map.of());
        }
        return new PlayerExportSnapshot(
                uuid,
                safeName,
                List.copyOf(pd.targets),
                new LinkedHashMap<>(pd.records));
    }

    static List<PlayerExportSnapshot> collectAllPlayerSnapshots(MilestoneData data) {
        List<PlayerExportSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = entry.getValue();
            snapshots.add(collectPlayerSnapshot(entry.getKey(), pd != null ? pd.lastKnownName : "", pd));
        }
        return snapshots;
    }

    public static String exportPlayer(Path dir, UUID uuid, String playerName, MilestoneData.PlayerData pd) throws IOException {
        Files.createDirectories(dir);
        String fileName = sanitizePlayerFileName(playerName, uuid);
        Path target = dir.resolve(fileName).normalize();
        if (!target.getParent().equals(dir.normalize())) {
            throw new IOException("Invalid export target path");
        }

        PlayerExportSnapshot snapshot = collectPlayerSnapshot(uuid, playerName, pd);
        JsonObject root = buildPlayerObject(snapshot);
        root.addProperty("format", FORMAT_VERSION);
        writeJsonAtomically(dir, fileName, target, root);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    /** Export entry point used by commands that carry {@link ExportOptions}. */
    public static String exportPlayer(Path dir, UUID uuid, String playerName, MilestoneData.PlayerData pd,
                                      ExportOptions options, Instant now, ZoneId zone) throws IOException {
        if (!options.snapshot()) {
            return exportPlayer(dir, uuid, playerName, pd);
        }
        String fileName = resolveSnapshotFileName(dir, sanitizePlayerFileName(playerName, uuid), now, zone);
        PlayerExportSnapshot snapshot = collectPlayerSnapshot(uuid, playerName, pd);
        JsonObject root = buildPlayerObject(snapshot);
        root.addProperty("format", FORMAT_VERSION);
        writeExportContent(dir, fileName, GSON.toJson(root), options);
        return relativePath(fileName);
    }

    public static String exportAll(Path dir, MilestoneData data) throws IOException {
        Files.createDirectories(dir);
        String fileName = "all-players.json";
        Path target = dir.resolve(fileName).normalize();

        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        JsonArray playersArr = new JsonArray();
        for (PlayerExportSnapshot snapshot : collectAllPlayerSnapshots(data)) {
            playersArr.add(buildPlayerObject(snapshot));
        }
        root.add("players", playersArr);
        root.add("worldFirsts", buildWorldFirstsArray(data.worldFirsts()));

        writeJsonAtomically(dir, fileName, target, root);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    /** Export-all entry point used by commands that carry {@link ExportOptions}. */
    public static String exportAll(Path dir, MilestoneData data, ExportOptions options,
                                   Instant now, ZoneId zone) throws IOException {
        if (!options.snapshot()) {
            return exportAll(dir, data);
        }
        String fileName = resolveSnapshotFileName(dir, "all-players.json", now, zone);
        JsonObject root = buildAllPlayersObject(collectAllPlayerSnapshots(data), data.worldFirsts());
        writeExportContent(dir, fileName, GSON.toJson(root), options);
        return relativePath(fileName);
    }

    private static JsonArray buildWorldFirstsArray(Map<TargetKey, WorldFirstRecord> worldFirsts) {
        JsonArray arr = new JsonArray();
        for (Map.Entry<TargetKey, WorldFirstRecord> entry : worldFirsts.entrySet()) {
            TargetKey key = entry.getKey();
            WorldFirstRecord wf = entry.getValue();
            if (key == null || wf == null) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("key", key.toEncodedString());
            obj.addProperty("uuid", wf.playerUuid().toString());
            obj.addProperty("name", wf.playerName() != null ? wf.playerName() : "");
            obj.addProperty("ticks", wf.playTicks());
            obj.addProperty("day", wf.day());
            obj.addProperty("epoch", wf.epochMillis());
            arr.add(obj);
        }
        return arr;
    }

    private static JsonObject buildPlayerObject(PlayerExportSnapshot snapshot) {
        JsonObject root = new JsonObject();
        JsonObject playerObj = new JsonObject();
        playerObj.addProperty("uuid", snapshot.uuid().toString());
        playerObj.addProperty("name", snapshot.playerName());
        root.add("player", playerObj);

        JsonArray targetsArr = new JsonArray();
        for (TargetKey target : snapshot.personalTargets()) {
            if (target.count() > TargetKey.MIN_COUNT) {
                JsonObject obj = new JsonObject();
                obj.addProperty("item", target.itemId());
                obj.addProperty("count", target.count());
                targetsArr.add(obj);
            } else {
                targetsArr.add(target.itemId());
            }
        }
        root.add("targets", targetsArr);

        JsonObject recordsObj = new JsonObject();
        for (Map.Entry<TargetKey, MilestoneRecord> r : snapshot.records().entrySet()) {
            TargetKey key = r.getKey();
            MilestoneRecord rec = r.getValue();
            JsonObject recObj = new JsonObject();
            recObj.addProperty("ticks", rec.playTicks());
            recObj.addProperty("day", rec.day());
            recObj.addProperty("epoch", rec.epochMillis());
            if (key.count() > TargetKey.MIN_COUNT) {
                recObj.addProperty("count", key.count());
            }
            recordsObj.add(key.toEncodedString(), recObj);
        }
        root.add("records", recordsObj);
        return root;
    }

    // ------------------------------------------------------------- F3 HTML export

    public static String exportPlayerHtml(
            Path dir,
            UUID uuid,
            @Nullable String playerName,
            @Nullable MilestoneData.PlayerData pd,
            @Nullable MilestoneData data) throws IOException {
        Map<TargetKey, WorldFirstRecord> worldFirsts = data != null ? data.worldFirsts() : Map.of();
        return exportPlayerHtml(
                dir,
                uuid,
                playerName,
                pd,
                Tracker.defaultTargets(),
                worldFirsts,
                MilestoneExport::resolveItemDisplayName,
                Instant.now(),
                Config.displayTimeZone());
    }

    /** HTML entry point used by commands that carry {@link ExportOptions}. */
    public static String exportPlayerHtml(
            Path dir,
            UUID uuid,
            @Nullable String playerName,
            @Nullable MilestoneData.PlayerData pd,
            @Nullable MilestoneData data,
            ExportOptions options,
            Instant generatedAt,
            ZoneId zone) throws IOException {
        if (!options.snapshot()) {
            return exportPlayerHtml(dir, uuid, playerName, pd, data);
        }
        String fileName = resolveSnapshotFileName(dir, sanitizePlayerHtmlFileName(playerName, uuid),
                generatedAt, zone);
        Map<TargetKey, WorldFirstRecord> worldFirsts = data != null ? data.worldFirsts() : Map.of();
        PlayerExportSnapshot snapshot = collectPlayerSnapshot(uuid, playerName, pd);
        String html = renderPlayerHtml(snapshot, Tracker.defaultTargets(), worldFirsts,
                MilestoneExport::resolveItemDisplayName, generatedAt, zone);
        writeExportContent(dir, fileName, html, options);
        return relativePath(fileName);
    }

    static String exportPlayerHtml(
            Path dir,
            UUID uuid,
            @Nullable String playerName,
            @Nullable MilestoneData.PlayerData pd,
            Set<TargetKey> defaultTargets,
            Map<TargetKey, WorldFirstRecord> worldFirsts,
            Function<String, String> itemNameResolver,
            Instant generatedAt,
            ZoneId zone) throws IOException {
        Files.createDirectories(dir);
        String fileName = sanitizePlayerHtmlFileName(playerName, uuid);
        Path target = dir.resolve(fileName).normalize();
        if (!target.getParent().equals(dir.normalize())) {
            throw new IOException("Invalid export target path");
        }

        PlayerExportSnapshot snapshot = collectPlayerSnapshot(uuid, playerName, pd);
        String html = renderPlayerHtml(snapshot, defaultTargets, worldFirsts, itemNameResolver, generatedAt, zone);
        writeTextAtomically(dir, fileName, target, html);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    public static String exportAllHtml(Path dir, MilestoneData data) throws IOException {
        return exportAllHtml(
                dir,
                data,
                Tracker.defaultTargets(),
                MilestoneExport::resolveItemDisplayName,
                Instant.now(),
                Config.displayTimeZone());
    }

    /** HTML all-players entry point used by commands that carry {@link ExportOptions}. */
    public static String exportAllHtml(Path dir, MilestoneData data, ExportOptions options,
                                       Instant generatedAt, ZoneId zone) throws IOException {
        if (!options.snapshot()) {
            return exportAllHtml(dir, data);
        }
        String fileName = resolveSnapshotFileName(dir, HtmlExportRenderer.ALL_PLAYERS_HTML_FILE,
                generatedAt, zone);
        List<PlayerExportSnapshot> snapshots = collectAllPlayerSnapshots(data);
        String html = renderAllPlayersHtml(snapshots, Tracker.defaultTargets(), data.worldFirsts(),
                MilestoneExport::resolveItemDisplayName, generatedAt, zone);
        writeExportContent(dir, fileName, html, options);
        return relativePath(fileName);
    }

    static String exportAllHtml(
            Path dir,
            MilestoneData data,
            Set<TargetKey> defaultTargets,
            Function<String, String> itemNameResolver,
            Instant generatedAt,
            ZoneId zone) throws IOException {
        Files.createDirectories(dir);
        String fileName = HtmlExportRenderer.ALL_PLAYERS_HTML_FILE;
        Path target = dir.resolve(fileName).normalize();

        List<PlayerExportSnapshot> snapshots = collectAllPlayerSnapshots(data);
        String html = renderAllPlayersHtml(snapshots, defaultTargets, data.worldFirsts(), itemNameResolver, generatedAt, zone);
        writeTextAtomically(dir, fileName, target, html);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    static String renderPlayerHtml(
            PlayerExportSnapshot snapshot,
            Set<TargetKey> defaultTargets,
            Map<TargetKey, WorldFirstRecord> worldFirsts,
            Function<String, String> itemNameResolver,
            Instant generatedAt,
            ZoneId zone) {
        HtmlExportRenderer.PlayerReport report =
                toHtmlPlayerReport(snapshot, defaultTargets, worldFirsts, itemNameResolver, zone);
        return HtmlExportRenderer.renderSinglePlayer(report, generatedAt, zone);
    }

    static String renderAllPlayersHtml(
            List<PlayerExportSnapshot> snapshots,
            Set<TargetKey> defaultTargets,
            Map<TargetKey, WorldFirstRecord> worldFirsts,
            Function<String, String> itemNameResolver,
            Instant generatedAt,
            ZoneId zone) {
        List<PlayerExportSnapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort((a, b) -> {
            String na = displayNameForSnapshot(a);
            String nb = displayNameForSnapshot(b);
            int c = String.CASE_INSENSITIVE_ORDER.compare(na, nb);
            if (c != 0) {
                return c;
            }
            c = na.compareTo(nb);
            return c != 0 ? c : a.uuid().compareTo(b.uuid());
        });
        List<HtmlExportRenderer.PlayerReport> reports = new ArrayList<>(sorted.size());
        for (PlayerExportSnapshot snapshot : sorted) {
            reports.add(toHtmlPlayerReport(snapshot, defaultTargets, worldFirsts, itemNameResolver, zone));
        }
        return HtmlExportRenderer.renderAllPlayers(reports, generatedAt, zone);
    }

    private static String displayNameForSnapshot(PlayerExportSnapshot snapshot) {
        return !snapshot.playerName().isBlank() ? snapshot.playerName() : snapshot.uuid().toString();
    }

    private static HtmlExportRenderer.PlayerReport toHtmlPlayerReport(
            PlayerExportSnapshot snapshot,
            Set<TargetKey> defaultTargets,
            @Nullable Map<TargetKey, WorldFirstRecord> worldFirsts,
            @Nullable Function<String, String> itemNameResolver,
            ZoneId zone) {
        String displayName = displayNameForSnapshot(snapshot);
        HtmlExportRenderer.CompletionSummary completion = HtmlExportRenderer.computeCompletion(
                snapshot.personalTargets(), defaultTargets, snapshot.records().keySet());

        List<Map.Entry<TargetKey, MilestoneRecord>> entries = new ArrayList<>(snapshot.records().entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<TargetKey, MilestoneRecord> e) -> e.getValue().playTicks())
                .thenComparing(Map.Entry::getKey));

        List<HtmlExportRenderer.RecordRow> rows = new ArrayList<>(entries.size());
        for (Map.Entry<TargetKey, MilestoneRecord> entry : entries) {
            TargetKey key = entry.getKey();
            MilestoneRecord rec = entry.getValue();
            String itemName = itemNameResolver != null ? itemNameResolver.apply(key.itemId()) : "";
            String playtime = Playtime.format(rec.playTicks());
            String dayText = rec.hasDay() ? ("Day " + (rec.day() + 1L)) : "";
            String dateText = rec.hasEpoch() ? Playtime.formatDate(rec.epochMillis(), zone) : "";
            WorldFirstRecord wf = worldFirsts != null ? worldFirsts.get(key) : null;
            boolean isWorldFirst = wf != null && snapshot.uuid().equals(wf.playerUuid());
            rows.add(new HtmlExportRenderer.RecordRow(
                    itemName, key.itemId(), key.count(), playtime, dayText, dateText, isWorldFirst));
        }
        return new HtmlExportRenderer.PlayerReport(snapshot.uuid(), displayName, completion, rows);
    }

    private static String resolveItemDisplayName(String itemId) {
        // F8b: tag names always resolve (translation, or the "#ns:path" fallback), even for tags
        // that are currently missing or empty.
        if (!TargetKey.isTagId(itemId) && !MilestoneService.isValidItemId(itemId)) {
            return "";
        }
        return MilestoneService.itemName(itemId).getString();
    }

    // ------------------------------------------------------------- F26 CSV export

    public static String exportPlayerCsv(
            Path dir,
            UUID uuid,
            @Nullable String playerName,
            @Nullable MilestoneData.PlayerData pd,
            @Nullable MilestoneData data) throws IOException {
        Map<TargetKey, WorldFirstRecord> worldFirsts = data != null ? data.worldFirsts() : Map.of();
        return exportPlayerCsv(
                dir,
                uuid,
                playerName,
                pd,
                worldFirsts,
                MilestoneExport::resolveItemDisplayName,
                Config.displayTimeZone());
    }

    /** CSV entry point used by commands that carry {@link ExportOptions}. */
    public static String exportPlayerCsv(
            Path dir,
            UUID uuid,
            @Nullable String playerName,
            @Nullable MilestoneData.PlayerData pd,
            @Nullable MilestoneData data,
            ExportOptions options,
            ZoneId zone) throws IOException {
        if (!options.snapshot()) {
            return exportPlayerCsv(dir, uuid, playerName, pd, data);
        }
        String fileName = resolveSnapshotFileName(dir, sanitizePlayerCsvFileName(playerName, uuid),
                Instant.now(), zone);
        Map<TargetKey, WorldFirstRecord> worldFirsts = data != null ? data.worldFirsts() : Map.of();
        PlayerExportSnapshot snapshot = collectPlayerSnapshot(uuid, playerName, pd);
        String csv = renderPlayerCsv(snapshot, worldFirsts, MilestoneExport::resolveItemDisplayName, zone);
        writeExportContent(dir, fileName, csv, options);
        return relativePath(fileName);
    }

    static String exportPlayerCsv(
            Path dir,
            UUID uuid,
            @Nullable String playerName,
            @Nullable MilestoneData.PlayerData pd,
            Map<TargetKey, WorldFirstRecord> worldFirsts,
            Function<String, String> itemNameResolver,
            ZoneId zone) throws IOException {
        Files.createDirectories(dir);
        String fileName = sanitizePlayerCsvFileName(playerName, uuid);
        Path target = dir.resolve(fileName).normalize();
        if (!target.getParent().equals(dir.normalize())) {
            throw new IOException("Invalid export target path");
        }

        PlayerExportSnapshot snapshot = collectPlayerSnapshot(uuid, playerName, pd);
        String csv = renderPlayerCsv(snapshot, worldFirsts, itemNameResolver, zone);
        writeTextAtomically(dir, fileName, target, csv);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    public static String exportAllCsv(Path dir, MilestoneData data) throws IOException {
        return exportAllCsv(
                dir,
                data,
                MilestoneExport::resolveItemDisplayName,
                Config.displayTimeZone());
    }

    /** CSV all-players entry point used by commands that carry {@link ExportOptions}. */
    public static String exportAllCsv(Path dir, MilestoneData data, ExportOptions options, ZoneId zone)
            throws IOException {
        if (!options.snapshot()) {
            return exportAllCsv(dir, data);
        }
        String fileName = resolveSnapshotFileName(dir, CsvExportRenderer.ALL_PLAYERS_CSV_FILE,
                Instant.now(), zone);
        List<PlayerExportSnapshot> snapshots = collectAllPlayerSnapshots(data);
        String csv = renderAllPlayersCsv(snapshots, data.worldFirsts(),
                MilestoneExport::resolveItemDisplayName, zone);
        writeExportContent(dir, fileName, csv, options);
        return relativePath(fileName);
    }

    static String exportAllCsv(
            Path dir,
            MilestoneData data,
            Function<String, String> itemNameResolver,
            ZoneId zone) throws IOException {
        Files.createDirectories(dir);
        String fileName = CsvExportRenderer.ALL_PLAYERS_CSV_FILE;
        Path target = dir.resolve(fileName).normalize();

        List<PlayerExportSnapshot> snapshots = collectAllPlayerSnapshots(data);
        String csv = renderAllPlayersCsv(snapshots, data.worldFirsts(), itemNameResolver, zone);
        writeTextAtomically(dir, fileName, target, csv);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    static String renderPlayerCsv(
            PlayerExportSnapshot snapshot,
            @Nullable Map<TargetKey, WorldFirstRecord> worldFirsts,
            @Nullable Function<String, String> itemNameResolver,
            ZoneId zone) {
        List<CsvExportRenderer.Row> rows = new ArrayList<>();
        appendCsvRowsForSnapshot(rows, snapshot, worldFirsts, itemNameResolver, zone);
        return CsvExportRenderer.render(rows);
    }

    static String renderAllPlayersCsv(
            List<PlayerExportSnapshot> snapshots,
            @Nullable Map<TargetKey, WorldFirstRecord> worldFirsts,
            @Nullable Function<String, String> itemNameResolver,
            ZoneId zone) {
        List<PlayerExportSnapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort((a, b) -> {
            String na = a.playerName();
            String nb = b.playerName();
            int c = String.CASE_INSENSITIVE_ORDER.compare(na, nb);
            if (c != 0) {
                return c;
            }
            c = na.compareTo(nb);
            return c != 0 ? c : a.uuid().compareTo(b.uuid());
        });
        List<CsvExportRenderer.Row> rows = new ArrayList<>();
        for (PlayerExportSnapshot snapshot : sorted) {
            appendCsvRowsForSnapshot(rows, snapshot, worldFirsts, itemNameResolver, zone);
        }
        return CsvExportRenderer.render(rows);
    }

    private static void appendCsvRowsForSnapshot(
            List<CsvExportRenderer.Row> out,
            PlayerExportSnapshot snapshot,
            @Nullable Map<TargetKey, WorldFirstRecord> worldFirsts,
            @Nullable Function<String, String> itemNameResolver,
            ZoneId zone) {
        if (snapshot.records().isEmpty()) {
            return;
        }
        List<Map.Entry<TargetKey, MilestoneRecord>> entries = new ArrayList<>(snapshot.records().entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<TargetKey, MilestoneRecord> e) -> e.getValue().playTicks())
                .thenComparing(Map.Entry::getKey));

        String uuidStr = snapshot.uuid().toString();
        String playerName = snapshot.playerName();
        for (Map.Entry<TargetKey, MilestoneRecord> entry : entries) {
            TargetKey key = entry.getKey();
            MilestoneRecord rec = entry.getValue();
            String itemName = itemNameResolver != null ? itemNameResolver.apply(key.itemId()) : "";
            String playtime = Playtime.format(rec.playTicks());
            Long displayDay = rec.hasDay() ? (rec.day() + 1L) : null;
            Long epochMillis = rec.hasEpoch() ? rec.epochMillis() : null;
            String dateText = rec.hasEpoch() ? Playtime.formatDate(rec.epochMillis(), zone) : "";
            WorldFirstRecord wf = worldFirsts != null ? worldFirsts.get(key) : null;
            boolean isWorldFirst = wf != null && snapshot.uuid().equals(wf.playerUuid());
            out.add(new CsvExportRenderer.Row(
                    uuidStr,
                    playerName,
                    key.itemId(),
                    key.count(),
                    itemName,
                    rec.playTicks(),
                    playtime,
                    displayDay,
                    epochMillis,
                    dateText,
                    isWorldFirst));
        }
    }

    @Nullable
    private static Integer parseStrictJsonInt(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive prim = el.getAsJsonPrimitive();
        if (!prim.isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(prim.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    @Nullable
    private static Long parseStrictJsonLong(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive prim = el.getAsJsonPrimitive();
        if (!prim.isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(prim.getAsString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static int parseStrictJsonCount(JsonElement countEl) {
        if (countEl == null || !countEl.isJsonPrimitive()) {
            return -1;
        }
        JsonPrimitive prim = countEl.getAsJsonPrimitive();
        if (!prim.isNumber()) {
            return -1;
        }
        return TargetKey.tryParseCount(prim.getAsString());
    }

    private static JsonObject readJsonObject(Path file, long maxBytes) throws IOException, IllegalArgumentException {
        if (Files.size(file) > maxBytes) {
            throw new IOException("Import file exceeds " + maxBytes + " bytes");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("invalid_format");
            }
            return parsed.getAsJsonObject();
        } catch (IOException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_format", e);
        }
    }

    public static ImportPayload parseSinglePlayerExport(Path file) throws IOException, IllegalArgumentException {
        JsonObject root = readJsonObject(file, MAX_IMPORT_BYTES);

        Integer format = parseStrictJsonInt(root.get("format"));
        if (format == null || format != FORMAT_VERSION) {
            throw new IllegalArgumentException("invalid_format");
        }
        if (root.has("players") && !root.has("targets") && !root.has("records")) {
            throw new IllegalArgumentException("invalid_format");
        }

        return parsePlayerImportObject(root, String.valueOf(file.getFileName()));
    }

    public static AllPlayersImportPayload parseAllPlayersExport(Path file) throws IOException, IllegalArgumentException {
        return parseAllPlayersExport(file, MAX_IMPORT_ALL_BYTES);
    }

    static AllPlayersImportPayload parseAllPlayersExport(Path file, long maxBytes)
            throws IOException, IllegalArgumentException {
        JsonObject root = readJsonObject(file, maxBytes);

        Integer format = parseStrictJsonInt(root.get("format"));
        if (format == null || format != FORMAT_VERSION) {
            throw new IllegalArgumentException("invalid_format");
        }
        if (!root.has("players") || !root.get("players").isJsonArray()) {
            throw new IllegalArgumentException("invalid_format");
        }

        String sourceName = String.valueOf(file.getFileName());
        List<ImportPayload> players = new ArrayList<>();
        int skippedEntries = 0;
        for (JsonElement el : root.getAsJsonArray("players")) {
            if (!el.isJsonObject()) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping non-object player entry in export file {}",
                        sourceName);
                skippedEntries++;
                continue;
            }
            ImportPayload entry;
            try {
                entry = parsePlayerImportObject(el.getAsJsonObject(), sourceName);
            } catch (IllegalArgumentException e) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid player entry in export file {}",
                        sourceName);
                skippedEntries++;
                continue;
            }
            if (entry.ownerUuid() == null) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping player entry without UUID in export file {}",
                        sourceName);
                skippedEntries++;
                continue;
            }
            players.add(entry);
        }

        // F31: preserve optional top-level worldFirsts array for parsing and merging.
        JsonArray rawWorldFirsts = null;
        if (root.has("worldFirsts") && !root.get("worldFirsts").isJsonNull()) {
            if (root.get("worldFirsts").isJsonArray()) {
                rawWorldFirsts = root.getAsJsonArray("worldFirsts");
            } else {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping non-array worldFirsts field in export file {}",
                        sourceName);
                skippedEntries++;
            }
        }

        return new AllPlayersImportPayload(players, skippedEntries, rawWorldFirsts);
    }

    /**
     * Parses a single-player export JSON object ({@code player} / {@code targets} / {@code records})
     * into an {@link ImportPayload}. Shared by single-player {@code import} and F22 {@code importall}.
     */
    static ImportPayload parsePlayerImportObject(JsonObject root, String sourceName) {
        UUID ownerUuid = null;
        String ownerName = null;
        if (root.has("player")) {
            if (!root.get("player").isJsonObject()) {
                throw new IllegalArgumentException("invalid_format");
            }
            JsonObject playerObj = root.getAsJsonObject("player");
            if (playerObj.has("uuid")) {
                if (!playerObj.get("uuid").isJsonPrimitive() || !playerObj.get("uuid").getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("invalid_format");
                }
                String rawUuid = playerObj.get("uuid").getAsString().trim();
                if (!rawUuid.isEmpty()) {
                    try {
                        ownerUuid = UUID.fromString(rawUuid);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("invalid_format", e);
                    }
                }
            }
            if (playerObj.has("name")
                    && playerObj.get("name").isJsonPrimitive()
                    && playerObj.get("name").getAsJsonPrimitive().isString()) {
                ownerName = playerObj.get("name").getAsString();
            }
        }

        List<TargetKey> targets = new ArrayList<>();
        if (root.has("targets")) {
            if (!root.get("targets").isJsonArray()) {
                throw new IllegalArgumentException("invalid_format");
            }
            for (JsonElement el : root.getAsJsonArray("targets")) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    String rawTarget = el.getAsString();
                    TargetKey normalized = TargetKey.tryParseNormalized(rawTarget, false, true);
                    if (normalized != null) {
                        targets.add(normalized);
                    } else {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid target '{}' in export file {}",
                                rawTarget, sourceName);
                    }
                } else if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    if (!obj.has("item") || !obj.get("item").isJsonPrimitive() || !obj.get("item").getAsJsonPrimitive().isString()) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid target object in export file {}",
                                sourceName);
                        continue;
                    }
                    String rawItem = obj.get("item").getAsString().trim();
                    String normalizedId = TargetKey.normalizeItemId(rawItem, false);
                    if (normalizedId == null || "minecraft:air".equals(normalizedId)) {
                        continue;
                    }
                    int count = TargetKey.MIN_COUNT;
                    if (obj.has("count")) {
                        count = parseStrictJsonCount(obj.get("count"));
                        if (count < TargetKey.MIN_COUNT) {
                            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping target '{}' with invalid count in export file {}",
                                    rawItem, sourceName);
                            continue;
                        }
                    }
                    TargetKey normalized = TargetKey.normalize(normalizedId, count, false, true);
                    if (normalized != null) {
                        targets.add(normalized);
                    }
                }
            }
        }

        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();
        if (root.has("records")) {
            if (!root.get("records").isJsonObject()) {
                throw new IllegalArgumentException("invalid_format");
            }
            JsonObject recordsObj = root.getAsJsonObject("records");
            for (Map.Entry<String, JsonElement> entry : recordsObj.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                String rawKey = entry.getKey();
                TargetKey keyFromMap = TargetKey.tryParseNormalized(rawKey, false, true);
                if (keyFromMap == null) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record key '{}' in export file {}",
                            rawKey, sourceName);
                    continue;
                }
                JsonObject recObj = entry.getValue().getAsJsonObject();
                Integer ticks = parseStrictJsonInt(recObj.get("ticks"));
                if (ticks == null || ticks < 0) {
                    continue;
                }
                int count = keyFromMap.count();
                boolean keyHadExplicitCount = rawKey.trim().lastIndexOf('#') >= 1;
                if (recObj.has("count")) {
                    int parsedCount = parseStrictJsonCount(recObj.get("count"));
                    if (parsedCount < TargetKey.MIN_COUNT || (keyHadExplicitCount && parsedCount != count)) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record '{}' with invalid or conflicting count in export file {}",
                                rawKey, sourceName);
                        continue;
                    }
                    count = parsedCount;
                }
                Long parsedDay = recObj.has("day") ? parseStrictJsonLong(recObj.get("day")) : null;
                long day = parsedDay != null ? parsedDay : MilestoneRecord.UNKNOWN_DAY;
                Long parsedEpoch = recObj.has("epoch") ? parseStrictJsonLong(recObj.get("epoch")) : null;
                long epoch = parsedEpoch != null ? parsedEpoch : MilestoneRecord.UNKNOWN_EPOCH;
                TargetKey normalizedKey = TargetKey.normalize(keyFromMap.itemId(), count, false, true);
                if (normalizedKey == null) {
                    continue;
                }
                MilestoneRecord incoming = new MilestoneRecord(ticks, day, epoch);
                MilestoneRecord existing = records.get(normalizedKey);
                if (existing == null || incoming.playTicks() < existing.playTicks()) {
                    records.put(normalizedKey, incoming);
                }
            }
        }

        return new ImportPayload(ownerUuid, ownerName, targets, records);
    }

    public static ImportMergeStats mergeInto(MilestoneData data, UUID uuid, ImportPayload payload) {
        MilestoneData.PlayerData pd = data.player(uuid);
        int addedTargets = 0;
        for (TargetKey target : payload.targets()) {
            if (pd.targets.contains(target)) {
                continue;
            }
            if (pd.targets.size() >= MilestoneService.MAX_PERSONAL_TARGETS) {
                break;
            }
            if (pd.targets.add(target)) {
                addedTargets++;
            }
        }
        int updatedRecords = 0;
        for (Map.Entry<TargetKey, MilestoneRecord> entry : payload.records().entrySet()) {
            TargetKey key = entry.getKey();
            MilestoneRecord incoming = entry.getValue();
            MilestoneRecord existing = pd.records.get(key);
            if (existing == null || incoming.playTicks() < existing.playTicks()) {
                pd.records.put(key, incoming);
                // The imported record supersedes any pending progress alert for this key.
                pd.progressNotified.remove(key);
                updatedRecords++;
            }
        }
        if (addedTargets > 0 || updatedRecords > 0) {
            data.setDirty();
        }
        return new ImportMergeStats(addedTargets, updatedRecords);
    }

    /**
     * Parses the already-loaded import payload into an immutable merge plan. This is the only part
     * of {@code importall} that touches JSON/world-first parsing and therefore runs on the
     * {@code itemmilestonelog-io} executor (F-140-3).
     */
    public static MergePlan buildMergePlan(AllPlayersImportPayload payload) {
        Map<UUID, List<String>> namesByPlayer = new LinkedHashMap<>();
        Map<UUID, List<TargetKey>> targetsByPlayer = new LinkedHashMap<>();
        Map<UUID, List<RecordUpdate>> recordsByPlayer = new LinkedHashMap<>();

        int skippedEntries = payload.skippedEntries();
        for (ImportPayload entry : payload.players()) {
            UUID uuid = entry.ownerUuid();
            if (uuid == null) {
                // Match the synchronous mergeAllInto behavior: null owner UUIDs count as skipped.
                skippedEntries++;
                continue;
            }
            namesByPlayer.computeIfAbsent(uuid, ignored -> new ArrayList<>());
            targetsByPlayer.computeIfAbsent(uuid, ignored -> new ArrayList<>()).addAll(entry.targets());
            List<RecordUpdate> updates = recordsByPlayer.computeIfAbsent(uuid, ignored -> new ArrayList<>());
            for (Map.Entry<TargetKey, MilestoneRecord> record : entry.records().entrySet()) {
                updates.add(new RecordUpdate(record.getKey(), record.getValue()));
            }
            String ownerName = entry.ownerName();
            if (ownerName != null && !ownerName.isBlank()) {
                namesByPlayer.get(uuid).add(ownerName);
            }
        }

        List<PlayerMergePlan> players = new ArrayList<>(namesByPlayer.size());
        for (UUID uuid : namesByPlayer.keySet()) {
            players.add(new PlayerMergePlan(uuid, namesByPlayer.get(uuid),
                    targetsByPlayer.get(uuid), recordsByPlayer.get(uuid)));
        }

        List<WorldFirstRecord> worldFirsts = null;
        int worldFirstSkippedEntries = 0;
        JsonArray rawWorldFirsts = payload.rawWorldFirsts();
        if (rawWorldFirsts != null) {
            worldFirsts = new ArrayList<>(rawWorldFirsts.size());
            for (JsonElement element : rawWorldFirsts) {
                WorldFirstRecord parsed = parseWorldFirstElement(element);
                if (parsed == null) {
                    worldFirstSkippedEntries++;
                } else {
                    worldFirsts.add(parsed);
                }
            }
        }

        return new MergePlan(players, skippedEntries, worldFirsts, worldFirstSkippedEntries);
    }

    /**
     * Applies a parsed merge plan to the live data. Must run on the server thread because
     * {@link MilestoneData} is server-thread confined.
     */
    public static ImportAllMergeStats applyMergePlan(MilestoneData data, MergePlan plan) {
        int addedTargets = 0;
        int updatedRecords = 0;

        for (PlayerMergePlan player : plan.players()) {
            UUID uuid = player.uuid();
            MilestoneData.PlayerData pd = data.player(uuid);
            for (String ownerName : player.ownerNames()) {
                if (pd.lastKnownName == null || pd.lastKnownName.isEmpty()) {
                    pd.lastKnownName = ownerName;
                    data.setDirty();
                }
            }

            int addedForPlayer = 0;
            for (TargetKey target : player.targets()) {
                if (pd.targets.contains(target)) {
                    continue;
                }
                if (pd.targets.size() >= MilestoneService.MAX_PERSONAL_TARGETS) {
                    break;
                }
                if (pd.targets.add(target)) {
                    addedForPlayer++;
                }
            }
            int updatedForPlayer = 0;
            for (RecordUpdate update : player.recordUpdates()) {
                MilestoneRecord existing = pd.records.get(update.key());
                if (existing == null || update.record().playTicks() < existing.playTicks()) {
                    pd.records.put(update.key(), update.record());
                    // The imported record supersedes any pending progress alert for this key.
                    pd.progressNotified.remove(update.key());
                    updatedForPlayer++;
                }
            }
            if (addedForPlayer > 0 || updatedForPlayer > 0) {
                data.setDirty();
            }
            addedTargets += addedForPlayer;
            updatedRecords += updatedForPlayer;
        }

        int skippedEntries = plan.skippedEntries();
        int updatedWorldFirsts = 0;
        if (plan.worldFirsts() != null) {
            WorldFirstImportLogic.MergeResult<TargetKey, WorldFirstRecord> merged =
                    mergeWorldFirsts(data.worldFirsts(), plan.worldFirsts(), plan.worldFirstSkippedEntries());
            skippedEntries += merged.skippedCount();
            if (merged.updatedCount() > 0) {
                data.replaceWorldFirsts(merged.worldFirsts());
                updatedWorldFirsts = merged.updatedCount();
            }
        }

        return new ImportAllMergeStats(
                plan.players().size(),
                addedTargets,
                updatedRecords,
                updatedWorldFirsts,
                skippedEntries);
    }

    /**
     * Synchronous merge used by the single-player {@code import} command and by the importall
     * synchronous fallback. Kept as the canonical behavior; {@link #buildMergePlan} +
     * {@link #applyMergePlan} are the asynchronous split of the same rules.
     */
    public static ImportAllMergeStats mergeAllInto(MilestoneData data, AllPlayersImportPayload payload) {
        return applyMergePlan(data, buildMergePlan(payload));
    }

    /**
     * Parses a single JSON element from {@code all-players.json}'s {@code worldFirsts} array (F31).
     * Returns {@code null} (and logs a warning) if the element is malformed or unparseable.
     */
    @Nullable
    static WorldFirstRecord parseWorldFirstElement(@Nullable JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping non-object worldFirst entry during importall");
            return null;
        }
        JsonObject obj = el.getAsJsonObject();

        JsonElement keyEl = obj.get("key");
        if (keyEl == null || !keyEl.isJsonPrimitive() || !keyEl.getAsJsonPrimitive().isString()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst entry with missing or non-string key during importall");
            return null;
        }
        String rawKey = keyEl.getAsString();
        TargetKey key = TargetKey.tryParseNormalized(rawKey, false, true);
        if (key == null) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst entry with invalid key '{}' during importall", rawKey);
            return null;
        }

        JsonElement uuidEl = obj.get("uuid");
        if (uuidEl == null || !uuidEl.isJsonPrimitive() || !uuidEl.getAsJsonPrimitive().isString()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with missing or non-string uuid during importall",
                    key.toEncodedString());
            return null;
        }
        String rawUuid = uuidEl.getAsString().trim();
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with invalid uuid '{}' during importall",
                    key.toEncodedString(), rawUuid);
            return null;
        }

        String playerName = "";
        if (obj.has("name") && !obj.get("name").isJsonNull()) {
            JsonElement nameEl = obj.get("name");
            if (!nameEl.isJsonPrimitive() || !nameEl.getAsJsonPrimitive().isString()) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with non-string name during importall",
                        key.toEncodedString());
                return null;
            }
            playerName = nameEl.getAsString();
        }

        Integer ticks = parseStrictJsonInt(obj.get("ticks"));
        if (ticks == null || ticks < 0) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with missing or invalid ticks during importall",
                    key.toEncodedString());
            return null;
        }

        long day = MilestoneRecord.UNKNOWN_DAY;
        if (obj.has("day") && !obj.get("day").isJsonNull()) {
            Long parsedDay = parseStrictJsonLong(obj.get("day"));
            if (parsedDay == null || parsedDay < MilestoneRecord.UNKNOWN_DAY) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with invalid day during importall",
                        key.toEncodedString());
                return null;
            }
            day = parsedDay;
        }

        long epoch = MilestoneRecord.UNKNOWN_EPOCH;
        if (obj.has("epoch") && !obj.get("epoch").isJsonNull()) {
            Long parsedEpoch = parseStrictJsonLong(obj.get("epoch"));
            if (parsedEpoch == null || parsedEpoch < MilestoneRecord.UNKNOWN_EPOCH) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with invalid epoch during importall",
                        key.toEncodedString());
                return null;
            }
            epoch = parsedEpoch;
        }

        return new WorldFirstRecord(
                key.itemId(),
                key.count(),
                playerUuid,
                playerName,
                ticks,
                day,
                epoch);
    }

    /**
     * Pure function: parses {@code rawWorldFirsts} and merges valid entries into {@code current}
     * without mutating {@code current} (F31).
     */
    static WorldFirstImportLogic.MergeResult<TargetKey, WorldFirstRecord> parseAndMergeWorldFirsts(
            Map<TargetKey, WorldFirstRecord> current,
            @Nullable JsonArray rawWorldFirsts) {
        if (rawWorldFirsts == null || rawWorldFirsts.isEmpty()) {
            return new WorldFirstImportLogic.MergeResult<>(current != null ? current : Map.of(), 0, 0);
        }
        List<WorldFirstRecord> parsed = new ArrayList<>(rawWorldFirsts.size());
        int skipped = 0;
        for (JsonElement el : rawWorldFirsts) {
            WorldFirstRecord rec = parseWorldFirstElement(el);
            if (rec == null) {
                skipped++;
            } else {
                parsed.add(rec);
            }
        }
        return mergeWorldFirsts(current, parsed, skipped);
    }

    /**
     * Pure function: merges already-parsed world-first entries into {@code current} without mutating
     * {@code current} (F31).
     */
    static WorldFirstImportLogic.MergeResult<TargetKey, WorldFirstRecord> mergeWorldFirsts(
            Map<TargetKey, WorldFirstRecord> current,
            List<WorldFirstRecord> incoming,
            int preSkippedCount) {
        return WorldFirstImportLogic.merge(
                current,
                incoming,
                preSkippedCount,
                WorldFirstRecord::targetKey,
                WorldFirstRecord::epochMillis);
    }

}
