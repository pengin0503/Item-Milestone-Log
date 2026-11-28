package com.pengin0503.itemmilestonelog.compat.client;

/**
 * F-150-71 (N68): clipboard write seam. The call into Minecraft lives only in the per-profile
 * implementations, so shared/merged-jar classes never link a version-varying client API
 * (D-150-204). Client-only.
 */
public interface ClipboardCompat {
    /** Returns true when the text was handed to the clipboard successfully. */
    boolean setClipboard(String value);
}
