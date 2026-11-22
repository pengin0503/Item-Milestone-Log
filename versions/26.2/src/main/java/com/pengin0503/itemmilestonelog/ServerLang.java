package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the chat messages that the server sends to players.
 *
 * Every message is a translatable component that also carries an English
 * fallback taken from this mod's own {@code en_us.json}. Clients with the mod
 * installed show the message in their own language; vanilla clients, which have
 * no translations for this mod, show the English fallback instead of the raw
 * translation key.
 */
public final class ServerLang {
    private static final String EN_US_PATH = "/assets/" + ItemMilestoneLog.MOD_ID + "/lang/en_us.json";

    private static volatile Map<String, String> fallbacks;

    private ServerLang() {
    }

    /** A translatable component with the English text of {@code key} as its fallback. */
    public static MutableComponent tr(String key, Object... args) {
        String fallback = fallbacks().get(key);
        return fallback != null
                ? Component.translatableWithFallback(key, fallback, args)
                : Component.translatable(key, args);
    }

    /** English texts by translation key, loaded once from the mod's {@code en_us.json}. */
    static Map<String, String> fallbacks() {
        Map<String, String> map = fallbacks;
        if (map == null) {
            map = load();
            fallbacks = map;
        }
        return map;
    }

    private static Map<String, String> load() {
        try (InputStream in = ServerLang.class.getResourceAsStream(EN_US_PATH)) {
            if (in == null) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] {} was not found; vanilla clients will see translation keys", EN_US_PATH);
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> map = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        map.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                return Map.copyOf(map);
            }
        } catch (Exception e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not read {}; vanilla clients will see translation keys", EN_US_PATH, e);
            return Map.of();
        }
    }
}
