package com.pengin0503.itemmilestonelog;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * F-150-49 (N114): the single definition of notification-volume precedence.
 *
 * <p>The server embeds the effective volume into the vanilla {@code ClientboundSoundPacket}, so a
 * vanilla client hears the configured volume without a client mod. A personal value of
 * {@link #FOLLOW_SERVER} (also the value for missing/legacy SavedData) inherits the server default.
 * Pure Java 21, no Minecraft/NeoForge dependency; shared by all five version trees.
 */
public final class SoundVolumeLogic {
    /** Personal "follow the server default" sentinel; also the fallback for missing/legacy data. */
    public static final int FOLLOW_SERVER = -1;
    public static final int MIN_VOLUME = 0;
    public static final int MAX_VOLUME = 100;
    public static final int DEFAULT_SERVER_VOLUME = 100;

    private static final Logger LOGGER = Logger.getLogger(SoundVolumeLogic.class.getName());
    private static final AtomicBoolean WARNED_PERSONAL = new AtomicBoolean(false);
    private static final AtomicBoolean WARNED_SERVER = new AtomicBoolean(false);

    private SoundVolumeLogic() {
    }

    /** Whether an integer is a legal stored personal value ({@code -1} or 0..100). */
    public static boolean isStoredPersonal(int value) {
        return value == FOLLOW_SERVER || (value >= MIN_VOLUME && value <= MAX_VOLUME);
    }

    /**
     * Lenient SavedData read: any out-of-range or non-numeric value becomes {@link #FOLLOW_SERVER}
     * with at most one warning per JVM, so old/edited files never lose their other fields.
     */
    public static int normalizePersonal(Object raw) {
        if (raw instanceof Number number) {
            long value = number.longValue();
            if (value == FOLLOW_SERVER || (value >= MIN_VOLUME && value <= MAX_VOLUME)) {
                return (int) value;
            }
        }
        warnOnce(WARNED_PERSONAL,
                "[ItemMilestoneLog] Invalid soundVolume '" + raw + "' in SavedData; following the server default");
        return FOLLOW_SERVER;
    }

    /** Clamps a server-config value into 0..100; invalid values fall back to 100 (vanilla loudness). */
    public static int normalizeServer(int value) {
        if (value < MIN_VOLUME || value > MAX_VOLUME) {
            warnOnce(WARNED_SERVER,
                    "[ItemMilestoneLog] Invalid notificationSoundVolume " + value + "; using 100");
            return DEFAULT_SERVER_VOLUME;
        }
        return value;
    }

    /** The one precedence rule: personal 0..100 wins, {@code -1} (or invalid) inherits the server. */
    public static int resolve(int personalVolume, int serverVolume) {
        int server = normalizeServer(serverVolume);
        return isStoredPersonal(personalVolume) && personalVolume >= MIN_VOLUME ? personalVolume : server;
    }

    /** Converts a resolved 0..100 integer into the vanilla packet volume (100 -> 1.0F). */
    public static float packetVolume(int effectiveVolume) {
        return normalizeServer(effectiveVolume) / (float) MAX_VOLUME;
    }

    /** Test seam for the warn-once guarantee; production code never calls this. */
    static void resetWarningStateForTests() {
        WARNED_PERSONAL.set(false);
        WARNED_SERVER.set(false);
    }

    private static void warnOnce(AtomicBoolean warned, String message) {
        if (warned.compareAndSet(false, true)) {
            LOGGER.warning(message);
        }
    }
}
