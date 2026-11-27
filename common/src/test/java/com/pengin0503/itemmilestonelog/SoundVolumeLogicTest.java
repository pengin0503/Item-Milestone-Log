package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-49 (N114): server volume x personal -1/value precedence and lenient SavedData reads. */
class SoundVolumeLogicTest {
    @AfterEach
    void resetWarnings() {
        SoundVolumeLogic.resetWarningStateForTests();
    }

    @Test
    void personalFollowServerInheritsTheServerDefault() {
        assertEquals(100, SoundVolumeLogic.resolve(SoundVolumeLogic.FOLLOW_SERVER, 100));
        assertEquals(35, SoundVolumeLogic.resolve(SoundVolumeLogic.FOLLOW_SERVER, 35));
        assertEquals(0, SoundVolumeLogic.resolve(SoundVolumeLogic.FOLLOW_SERVER, 0));
    }

    @Test
    void personalValueOverridesServerDefaultAndBoundaryValuesAreExact() {
        assertEquals(0, SoundVolumeLogic.resolve(0, 100));
        assertEquals(50, SoundVolumeLogic.resolve(50, 80));
        assertEquals(100, SoundVolumeLogic.resolve(100, 0));
    }

    @Test
    void outOfRangePersonalAndServerValuesFallBackLeniently() {
        assertEquals(70, SoundVolumeLogic.resolve(101, 70));
        assertEquals(70, SoundVolumeLogic.resolve(-2, 70));
        assertEquals(100, SoundVolumeLogic.resolve(SoundVolumeLogic.FOLLOW_SERVER, -5));
        assertEquals(100, SoundVolumeLogic.resolve(SoundVolumeLogic.FOLLOW_SERVER, 101));
    }

    @Test
    void lenientSavedReadsAcceptTheSentinelAndTheWholeLegalRange() {
        assertEquals(-1, SoundVolumeLogic.normalizePersonal(null));
        assertEquals(-1, SoundVolumeLogic.normalizePersonal("garbage"));
        assertEquals(-1, SoundVolumeLogic.normalizePersonal(101));
        assertEquals(-1, SoundVolumeLogic.normalizePersonal(-3));
        assertEquals(-1, SoundVolumeLogic.normalizePersonal(-1));
        assertEquals(0, SoundVolumeLogic.normalizePersonal(0));
        assertEquals(100, SoundVolumeLogic.normalizePersonal(100));
        assertEquals(100L, (long) SoundVolumeLogic.normalizePersonal(100L));
    }

    @Test
    void storedValueGuardRejectsOnlyOutOfRangeNumbers() {
        assertTrue(SoundVolumeLogic.isStoredPersonal(-1));
        assertTrue(SoundVolumeLogic.isStoredPersonal(0));
        assertTrue(SoundVolumeLogic.isStoredPersonal(100));
        assertFalse(SoundVolumeLogic.isStoredPersonal(-2));
        assertFalse(SoundVolumeLogic.isStoredPersonal(101));
    }

    @Test
    void packetVolumeKeepsVanillaLoudnessAtTheDefault() {
        assertEquals(1.0F, SoundVolumeLogic.packetVolume(100));
        assertEquals(0.5F, SoundVolumeLogic.packetVolume(50));
        assertEquals(0.0F, SoundVolumeLogic.packetVolume(0));
    }
}
