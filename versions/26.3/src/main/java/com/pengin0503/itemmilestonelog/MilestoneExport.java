package com.pengin0503.itemmilestonelog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JSON export and import helpers for F6.
 */
public final class MilestoneExport {
    public static final int FORMAT_VERSION = 1;
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record ImportPayload(List<String> targets, Map<String, MilestoneRecord> records) {
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
        } catch (IOException ignored) {
        }
        return names;
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
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
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

        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
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
            for (String target : pd.targets) {
                targetsArr.add(target);
            }
        }
        root.add("targets", targetsArr);

        JsonObject recordsObj = new JsonObject();
        if (pd != null) {
            for (Map.Entry<String, MilestoneRecord> r : pd.records.entrySet()) {
                MilestoneRecord rec = r.getValue();
                JsonObject recObj = new JsonObject();
                recObj.addProperty("ticks", rec.playTicks());
                recObj.addProperty("day", rec.day());
                recObj.addProperty("epoch", rec.epochMillis());
                recordsObj.add(r.getKey(), recObj);
            }
        }
        root.add("records", recordsObj);
        return root;
    }

    public static ImportPayload parseSinglePlayerExport(Path file) throws IOException, IllegalArgumentException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("invalid_format");
            }
            root = parsed.getAsJsonObject();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_format", e);
        }

        if (!root.has("format") || !root.get("format").isJsonPrimitive() || !root.get("format").getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("invalid_format");
        }
        if (root.get("format").getAsInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("invalid_format");
        }
        if (root.has("players") && !root.has("targets") && !root.has("records")) {
            throw new IllegalArgumentException("invalid_format");
        }

        List<String> targets = new ArrayList<>();
        if (root.has("targets")) {
            if (!root.get("targets").isJsonArray()) {
                throw new IllegalArgumentException("invalid_format");
            }
            for (JsonElement el : root.getAsJsonArray("targets")) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String itemId = el.getAsString();
                if (Identifier.tryParse(itemId) != null) {
                    targets.add(itemId);
                }
            }
        }

        Map<String, MilestoneRecord> records = new LinkedHashMap<>();
        if (root.has("records")) {
            if (!root.get("records").isJsonObject()) {
                throw new IllegalArgumentException("invalid_format");
            }
            JsonObject recordsObj = root.getAsJsonObject("records");
            for (Map.Entry<String, JsonElement> entry : recordsObj.entrySet()) {
                String itemId = entry.getKey();
                if (Identifier.tryParse(itemId) == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject recObj = entry.getValue().getAsJsonObject();
                if (!recObj.has("ticks") || !recObj.get("ticks").isJsonPrimitive() || !recObj.get("ticks").getAsJsonPrimitive().isNumber()) {
                    continue;
                }
                int ticks = recObj.get("ticks").getAsInt();
                if (ticks < 0) {
                    continue;
                }
                long day = recObj.has("day") && recObj.get("day").isJsonPrimitive() && recObj.get("day").getAsJsonPrimitive().isNumber()
                        ? recObj.get("day").getAsLong()
                        : MilestoneRecord.UNKNOWN_DAY;
                long epoch = recObj.has("epoch") && recObj.get("epoch").isJsonPrimitive() && recObj.get("epoch").getAsJsonPrimitive().isNumber()
                        ? recObj.get("epoch").getAsLong()
                        : MilestoneRecord.UNKNOWN_EPOCH;
                records.put(itemId, new MilestoneRecord(ticks, day, epoch));
            }
        }

        return new ImportPayload(targets, records);
    }

    public static ImportMergeStats mergeInto(MilestoneData data, UUID uuid, ImportPayload payload) {
        MilestoneData.PlayerData pd = data.player(uuid);
        int addedTargets = 0;
        for (String target : payload.targets()) {
            if (pd.targets.add(target)) {
                addedTargets++;
            }
        }
        int updatedRecords = 0;
        for (Map.Entry<String, MilestoneRecord> entry : payload.records().entrySet()) {
            String itemId = entry.getKey();
            MilestoneRecord incoming = entry.getValue();
            MilestoneRecord existing = pd.records.get(itemId);
            if (existing == null || incoming.playTicks() < existing.playTicks()) {
                pd.records.put(itemId, incoming);
                updatedRecords++;
            }
        }
        if (addedTargets > 0 || updatedRecords > 0) {
            data.setDirty();
        }
        return new ImportMergeStats(addedTargets, updatedRecords);
    }
}
