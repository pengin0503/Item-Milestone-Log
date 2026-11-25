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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the chat messages that the server sends to players.
 *
 * Every message is a translatable component that also carries a fallback taken
 * from the mod's bundled language files ({@code en_us.json}, {@code ja_jp.json}).
 * Clients with the mod installed show the message in their own language; vanilla
 * clients show the fallback matching their reported client language (falling back
 * to English if no bundled translation exists for that language).
 */
public final class ServerLang {
    public static final String DEFAULT_LANG = "en_us";
    public static final List<String> BUNDLED_LANGUAGES = List.of("en_us", "ja_jp");

    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:(\\d+)\\$)?([s%])");
    private static final Object[] EMPTY_ARGS = new Object[0];

    private static final Map<String, Map<String, String>> languageTables = loadAll();

    private ServerLang() {
    }

    /** A translatable component with the English text of {@code key} as its fallback. */
    public static MutableComponent tr(String key, Object... args) {
        return trForLang(DEFAULT_LANG, key, args);
    }

    /**
     * A translatable component whose fallback matches {@code player}'s client language
     * when bundled, or English otherwise.
     */
    public static MutableComponent tr(@Nullable ServerPlayer player, String key, Object... args) {
        if (player == null) {
            return tr(key, args);
        }
        // 1.20.1: ServerPlayer#getLanguage() (1.20.2+ replaced it with clientInformation().language()).
        String lang = player.getLanguage() != null ? player.getLanguage() : DEFAULT_LANG;
        return trForLang(lang, key, args);
    }

    /**
     * A translatable component whose fallback matches the source player's client language,
     * or English when the source is not a player (e.g. server console / RCON).
     */
    public static MutableComponent tr(@Nullable CommandSourceStack source, String key, Object... args) {
        ServerPlayer player = source != null ? source.getPlayer() : null;
        return tr(player, key, args);
    }

    /**
     * A translatable component whose fallback uses the specified language code if bundled,
     * or English otherwise.
     */
    public static MutableComponent trForLang(@Nullable String language, String key, Object... args) {
        String fallback = lookupFallback(language, key);
        return fallback != null
                ? Component.translatableWithFallback(key, fallback, args)
                : Component.translatable(key, args);
    }

    /**
     * Resolves {@code key} directly from the bundled language table for {@code language}
     * (falling back to English) and formats it with Minecraft translation placeholders
     * ({@code %s}, positional {@code %1$s}, and {@code %%}).
     *
     * <p>Unlike {@code Component.translatable(...).getString()}, this never consults the
     * dedicated server's global English {@code Language} table, so non-English languages
     * such as {@code ja_jp} work on dedicated servers (e.g. for Discord notifications).
     */
    public static String formatForLang(@Nullable String language, String key, Object... args) {
        if (key == null) {
            return "";
        }
        String template = lookupFallback(language, key);
        if (template == null) {
            return key;
        }
        return formatTemplate(template, args);
    }

    /** Formats a raw Minecraft translation template supporting {@code %s}, {@code %N$s}, and {@code %%}. */
    static String formatTemplate(String template, @Nullable Object... args) {
        if (template == null || template.isEmpty() || template.indexOf('%') < 0) {
            return template != null ? template : "";
        }
        Object[] safeArgs = args != null ? args : EMPTY_ARGS;
        Matcher matcher = FORMAT_SPECIFIER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        int lastEnd = 0;
        int implicitIndex = 0;
        while (matcher.find()) {
            sb.append(template, lastEnd, matcher.start());
            String pos = matcher.group(1);
            String type = matcher.group(2);
            if ("%".equals(type)) {
                if (pos == null) {
                    sb.append('%');
                } else {
                    sb.append(template, matcher.start(), matcher.end());
                }
            } else {
                int argIndex;
                if (pos != null) {
                    int parsed = parsePositiveIndex(pos);
                    argIndex = parsed >= 1 ? parsed - 1 : -1;
                } else {
                    argIndex = implicitIndex++;
                }
                if (argIndex >= 0 && argIndex < safeArgs.length) {
                    sb.append(argToString(safeArgs[argIndex]));
                } else {
                    sb.append(template, matcher.start(), matcher.end());
                }
            }
            lastEnd = matcher.end();
        }
        sb.append(template, lastEnd, template.length());
        return sb.toString();
    }

    private static int parsePositiveIndex(String digits) {
        try {
            int value = Integer.parseInt(digits);
            return value >= 1 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String argToString(@Nullable Object arg) {
        if (arg instanceof Component component) {
            return component.getString();
        }
        return String.valueOf(arg);
    }

    @Nullable
    static String lookupFallback(@Nullable String language, String key) {
        if (key == null) {
            return null;
        }
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

    /** English texts by translation key, loaded once from the mod's {@code en_us.json}. */
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
