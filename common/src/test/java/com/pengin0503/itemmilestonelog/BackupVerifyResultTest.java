package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java tests for the closed, detail-free backup verification outcome vocabulary (F-150-55). */
class BackupVerifyResultTest {
    @Test
    void allSixStatusesHaveUniqueFixedLanguageKeys() {
        List<String> keys = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (BackupVerifyResult.Status status : EnumSet.complementOf(
                EnumSet.of(BackupVerifyResult.Status.OK))) {
            BackupVerifyResult result = BackupVerifyResult.failure(status);
            assertTrue(seen.add(result.messageKey()), "duplicate key: " + result.messageKey());
            keys.add(result.messageKey());
            assertEquals("message.itemmilestonelog.backup_verify_"
                    + status.name().toLowerCase(java.util.Locale.ROOT), result.messageKey());
            assertFalse(result.isOk());
            assertEquals(0, result.players());
            assertEquals(0, result.worldFirsts());
            assertEquals(0, result.schemaVersion());
        }
        // OK is covered by ok(...) below; the five failure statuses are checked here.
        assertEquals(5, keys.size());
        assertEquals(EnumSet.allOf(BackupVerifyResult.Status.class).size() - 1, keys.size());
        assertTrue(keys.contains("message.itemmilestonelog.backup_verify_invalid_nbt"));
    }

    @Test
    void okCarriesOnlyCountsAndNeverAnExceptionMessage() {
        BackupVerifyResult ok = BackupVerifyResult.ok(3, 2, SchemaVersionLogic.CURRENT_SCHEMA_VERSION);
        assertTrue(ok.isOk());
        assertEquals(3, ok.players());
        assertEquals(2, ok.worldFirsts());
        assertEquals(1, ok.schemaVersion());
        assertEquals("message.itemmilestonelog.backup_verify_ok", ok.messageKey());
        assertThrows(IllegalArgumentException.class, () -> BackupVerifyResult.failure(BackupVerifyResult.Status.OK));
        assertThrows(IllegalArgumentException.class, () -> BackupVerifyResult.ok(-1, 0, 0));
    }
}
