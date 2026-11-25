package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaVersionLogicTest {
    @Test
    void currentVersionIsOne() {
        assertEquals(1, SchemaVersionLogic.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void missingFieldIsTreatedAsZeroWithoutWarningAndSavesAsCurrent() {
        SchemaVersionLogic.LoadResult result =
                SchemaVersionLogic.fromStoredVersion(SchemaVersionLogic.ABSENT_SCHEMA_VERSION);

        assertEquals(0, result.retainedVersion());
        assertEquals(SchemaVersionLogic.Status.MISSING, result.status());
        assertFalse(result.shouldWarn());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, result.versionToSave());
    }

    @Test
    void zeroAndCurrentAreValidAndSaveAsCurrent() {
        SchemaVersionLogic.LoadResult zero = SchemaVersionLogic.fromStoredVersion(0);
        assertEquals(0, zero.retainedVersion());
        assertEquals(SchemaVersionLogic.Status.VALID, zero.status());
        assertFalse(zero.shouldWarn());
        assertEquals(1, zero.versionToSave());

        SchemaVersionLogic.LoadResult one = SchemaVersionLogic.fromStoredVersion(1);
        assertEquals(1, one.retainedVersion());
        assertEquals(SchemaVersionLogic.Status.VALID, one.status());
        assertFalse(one.shouldWarn());
        assertEquals(1, one.versionToSave());
    }

    @Test
    void futureVersionIsRetainedAndSavesAsItself() {
        SchemaVersionLogic.LoadResult future = SchemaVersionLogic.fromStoredVersion(99);

        assertEquals(99, future.retainedVersion());
        assertEquals(SchemaVersionLogic.Status.FUTURE, future.status());
        assertTrue(future.shouldWarn());
        assertTrue(future.isFuture());
        assertEquals(99, future.versionToSave());
    }

    @Test
    void justAboveCurrentIsFuture() {
        SchemaVersionLogic.LoadResult future = SchemaVersionLogic.fromStoredVersion(2);
        assertEquals(2, future.retainedVersion());
        assertEquals(SchemaVersionLogic.Status.FUTURE, future.status());
        assertEquals(2, future.versionToSave());
    }

    @Test
    void negativeVersionIsInvalidAndSavesAsCurrent() {
        SchemaVersionLogic.LoadResult negative = SchemaVersionLogic.fromStoredVersion(-5);

        assertEquals(0, negative.retainedVersion());
        assertEquals(SchemaVersionLogic.Status.INVALID, negative.status());
        assertTrue(negative.shouldWarn());
        assertFalse(negative.isFuture());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, negative.versionToSave());
    }

    @Test
    void malformedVersionIsInvalidAndSavesAsCurrent() {
        SchemaVersionLogic.LoadResult malformed =
                SchemaVersionLogic.fromStoredVersion(SchemaVersionLogic.MALFORMED_SCHEMA_VERSION);

        assertEquals(0, malformed.retainedVersion());
        assertEquals(SchemaVersionLogic.Status.INVALID, malformed.status());
        assertTrue(malformed.shouldWarn());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, malformed.versionToSave());
    }

    @Test
    void versionToSaveNeverDowngradesBelowCurrent() {
        assertEquals(1, SchemaVersionLogic.versionToSave(0));
        assertEquals(1, SchemaVersionLogic.versionToSave(1));
        assertEquals(2, SchemaVersionLogic.versionToSave(2));
        assertEquals(99, SchemaVersionLogic.versionToSave(99));
    }

    @Test
    void loadResultRejectsNegativeRetainedVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaVersionLogic.LoadResult(-1, SchemaVersionLogic.Status.INVALID));
    }

    @Test
    void warningMessageIsEmittedOnceForFutureAndInvalidOnly() {
        assertNull(SchemaVersionLogic.warningMessage(
                SchemaVersionLogic.ABSENT_SCHEMA_VERSION,
                SchemaVersionLogic.fromStoredVersion(SchemaVersionLogic.ABSENT_SCHEMA_VERSION)));
        assertNull(SchemaVersionLogic.warningMessage(0, SchemaVersionLogic.fromStoredVersion(0)));
        assertNull(SchemaVersionLogic.warningMessage(1, SchemaVersionLogic.fromStoredVersion(1)));

        String future = SchemaVersionLogic.warningMessage(99, SchemaVersionLogic.fromStoredVersion(99));
        assertNotNull(future);
        assertTrue(future.contains("newer schemaVersion 99"));
        assertTrue(future.contains("best-effort"));

        String negative = SchemaVersionLogic.warningMessage(-2, SchemaVersionLogic.fromStoredVersion(-2));
        assertNotNull(negative);
        assertTrue(negative.contains("-2"));
        assertTrue(negative.contains("treating it as 0"));

        String malformed = SchemaVersionLogic.warningMessage(
                SchemaVersionLogic.MALFORMED_SCHEMA_VERSION,
                SchemaVersionLogic.fromStoredVersion(SchemaVersionLogic.MALFORMED_SCHEMA_VERSION));
        assertNotNull(malformed);
        assertTrue(malformed.contains("malformed"));
    }
}
