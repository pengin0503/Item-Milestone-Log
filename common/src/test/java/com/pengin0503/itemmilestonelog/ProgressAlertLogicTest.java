package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressAlertLogicTest {
    private static final List<Integer> DEFAULT_THRESHOLDS = List.of(50, 90);

    @Test
    void validatesThresholdsAndStoredThresholds() {
        assertFalse(ProgressAlertLogic.isValidThreshold(-1));
        assertFalse(ProgressAlertLogic.isValidThreshold(0));
        assertTrue(ProgressAlertLogic.isValidThreshold(1));
        assertTrue(ProgressAlertLogic.isValidThreshold(50));
        assertTrue(ProgressAlertLogic.isValidThreshold(99));
        assertFalse(ProgressAlertLogic.isValidThreshold(100));

        assertFalse(ProgressAlertLogic.isValidStoredThreshold(-1));
        assertTrue(ProgressAlertLogic.isValidStoredThreshold(0));
        assertTrue(ProgressAlertLogic.isValidStoredThreshold(1));
        assertTrue(ProgressAlertLogic.isValidStoredThreshold(99));
        assertFalse(ProgressAlertLogic.isValidStoredThreshold(100));
    }

    @Test
    void normalizesThresholdsByFilteringDeduplicatingAndSortingAscending() {
        assertEquals(List.of(), ProgressAlertLogic.normalizeThresholds(null));
        assertEquals(List.of(), ProgressAlertLogic.normalizeThresholds(List.of()));
        assertEquals(List.of(), ProgressAlertLogic.normalizeThresholds(Arrays.asList(null, -5, 0, 100, 150)));
        assertEquals(List.of(25, 50, 90),
                ProgressAlertLogic.normalizeThresholds(Arrays.asList(90, 50, 25, 50, 0, 100, null, -1, 90)));
    }

    @Test
    void computePercentFloorsWithoutOverflow() {
        assertEquals(0, ProgressAlertLogic.computePercent(0, 1000));
        assertEquals(0, ProgressAlertLogic.computePercent(-10, 1000));
        assertEquals(0, ProgressAlertLogic.computePercent(10, 0));
        assertEquals(49, ProgressAlertLogic.computePercent(499, 1000));
        assertEquals(50, ProgressAlertLogic.computePercent(500, 1000));
        assertEquals(89, ProgressAlertLogic.computePercent(899, 1000));
        assertEquals(90, ProgressAlertLogic.computePercent(900, 1000));
        assertEquals(99, ProgressAlertLogic.computePercent(999, 1000));
        assertEquals(50, ProgressAlertLogic.computePercent(500_000, 1_000_000));
        assertEquals(99, ProgressAlertLogic.computePercent(999_999, 1_000_000));
    }

    @Test
    void baselineStoresHighestCrossedOrZeroWithoutNotifying() {
        // Below all thresholds: stores 0 without notifying
        ProgressAlertLogic.Evaluation zero = ProgressAlertLogic.evaluate(0, 1000, DEFAULT_THRESHOLDS, null, 10);
        assertTrue(zero.shouldStore());
        assertEquals(0, zero.newStoredThreshold());
        assertFalse(zero.shouldNotify());
        assertNull(zero.notifyThreshold());

        // Just under 50% (floor 49%): stores 0 without notifying
        ProgressAlertLogic.Evaluation under50 = ProgressAlertLogic.evaluate(499, 1000, DEFAULT_THRESHOLDS, null, 10);
        assertEquals(0, under50.newStoredThreshold());
        assertFalse(under50.shouldNotify());

        // Already at 60%: stores 50 without notifying
        ProgressAlertLogic.Evaluation at60 = ProgressAlertLogic.evaluate(600, 1000, DEFAULT_THRESHOLDS, null, 10);
        assertEquals(50, at60.newStoredThreshold());
        assertFalse(at60.shouldNotify());

        // Already at 95%: stores 90 without notifying
        ProgressAlertLogic.Evaluation at95 = ProgressAlertLogic.evaluate(950, 1000, DEFAULT_THRESHOLDS, null, 10);
        assertEquals(90, at95.newStoredThreshold());
        assertFalse(at95.shouldNotify());
    }

    @Test
    void notifiesOncePerThresholdAndRespectsFloorTruncation() {
        // 499 / 1000 = 49% -> does not cross 50%
        ProgressAlertLogic.Evaluation under50 = ProgressAlertLogic.evaluate(499, 1000, DEFAULT_THRESHOLDS, 0, 10);
        assertFalse(under50.shouldStore());
        assertFalse(under50.shouldNotify());

        // 500 / 1000 = 50% -> crosses 50%
        ProgressAlertLogic.Evaluation at50 = ProgressAlertLogic.evaluate(500, 1000, DEFAULT_THRESHOLDS, 0, 10);
        assertEquals(50, at50.newStoredThreshold());
        assertEquals(50, at50.notifyThreshold());
        assertEquals(50, at50.percent());

        // Staying at 50% or dropping and re-crossing 50% -> no change / no re-alert
        assertFalse(ProgressAlertLogic.evaluate(500, 1000, DEFAULT_THRESHOLDS, 50, 10).shouldNotify());
        assertFalse(ProgressAlertLogic.evaluate(200, 1000, DEFAULT_THRESHOLDS, 50, 10).shouldStore());
        assertFalse(ProgressAlertLogic.evaluate(700, 1000, DEFAULT_THRESHOLDS, 50, 10).shouldNotify());

        // 899 / 1000 = 89% -> does not cross 90%
        assertFalse(ProgressAlertLogic.evaluate(899, 1000, DEFAULT_THRESHOLDS, 50, 10).shouldNotify());

        // 900 / 1000 = 90% -> crosses 90%
        ProgressAlertLogic.Evaluation at90 = ProgressAlertLogic.evaluate(900, 1000, DEFAULT_THRESHOLDS, 50, 10);
        assertEquals(90, at90.newStoredThreshold());
        assertEquals(90, at90.notifyThreshold());
        assertEquals(90, at90.percent());
    }

    @Test
    void crossingMultipleThresholdsAtOnceNotifiesOnlyTheLargest() {
        List<Integer> thresholds = List.of(25, 50, 75, 90);

        ProgressAlertLogic.Evaluation jumpFromZero = ProgressAlertLogic.evaluate(95, 100, thresholds, 0, 10);
        assertEquals(90, jumpFromZero.newStoredThreshold());
        assertEquals(90, jumpFromZero.notifyThreshold());
        assertEquals(95, jumpFromZero.percent());

        ProgressAlertLogic.Evaluation jumpFrom25 = ProgressAlertLogic.evaluate(80, 100, thresholds, 25, 10);
        assertEquals(75, jumpFrom25.newStoredThreshold());
        assertEquals(75, jumpFrom25.notifyThreshold());
        assertEquals(80, jumpFrom25.percent());
    }

    @Test
    void enforcesMinCountAndNeverAlertsForSingleItemOrCompletion() {
        // count == 1 is never eligible even if minCount <= 1
        assertFalse(ProgressAlertLogic.evaluate(0, 1, DEFAULT_THRESHOLDS, null, 1).shouldStore());
        assertFalse(ProgressAlertLogic.evaluate(1, 1, DEFAULT_THRESHOLDS, 0, 1).shouldNotify());

        // Below minCount (default 10): count = 2 and count = 9 are ignored
        assertFalse(ProgressAlertLogic.evaluate(1, 2, DEFAULT_THRESHOLDS, null, 10).shouldStore());
        assertFalse(ProgressAlertLogic.evaluate(1, 2, DEFAULT_THRESHOLDS, 0, 10).shouldNotify());
        assertFalse(ProgressAlertLogic.evaluate(5, 9, DEFAULT_THRESHOLDS, 0, 10).shouldNotify());

        // Exactly at minCount: eligible
        ProgressAlertLogic.Evaluation atMin = ProgressAlertLogic.evaluate(5, 10, DEFAULT_THRESHOLDS, 0, 10);
        assertEquals(50, atMin.newStoredThreshold());
        assertEquals(50, atMin.notifyThreshold());
        assertEquals(50, atMin.percent());

        // minCount = 2 allows count = 2
        ProgressAlertLogic.Evaluation minTwo = ProgressAlertLogic.evaluate(1, 2, DEFAULT_THRESHOLDS, 0, 2);
        assertEquals(50, minTwo.notifyThreshold());

        // At 100% or above: no alert and no baseline store (record confirmation covers it)
        assertFalse(ProgressAlertLogic.evaluate(100, 100, DEFAULT_THRESHOLDS, 0, 10).shouldNotify());
        assertFalse(ProgressAlertLogic.evaluate(100, 100, DEFAULT_THRESHOLDS, null, 10).shouldStore());
        assertFalse(ProgressAlertLogic.evaluate(150, 100, DEFAULT_THRESHOLDS, 50, 10).shouldNotify());
    }

    @Test
    void handlesEmptyOrInvalidThresholdsAndUnsortedInput() {
        assertFalse(ProgressAlertLogic.evaluate(50, 100, List.of(), null, 10).shouldStore());
        assertFalse(ProgressAlertLogic.evaluate(50, 100, Arrays.asList(0, 100, -1, null), null, 10).shouldStore());

        // Unsorted / duplicate thresholds still evaluate properly
        ProgressAlertLogic.Evaluation eval = ProgressAlertLogic.evaluate(
                92, 100, Arrays.asList(90, 50, 90, 0, 100), 0, 10);
        assertEquals(90, eval.newStoredThreshold());
        assertEquals(90, eval.notifyThreshold());
        assertEquals(92, eval.percent());
    }
}
