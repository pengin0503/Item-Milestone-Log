package com.pengin0503.itemmilestonelog.compat.client;

import java.nio.file.Path;

/**
 * F-150-70 (N67): client-only screenshot hook. The Minecraft screenshot API changes between
 * 1.20.x / 1.21.x / 26.x (and inside the 1.21.x line), so this interface deliberately exposes
 * only pure-Java types and every version-varying call lives in a profile-scoped implementation.
 * Shared/merged-jar code never references a screenshot API directly.
 */
public interface ScreenshotCompat {
    /** Captures one image into {@code clientDir/screenshots/<fileName>}. */
    boolean capture(Path clientDir, String fileName);
}
