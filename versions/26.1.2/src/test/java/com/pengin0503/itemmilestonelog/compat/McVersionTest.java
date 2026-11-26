package com.pengin0503.itemmilestonelog.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** #125: pre-release names use a hyphen, so they must still select their 26.x line. */
class McVersionTest {
    @Test
    void releaseAndPatchNamesResolveToTheirLine() {
        assertEquals(McVersion.V26_1, McVersion.detect("26.1"));
        assertEquals(McVersion.V26_1, McVersion.detect("26.1.2"));
        assertEquals(McVersion.V26_2, McVersion.detect("26.2"));
        assertEquals(McVersion.V26_3, McVersion.detect("26.3.4"));
    }

    @Test
    void hyphenatedPrereleaseNamesResolveToTheirLine() {
        assertEquals(McVersion.V26_1, McVersion.detect("26.1-pre1"));
        assertEquals(McVersion.V26_1, McVersion.detect("26.1.2-pre2"));
        assertEquals(McVersion.V26_2, McVersion.detect("26.2-pre1"));
        assertEquals(McVersion.V26_3, McVersion.detect("26.3-pre2"));
    }

    @Test
    void unknownNullAndOtherLinesStayUnknown() {
        assertEquals(McVersion.UNKNOWN, McVersion.detect(null));
        assertEquals(McVersion.UNKNOWN, McVersion.detect(""));
        assertEquals(McVersion.UNKNOWN, McVersion.detect("26.4"));
        assertEquals(McVersion.UNKNOWN, McVersion.detect("27.1-pre1"));
    }
}
