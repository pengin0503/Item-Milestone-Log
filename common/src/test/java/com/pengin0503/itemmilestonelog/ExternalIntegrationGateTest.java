package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure F-150-73 tests: no external mod classes are loaded, only the OR gate is exercised. */
class ExternalIntegrationGateTest {
    @Test
    void allEightModCombinationsAddAtMostOneLine() {
        for (int mask = 0; mask < 8; mask++) {
            boolean jade = (mask & 1) != 0;
            boolean wthit = (mask & 2) != 0;
            boolean top = (mask & 4) != 0;
            for (boolean enabled : new boolean[] { false, true }) {
                for (boolean alreadyShown : new boolean[] { false, true }) {
                    int added = ExternalIntegrationGate.addedLineCount(enabled, jade, wthit, top, alreadyShown);
                    assertTrue(added == 0 || added == 1, "at most one line for mask " + mask);
                    assertEquals(enabled && !alreadyShown && mask != 0 ? 1 : 0, added);
                }
            }
        }
    }

    @Test
    void defaultOffAndDuplicateLinesAreStrictNoOps() {
        assertFalse(ExternalIntegrationGate.addLine(false, true, true, true, false));
        assertFalse(ExternalIntegrationGate.addLine(true, true, true, true, true));
        assertFalse(ExternalIntegrationGate.addLine(true, false, false, false, false));
        assertTrue(ExternalIntegrationGate.anyLoaded(false, false, true));
        assertFalse(ExternalIntegrationGate.anyLoaded(false, false, false));
    }

    @Test
    void threeModsTogetherDoNotMultiplyTheLine() {
        assertEquals(1, ExternalIntegrationGate.addedLineCount(true, true, true, true, false));
        assertEquals(0, ExternalIntegrationGate.addedLineCount(true, true, true, true, true));
    }
}
