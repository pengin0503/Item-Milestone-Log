package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldFirstNamesTest {

    private static final UUID UUID_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void sameNameIsNotRenamed() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, "Steve", true, "Steve");
        assertEquals("Steve", c.name());
        assertNull(c.recordedName());
        assertFalse(c.renamed());
    }

    @Test
    void renamedShowsCurrentAndRecordTimeName() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, "Steve", true, "Alex");
        assertEquals("Alex", c.name());
        assertEquals("Steve", c.recordedName());
        assertTrue(c.renamed());
    }

    @Test
    void caseOnlyChangeCountsAsRename() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, "steve", true, "Steve");
        assertEquals("Steve", c.name());
        assertEquals("steve", c.recordedName());
    }

    @Test
    void noPlayerDataUsesRecordTimeName() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, "Steve", false, null);
        assertEquals("Steve", c.name());
        assertFalse(c.renamed());
    }

    @Test
    void noPlayerDataAndEmptyRecordNameUsesUuid() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, "", false, null);
        assertEquals(UUID_A.toString(), c.name());
        assertFalse(c.renamed());
    }

    @Test
    void bothNamesEmptyUsesUuid() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, "", true, "");
        assertEquals(UUID_A.toString(), c.name());
        assertFalse(c.renamed());
    }

    @Test
    void emptyLastKnownNameFallsBackToRecordTimeName() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, "Steve", true, "");
        assertEquals("Steve", c.name());
        assertFalse(c.renamed());
    }

    @Test
    void emptyRecordNameWithCurrentNameIsNotRenamed() {
        WorldFirstNames.Choice c = WorldFirstNames.choose(UUID_A, null, true, "Alex");
        assertEquals("Alex", c.name());
        assertFalse(c.renamed());
    }
}
