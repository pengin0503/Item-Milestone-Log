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
import net.minecraft.resources.ResourceLocation;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JSON export and import helpers for F6 (extended with quantities in Q7).
 */
public final class MilestoneExport {
    public static final int FORMAT_VERSION = 1;
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record ImportPayload(@Nullable UUID ownerUuid, List<TargetKey> targets, Map<TargetKey, MilestoneRecord> records) {
        public ImportPayload(List<TargetKey> targets, Map<TargetKey, MilestoneRecord> records) {
            this(null, targets, records);
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

    public static String sanitizePlayerFileName(String playerName) {
        String base = (playerName == null || playerName.isBlank()) ? "player" : playerName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (base.contains("..")) {
            base = base.replace("..", "_");
        }
        return base + ".json";
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
                    .filter(name -> name.endsWith(".json") && isSafeFileName(name))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not list exports directory {}: {}", dir, e.getMessage());
        }
        return names;
    }

    private static void writeJsonAtomically(Path dir, String prefix, Path target, JsonObject root) throws IOException {
        Path temp = Files.createTempFile(dir, prefix + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
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

    public static String exportPlayer(Path dir, UUID uuid, String playerName, MilestoneData.PlayerData pd) throws IOException {
        Files.createDirectories(dir);
        String fileName = sanitizePlayerFileName(playerName);
        Path target = dir.resolve(fileName).normalize();
        if (!target.getParent().equals(dir.normalize())) {
            throw new IOException("Invalid export target path");
        }

        JsonObject root = buildPlayerObject(uuid, playerName, pd);
        root.addProperty("format", FORMAT_VERSION);
        writeJsonAtomically(dir, fileName, target, root);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    public static String exportAll(Path dir, MilestoneData data) throws IOException {
        Files.createDirectories(dir);
        String fileName = "all-players.json";
        Path target = dir.resolve(fileName).normalize();

        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        JsonArray playersArr = new JsonArray();
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = entry.getValue();
            playersArr.add(buildPlayerObject(entry.getKey(), pd.lastKnownName, pd));
        }
        root.add("players", playersArr);

        writeJsonAtomically(dir, fileName, target, root);
        return ItemMilestoneLog.MOD_ID + "/exports/" + fileName;
    }

    private static JsonObject buildPlayerObject(UUID uuid, String playerName, MilestoneData.PlayerData pd) {
        JsonObject root = new JsonObject();
        JsonObject playerObj = new JsonObject();
        playerObj.addProperty("uuid", uuid.toString());
        playerObj.addProperty("name", playerName != null ? playerName : "");
        root.add("player", playerObj);

        JsonArray targetsArr = new JsonArray();
        if (pd != null) {
            for (TargetKey target : pd.targets) {
                if (target.count() > TargetKey.MIN_COUNT) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("item", target.itemId());
                    obj.addProperty("count", target.count());
                    targetsArr.add(obj);
                } else {
                    targetsArr.add(target.itemId());
                }
            }
        }
        root.add("targets", targetsArr);

        JsonObject recordsObj = new JsonObject();
        if (pd != null) {
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
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
        }
        root.add("records", recordsObj);
        return root;
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

    public static ImportPayload parseSinglePlayerExport(Path file) throws IOException, IllegalArgumentException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("invalid_format");
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_format", e);
        }

        Integer format = parseStrictJsonInt(root.get("format"));
        if (format == null || format != FORMAT_VERSION) {
            throw new IllegalArgumentException("invalid_format");
        }
        if (root.has("players") && !root.has("targets") && !root.has("records")) {
            throw new IllegalArgumentException("invalid_format");
        }

        UUID ownerUuid = null;
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
        }

        List<TargetKey> targets = new ArrayList<>();
        if (root.has("targets")) {
            if (!root.get("targets").isJsonArray()) {
                throw new IllegalArgumentException("invalid_format");
            }
            for (JsonElement el : root.getAsJsonArray("targets")) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    String rawTarget = el.getAsString();
                    TargetKey parsedKey = TargetKey.tryParseEncoded(rawTarget);
                    if (parsedKey == null) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid target '{}' in export file {}",
                                rawTarget, file.getFileName());
                        continue;
                    }
                    ResourceLocation rl = ResourceLocation.tryParse(parsedKey.itemId());
                    if (rl != null && !"minecraft:air".equals(rl.toString())) {
                        targets.add(new TargetKey(rl.toString(), parsedKey.count()));
                    }
                } else if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    if (!obj.has("item") || !obj.get("item").isJsonPrimitive() || !obj.get("item").getAsJsonPrimitive().isString()) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid target object in export file {}",
                                file.getFileName());
                        continue;
                    }
                    String rawItem = obj.get("item").getAsString().trim();
                    ResourceLocation rl = ResourceLocation.tryParse(rawItem);
                    if (rl == null || "minecraft:air".equals(rl.toString())) {
                        continue;
                    }
                    int count = TargetKey.MIN_COUNT;
                    if (obj.has("count")) {
                        count = parseStrictJsonCount(obj.get("count"));
                        if (count < TargetKey.MIN_COUNT) {
                            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping target '{}' with invalid count in export file {}",
                                    rawItem, file.getFileName());
                            continue;
                        }
                    }
                    targets.add(new TargetKey(rl.toString(), count));
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
                TargetKey keyFromMap = TargetKey.tryParseEncoded(rawKey);
                if (keyFromMap == null) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record key '{}' in export file {}",
                            rawKey, file.getFileName());
                    continue;
                }
                ResourceLocation rl = ResourceLocation.tryParse(keyFromMap.itemId());
                if (rl == null || "minecraft:air".equals(rl.toString())) {
                    continue;
                }
                JsonObject recObj = entry.getValue().getAsJsonObject();
                Integer ticks = parseStrictJsonInt(recObj.get("ticks"));
                if (ticks == null || ticks < 0) {
                    continue;
                }
                int count = keyFromMap.count();
                boolean keyHadExplicitCount = rawKey.indexOf('#') >= 0;
                if (recObj.has("count")) {
                    int parsedCount = parseStrictJsonCount(recObj.get("count"));
                    if (parsedCount < TargetKey.MIN_COUNT || (keyHadExplicitCount && parsedCount != count)) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record '{}' with invalid or conflicting count in export file {}",
                                rawKey, file.getFileName());
                        continue;
                    }
                    count = parsedCount;
                }
                Long parsedDay = recObj.has("day") ? parseStrictJsonLong(recObj.get("day")) : null;
                long day = parsedDay != null ? parsedDay : MilestoneRecord.UNKNOWN_DAY;
                Long parsedEpoch = recObj.has("epoch") ? parseStrictJsonLong(recObj.get("epoch")) : null;
                long epoch = parsedEpoch != null ? parsedEpoch : MilestoneRecord.UNKNOWN_EPOCH;
                TargetKey normalizedKey = new TargetKey(rl.toString(), count);
                MilestoneRecord incoming = new MilestoneRecord(ticks, day, epoch);
                MilestoneRecord existing = records.get(normalizedKey);
                if (existing == null || incoming.playTicks() < existing.playTicks()) {
                    records.put(normalizedKey, incoming);
                }
            }
        }

        return new ImportPayload(ownerUuid, targets, records);
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
                updatedRecords++;
            }
        }
        if (addedTargets > 0 || updatedRecords > 0) {
            data.setDirty();
        }
        return new ImportMergeStats(addedTargets, updatedRecords);
    }
}
