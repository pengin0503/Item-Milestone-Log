package com.pengin0503.itemmilestonelog.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * #118: the 1.21.9+ fallback path must never let an uninitialized
 * {@code SharedConstants#getCurrentVersion()} crash enum initialization.
 */
class McVersionTest {
    @Test
    void runtimeExceptionFromGameVersionLookupFallsBackToNull() {
        assertNull(McVersion.versionNameFrom(() -> {
            throw new IllegalStateException("Game version not set");
        }));
    }

    @Test
    void linkErrorFromGameVersionLookupFallsBackToNull() {
        assertNull(McVersion.versionNameFrom(() -> {
            throw new NoClassDefFoundError("net/minecraft/WorldVersion");
        }));
    }

    @Test
    void nullDetectedVersionFallsBackToNull() {
        assertNull(McVersion.versionNameFrom(() -> null));
    }

    @Test
    void newerNameAccessorIsUsedWhenPresentAndLegacyGetterOtherwise() {
        assertEquals("1.21.9", McVersion.versionNameFrom(NamedVersion::new));
        assertEquals("1.21.8", McVersion.versionNameFrom(LegacyNamedVersion::new));
    }

    public static final class NamedVersion {
        public String name() {
            return "1.21.9";
        }
    }

    public static final class LegacyNamedVersion {
        public String getName() {
            return "1.21.8";
        }
    }
}
