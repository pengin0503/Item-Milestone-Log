package com.pengin0503.itemmilestonelog;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * Pure-Java part of F-150-57 (N90, structured admin audit log), shared by all five Minecraft
 * versions via {@code common/src/main/java}. Holds one immutable audit record and renders it as a
 * single UTF-8 JSONL line. File access and rotation live in the thin version-side {@code AuditLog}.
 *
 * <p>The common rules are defined only in {@code spec-150-operations.md} §1.3. In particular:
 * <ul>
 *   <li>one operation = one line, fields in the fixed order
 *       {@code v, ts, op, actor, target, detail, result};</li>
 *   <li>{@code v} is the audit record version ({@value #AUDIT_VERSION}) and is unrelated to the
 *       SavedData {@code schemaVersion};</li>
 *   <li>{@code ts} is an UTC ISO-8601 {@link Instant};</li>
 *   <li>{@code op} is a fixed, non-localized upper-case name;</li>
 *   <li>{@code actor} is a player UUID string or {@link #NON_PLAYER_ACTOR};</li>
 *   <li>{@code target} is an item id / player UUID / file name or JSON {@code null};</li>
 *   <li>{@code detail} is a short key/value object; values never contain secrets (webhook URLs,
 *       {@code env:} values, exception messages, player display names or raw command arguments);</li>
 *   <li>{@code result} is {@code success} only: failed operations are not recorded. The field is
 *       kept for forward compatibility.</li>
 * </ul>
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class AuditEvent {
    /** Audit record version. Unrelated to {@link SchemaVersionLogic#CURRENT_SCHEMA_VERSION}. */
    public static final int AUDIT_VERSION = 1;

    /** Actor used for console / RCON / command block / function sources. */
    public static final String NON_PLAYER_ACTOR = "#nonplayer";

    /** Maximum length of any variable-length string written into {@code detail}. */
    public static final int MAX_VALUE_CHARS = 4000;

    private static final String RESULT_SUCCESS = "success";
    private static final String REDACTED = "[redacted]";

    // Keys that must never reach the file, even if a hook accidentally supplies them.
    private static final Set<String> FORBIDDEN_DETAIL_KEYS = Set.of(
            "url", "webhook", "webhookurl", "token", "secret", "password", "passwd",
            "env", "envvalue", "stacktrace", "stack", "message",
            "errormessage", "playername", "displayname", "username", "rawargs", "rawcommand");

    /** Fixed, non-localized operation names from the F-150-57 recording-target table. */
    public enum Operation {
        ADMIN_TARGET_ADD("ADMIN_TARGET_ADD"),
        ADMIN_TARGET_REMOVE("ADMIN_TARGET_REMOVE"),
        ADMIN_RECORD_RESET_ALL("ADMIN_RECORD_RESET_ALL"),
        ADMIN_PURGE("ADMIN_PURGE"),
        ADMIN_COPY_FROM("ADMIN_COPY_FROM"),
        FIRSTS_RESET("FIRSTS_RESET"),
        PRUNE_DATA("PRUNE_DATA"),
        RENAME_ITEM("RENAME_ITEM"),
        RENAME_TAG("RENAME_TAG"),
        IMPORT_ALL("IMPORT_ALL"),
        PRESET_MUTATE("PRESET_MUTATE"),
        DISCORD_MANUAL_DIGEST("DISCORD_MANUAL_DIGEST"),
        BACKUP_NOW("BACKUP_NOW"),
        /** Designed in F-150-58 but deliberately not shipped (pen decision, D-150-136). */
        ADJUST_RECORD("ADJUST_RECORD");

        private final String wireName;

        Operation(String wireName) {
            this.wireName = wireName;
        }

        /** The upper-case name written to the {@code op} field. */
        public String wireName() {
            return wireName;
        }
    }

    private final Operation op;
    private final Instant ts;
    private final String actor;
    @Nullable
    private final String target;
    private final Map<String, Object> detail;
    private final String jsonLine;

    private AuditEvent(Operation op, Instant ts, String actor, @Nullable String target,
                       Map<String, ?> detail) {
        this.op = Objects.requireNonNull(op, "op");
        this.ts = Objects.requireNonNull(ts, "ts");
        this.actor = actor == null || actor.isEmpty() ? NON_PLAYER_ACTOR : actor;
        this.target = target == null ? null : sanitizeString(target);
        this.detail = sanitizeDetail(detail);
        this.jsonLine = renderJson();
    }

    /** Creates an event stamped with the current UTC instant. */
    public static AuditEvent of(Operation op, @Nullable UUID actorUuid, @Nullable String target,
                                Map<String, ?> detail) {
        return of(op, actorUuid, target, detail, Instant.now());
    }

    /** Explicit-timestamp factory used by the shared unit tests. */
    public static AuditEvent of(Operation op, @Nullable UUID actorUuid, @Nullable String target,
                                Map<String, ?> detail, Instant ts) {
        return new AuditEvent(op, ts, actorUuid == null ? null : actorUuid.toString(), target, detail);
    }

    /** Creates an event for a console / RCON / command block / function source. */
    public static AuditEvent nonPlayer(Operation op, @Nullable String target, Map<String, ?> detail) {
        return of(op, null, target, detail);
    }

    /** Explicit-timestamp non-player factory used by the shared unit tests. */
    public static AuditEvent nonPlayer(Operation op, @Nullable String target, Map<String, ?> detail,
                                       Instant ts) {
        return of(op, null, target, detail, ts);
    }

    public Operation op() {
        return op;
    }

    public Instant ts() {
        return ts;
    }

    public String actor() {
        return actor;
    }

    @Nullable
    public String target() {
        return target;
    }

    /** Sanitized, immutable detail object. */
    public Map<String, Object> detail() {
        return detail;
    }

    /** The single JSONL line (without its trailing newline), with a fixed field order. */
    public String toJsonLine() {
        return jsonLine;
    }

    private static Map<String, Object> sanitizeDetail(@Nullable Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> safe = new TreeMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String cleanKey = sanitizeString(key);
            if (cleanKey.isEmpty() || FORBIDDEN_DETAIL_KEYS.contains(cleanKey.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null || value instanceof Number || value instanceof Boolean) {
                safe.put(cleanKey, value);
            } else if (value instanceof Throwable throwable) {
                // Never inspect getMessage(): mirrors DiscordTestResult.safeExceptionName.
                safe.put(cleanKey, DiscordTestResult.safeExceptionName(throwable));
            } else if (value instanceof Enum<?> enumValue) {
                safe.put(cleanKey, sanitizeString(enumValue.name()));
            } else {
                safe.put(cleanKey, sanitizeString(String.valueOf(value)));
            }
        }
        return Collections.unmodifiableMap(safe);
    }

    /** Drops line breaks, truncates safely and redacts secret-looking content. */
    private static String sanitizeString(String value) {
        String clean = TextEscaping.truncateUtf16(TextEscaping.removeLineBreaks(value), MAX_VALUE_CHARS);
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.startsWith("env:") || lower.contains("://") || lower.contains("webhooks/")) {
            return REDACTED;
        }
        return clean;
    }

    private String renderJson() {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"v\":").append(AUDIT_VERSION);
        sb.append(",\"ts\":").append(quote(ts.toString()));
        sb.append(",\"op\":").append(quote(op.wireName()));
        sb.append(",\"actor\":").append(quote(actor));
        sb.append(",\"target\":").append(target == null ? "null" : quote(target));
        sb.append(",\"detail\":{");
        boolean first = true;
        Map<String, Object> sorted = new TreeMap<>(detail);
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append(quote(String.valueOf(value)));
            }
        }
        sb.append("},\"result\":").append(quote(RESULT_SUCCESS)).append('}');
        return sb.toString();
    }

    /** Minimal JSON string escaping; also removes any residual control characters. */
    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Convenience for hook sites that already have a {@code Map.of(...)} of small values. */
    public static Map<String, Object> detail(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("detail requires key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
