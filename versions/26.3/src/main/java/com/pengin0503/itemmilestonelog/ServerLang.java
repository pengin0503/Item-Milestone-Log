package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the chat messages that the server sends to players.
 */
public final class ServerLang {
    private static final String DEFAULT_LANG = "en_us";
    private static final List<String> BUNDLED_LANGUAGES = List.of("en_us", "ja_jp");

    private static final Map<String, Map<String, String>> languageTables = loadAll();

    private ServerLang() {
    }

    public static MutableComponent tr(String key, Object... args) {
        return trForLang(DEFAULT_LANG, key, args);
    }

    public static MutableComponent tr(@Nullable ServerPlayer player, String key, Object... args) {
        if (player == null) {
            return tr(key, args);
        }
        String lang = player.clientInformation() != null ? player.clientInformation().language() : DEFAULT_LANG;
        return trForLang(lang, key, args);
    }

    public static MutableComponent tr(@Nullable CommandSourceStack source, String key, Object... args) {
        ServerPlayer player = source != null ? source.getPlayer() : null;
        return tr(player, key, args);
    }

    public static MutableComponent trForLang(@Nullable String language, String key, Object... args) {
        String fallback = lookupFallback(language, key);
        return fallback != null
                ? Component.translatableWithFallback(key, fallback, args)
                : Component.translatable(key, args);
    }

    @Nullable
    static String lookupFallback(@Nullable String language, String key) {
        Map<String, Map<String, String>> all = tables();
        if (language != null && !language.isBlank()) {
            String normalized = language.toLowerCase(Locale.ROOT);
            Map<String, String> langMap = all.get(normalized);
            if (langMap != null) {
                String value = langMap.get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        Map<String, String> enMap = all.get(DEFAULT_LANG);
        return enMap != null ? enMap.get(key) : null;
    }

    static Map<String, String> fallbacks() {
        return tables().getOrDefault(DEFAULT_LANG, Map.of());
    }

    private static Map<String, Map<String, String>> tables() {
        return languageTables;
    }

    private static Map<String, Map<String, String>> loadAll() {
        Map<String, Map<String, String>> all = new HashMap<>();
        for (String lang : BUNDLED_LANGUAGES) {
            Map<String, String> loaded = loadLang(lang);
            if (!loaded.isEmpty()) {
                all.put(lang, loaded);
            }
        }
        return Map.copyOf(all);
    }

    private static Map<String, String> loadLang(String lang) {
        String path = "/assets/" + ItemMilestoneLog.MOD_ID + "/lang/" + lang + ".json";
        try (InputStream in = ServerLang.class.getResourceAsStream(path)) {
            if (in == null) {
                if (DEFAULT_LANG.equals(lang)) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] {} was not found; vanilla clients will see translation keys", path);
                }
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
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not read {}; skipping language fallback", path, e);
            return Map.of();
        }
    }
}
