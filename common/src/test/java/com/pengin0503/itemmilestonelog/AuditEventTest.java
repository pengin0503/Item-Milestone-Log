package com.pengin0503.itemmilestonelog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** F-150-57 / §1.3: shared, version-independent audit record tests (all five trees). */
class AuditEventTest {
    private static final Instant TS = Instant.parse("2026-11-28T01:02:03Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void operationNamesAreTheFixedSetFromTheSpecTable() {
        Set<String> names = Arrays.stream(AuditEvent.Operation.values())
                .map(AuditEvent.Operation::wireName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "ADMIN_TARGET_ADD", "ADMIN_TARGET_REMOVE", "ADMIN_RECORD_RESET_ALL",
                "ADMIN_PURGE", "ADMIN_COPY_FROM", "FIRSTS_RESET", "PRUNE_DATA",
                "RENAME_ITEM", "RENAME_TAG", "IMPORT_ALL", "PRESET_MUTATE",
                "DISCORD_MANUAL_DIGEST", "BACKUP_NOW", "ADJUST_RECORD"), names);
        for (AuditEvent.Operation op : AuditEvent.Operation.values()) {
            assertEquals(op.name(), op.wireName());
        }
    }

    @Test
    void jsonHasFixedFieldOrderAndShape() {
        AuditEvent event = AuditEvent.of(AuditEvent.Operation.BACKUP_NOW, ACTOR, "backup-1.nbt",
                AuditEvent.detail("count", 2, "fileName", "backup-1.nbt", "ok", true), TS);
        assertEquals("{\"v\":1,\"ts\":\"2026-11-28T01:02:03Z\",\"op\":\"BACKUP_NOW\","
                        + "\"actor\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"target\":\"backup-1.nbt\","
                        + "\"detail\":{\"count\":2,\"fileName\":\"backup-1.nbt\",\"ok\":true},"
                        + "\"result\":\"success\"}",
                event.toJsonLine());
        assertEquals(1, AuditEvent.AUDIT_VERSION);
    }

    @Test
    void nonPlayerActorAndNullTargetAreSerialized() {
        AuditEvent event = AuditEvent.nonPlayer(AuditEvent.Operation.ADMIN_PURGE, null,
                Map.of(), TS);
        assertEquals(AuditEvent.NON_PLAYER_ACTOR, event.actor());
        assertNull(event.target());
        assertTrue(event.toJsonLine().contains("\"target\":null"));
        assertTrue(event.toJsonLine().contains("\"detail\":{}"));
        assertTrue(event.toJsonLine().contains("\"actor\":\"#nonplayer\""));
    }

    @Test
    void timestampIsUtcIso8601Instant() {
        AuditEvent event = AuditEvent.of(AuditEvent.Operation.FIRSTS_RESET, ACTOR, "item:stone",
                Map.of(), TS);
        assertEquals(TS, event.ts());
        assertTrue(event.toJsonLine().contains("\"ts\":\"2026-11-28T01:02:03Z\""));
        assertEquals(TS, Instant.parse("2026-11-28T01:02:03Z"));
    }

    @Test
    void lineBreaksAreRemovedAndLongValuesAreTruncated() {
        String longValue = "a\n".repeat(3000);
        AuditEvent event = AuditEvent.nonPlayer(AuditEvent.Operation.PRUNE_DATA, longValue,
                AuditEvent.detail("days", 7, "note", longValue), TS);
        assertFalse(event.toJsonLine().contains("\\n"));
        assertEquals(AuditEvent.MAX_VALUE_CHARS,
                String.valueOf(event.detail().get("note")).length());
        assertEquals(AuditEvent.MAX_VALUE_CHARS, event.target().length());
    }

    @Test
    void secretsAndExceptionMessagesAreNeverSerialized() {
        String webhook = "https://discord.com/api/webhooks/123/abcXYZtoken";
        String envValue = "env:DISCORD_TOKEN=supersecretvalue";
        String displayName = "SecretPlayerName";
        IllegalArgumentException boom = new IllegalArgumentException("secret detail " + webhook);
        AuditEvent event = AuditEvent.nonPlayer(AuditEvent.Operation.DISCORD_MANUAL_DIGEST, null,
                AuditEvent.detail("entries", 3, "webhookUrl", webhook, "envValue", envValue,
                        "displayName", displayName, "rawArgs", webhook, "exception", boom,
                        "success", true),
                TS);
        String json = event.toJsonLine();
        assertFalse(json.contains(webhook));
        assertFalse(json.contains(envValue));
        assertFalse(json.contains(displayName));
        assertFalse(json.contains("secret detail"));
        assertFalse(json.contains("IllegalArgumentException:"));
        assertTrue(json.contains("\"entries\":3"));
        assertTrue(json.contains("\"success\":true"));
        assertEquals("IllegalArgumentException", event.detail().get("exception"));
    }

    @Test
    void jsonStringsAreEscapedAndDetailIsImmutable() {
        AuditEvent event = AuditEvent.nonPlayer(AuditEvent.Operation.RENAME_ITEM, "a\"b\\c",
                AuditEvent.detail("oldId", "a\"b\nc", "records", 4), TS);
        assertTrue(event.toJsonLine().contains("\"target\":\"a\\\"b\\\\c\""));
        assertTrue(event.toJsonLine().contains("\"oldId\":\"a\\\"b c\""));
        assertThrows(UnsupportedOperationException.class,
                () -> event.detail().put("extra", 1));
    }
}
