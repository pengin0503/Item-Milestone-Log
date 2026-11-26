package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.TargetKey;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure-Java encode/decode logic for preset share codes (spec 1.4.0 F-140-18 / N54),
 * shared across all Minecraft versions.
 *
 * <p>A share code is {@code IML1:} followed by the unpadded Base64URL encoding of a canonical
 * preset JSON document. The document uses the normal preset-file layout plus an {@code id}
 * field (preset files get their ID from the file name; a code has to carry it), and its
 * {@code items} array is always the includes-expanded item list. Every element of the decoded
 * document is still validated by the normal preset parse path in the version layer
 * ({@code Presets.parsePresetContent}); this class only validates the envelope, the JSON
 * structure needed to read the ID and item count, and the import caps.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetShareLogic {
    /** Prefix every share code starts with. */
    public static final String PREFIX = "IML1:";

    /** Maximum accepted length of the whole code string ({@code IML1:} included). */
    public static final int MAX_CODE_CHARS = 16_384;

    /** Maximum accepted number of entries in the decoded {@code items} array. */
    public static final int MAX_ITEMS = 512;

    /** Outcome of decoding a share code; the command maps each status to its own message. */
    public enum Status {
        /** The code decoded into a preset document that may be imported. */
        OK,
        /** The code does not start with {@link #PREFIX}. */
        INVALID_PREFIX,
        /** The Base64URL payload is empty or cannot be decoded. */
        INVALID_CODE,
        /** The decoded bytes are not a JSON object, or the item list is not an array. */
        INVALID_JSON,
        /** The whole code string exceeds {@link #MAX_CODE_CHARS}. */
        TOO_LONG,
        /** The decoded {@code items} array exceeds {@link #MAX_ITEMS}. */
        TOO_MANY_ITEMS,
        /** The decoded preset has no items. */
        EMPTY_ITEMS,
        /** The document has no usable {@code id} string. */
        INVALID_ID,
        /** The document's ID is already used by another preset. */
        ID_COLLISION
    }

    /**
     * A decoded share code.
     *
     * @param status    outcome; only {@link Status#OK} carries the other values
     * @param id        the preset ID carried by the code, otherwise null
     * @param json      the decoded preset JSON document, otherwise null
     * @param itemCount number of entries in the decoded {@code items} array (0 on errors)
     */
    public record Decoded(Status status, @Nullable String id, @Nullable String json, int itemCount) {
        public Decoded {
            Objects.requireNonNull(status, "status");
            itemCount = Math.max(0, itemCount);
        }

        public static Decoded error(Status status) {
            return new Decoded(status, null, null, 0);
        }
    }

    /**
     * One preset definition in the form a share code carries: the includes-expanded items and
     * every piece of preset metadata the normal JSON format supports.
     */
    public record ShareData(
            String id,
            PresetName name,
            List<TargetKey> items,
            PresetName description,
            @Nullable String category,
            int order,
            @Nullable Integer version,
            List<String> requiredMods,
            List<PresetGroup> groups,
            List<String> nextPresets,
            @Nullable Double unlockAfterHours) {

        public ShareData {
            name = name == null ? PresetName.absent() : name;
            items = items == null ? List.of() : List.copyOf(items);
            description = description == null ? PresetName.absent() : description;
            requiredMods = requiredMods == null ? List.of() : List.copyOf(requiredMods);
            groups = groups == null ? List.of() : List.copyOf(groups);
            nextPresets = nextPresets == null ? List.of() : List.copyOf(nextPresets);
        }
    }

    private PresetShareLogic() {
    }

    /**
     * Encodes {@code data} as {@code IML1:} + unpadded Base64URL of the canonical JSON.
     * The result is deterministic for equal inputs (fixed key order, defaults omitted), so the
     * same preset always produces the same code.
     */
    public static String encode(ShareData data) {
        Objects.requireNonNull(data, "data");
        String json = canonicalJson(data);
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The canonical preset JSON document a share code is built from (without the envelope). */
    public static String canonicalJson(ShareData data) {
        Objects.requireNonNull(data, "data");
        JsonObject root = new JsonObject();
        root.addProperty("id", data.id());
        writeName(root, "name", data.name());
        writeName(root, "description", data.description());
        if (data.category() != null) {
            root.addProperty("category", data.category());
        }
        if (data.order() != 0) {
            root.addProperty("order", data.order());
        }
        if (data.version() != null) {
            root.addProperty("version", data.version());
        }
        if (!data.requiredMods().isEmpty()) {
            root.add("requiredMods", stringArray(data.requiredMods()));
        }
        if (!data.groups().isEmpty()) {
            JsonArray groups = new JsonArray();
            for (PresetGroup group : data.groups()) {
                if (group == null) {
                    continue;
                }
                JsonObject groupJson = new JsonObject();
                groupJson.addProperty("id", group.id());
                writeName(groupJson, "name", group.name());
                groupJson.add("items", itemsArray(group.items()));
                groups.add(groupJson);
            }
            if (!groups.isEmpty()) {
                root.add("groups", groups);
            }
        }
        if (!data.nextPresets().isEmpty()) {
            root.add("nextPresets", stringArray(data.nextPresets()));
        }
        if (data.unlockAfterHours() != null) {
            root.addProperty("unlockAfterHours", data.unlockAfterHours());
        }
        root.add("items", itemsArray(data.items()));
        return root.toString();
    }

    /**
     * Decodes and envelope-validates a share code.
     *
     * @param code      the chat argument; null/blank is treated as a bad prefix
     * @param idExists  tells whether a preset ID is already taken (built-in, file or datapack);
     *                  may be null to skip the collision check
     */
    public static Decoded decode(@Nullable String code, @Nullable Predicate<String> idExists) {
        if (code == null || !code.startsWith(PREFIX)) {
            return Decoded.error(Status.INVALID_PREFIX);
        }
        if (code.length() > MAX_CODE_CHARS) {
            return Decoded.error(Status.TOO_LONG);
        }
        String payload = code.substring(PREFIX.length());
        if (payload.isEmpty()) {
            return Decoded.error(Status.INVALID_CODE);
        }

        String json;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payload);
            json = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Decoded.error(Status.INVALID_CODE);
        }

        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return Decoded.error(Status.INVALID_JSON);
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException e) {
            return Decoded.error(Status.INVALID_JSON);
        }

        JsonElement idElement = root.get("id");
        if (idElement == null || !idElement.isJsonPrimitive()
                || !idElement.getAsJsonPrimitive().isString()) {
            return Decoded.error(Status.INVALID_ID);
        }
        String id = idElement.getAsString().trim();
        if (id.isEmpty()) {
            return Decoded.error(Status.INVALID_ID);
        }

        JsonElement itemsElement = root.get("items");
        if (itemsElement == null || !itemsElement.isJsonArray()) {
            return Decoded.error(Status.INVALID_JSON);
        }
        int itemCount = itemsElement.getAsJsonArray().size();
        if (itemCount == 0) {
            return Decoded.error(Status.EMPTY_ITEMS);
        }
        if (itemCount > MAX_ITEMS) {
            return Decoded.error(Status.TOO_MANY_ITEMS);
        }
        if (idExists != null && idExists.test(id)) {
            return Decoded.error(Status.ID_COLLISION);
        }
        return new Decoded(Status.OK, id, json, itemCount);
    }

    private static void writeName(JsonObject root, String field, PresetName name) {
        if (name == null || !name.present()) {
            return;
        }
        if (name.localizedForm()) {
            JsonObject values = new JsonObject();
            for (Map.Entry<String, String> entry : name.localizedValues().entrySet()) {
                values.addProperty(entry.getKey(), entry.getValue());
            }
            root.add(field, values);
        } else if (name.plainValue() != null) {
            root.addProperty(field, name.plainValue());
        }
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value != null) {
                array.add(value);
            }
        }
        return array;
    }

    private static JsonArray itemsArray(List<TargetKey> items) {
        JsonArray array = new JsonArray();
        Set<TargetKey> seen = new LinkedHashSet<>();
        for (TargetKey key : items) {
            if (key == null || !seen.add(key)) {
                continue;
            }
            if (key.count() > TargetKey.MIN_COUNT) {
                JsonObject object = new JsonObject();
                object.addProperty("item", key.itemId());
                object.addProperty("count", key.count());
                array.add(object);
            } else {
                array.add(key.itemId());
            }
        }
        return array;
    }
}
