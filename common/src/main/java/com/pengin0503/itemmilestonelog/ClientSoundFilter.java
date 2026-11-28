package com.pengin0503.itemmilestonelog;

/**
 * F-150-81 (N194): client-side on/off filters for the four notification sounds.
 *
 * <p>This is a second filter dimension, not a second volume path: {@link SoundVolumeLogic},
 * {@code PlayerData.soundVolume} and the server volume keys stay untouched (D-150-223). The
 * version layer passes the sound id carried by the received packet; an unknown id is always
 * allowed through so custom server ids never go silent (D-150-224).
 */
public final class ClientSoundFilter {
    /** Four independent flags; shipped defaults keep every sound audible. */
    public record Flags(boolean recorded, boolean worldFirst, boolean presetComplete,
                        boolean progressAlert) {
        public static Flags defaults() {
            return new Flags(true, true, true, true);
        }

        public boolean enabled(NotificationSoundLogic.Event event) {
            if (event == null) {
                return true; // unknown ids are never silenced
            }
            return switch (event) {
                case RECORD -> recorded;
                case WORLD_FIRST -> worldFirst;
                case PRESET_COMPLETE -> presetComplete;
                case PROGRESS_ALERT -> progressAlert;
            };
        }
    }

    private ClientSoundFilter() {
    }

    /**
     * Maps a received sound id to one of the four existing events using the same fallback rule as
     * {@link NotificationSoundLogic#resolveSoundId}: an empty per-event id reuses {@code milestoneId}
     * (so a server with only the legacy id reports RECORD). A progress-alert id is never inherited
     * from {@code milestoneId}, because progress alerts are silent unless explicitly configured.
     *
     * @return the matching event, or {@code null} for an unknown/blank id (never filtered)
     */
    public static NotificationSoundLogic.Event categorize(String receivedId, String milestoneId,
                                                          String worldFirstId, String presetCompleteId,
                                                          String progressAlertId) {
        if (receivedId == null || receivedId.isBlank()) {
            return null;
        }
        String base = blankToNull(milestoneId);
        if (equalsId(receivedId, blankToNull(worldFirstId))) {
            return NotificationSoundLogic.Event.WORLD_FIRST;
        }
        if (equalsId(receivedId, blankToNull(presetCompleteId))) {
            return NotificationSoundLogic.Event.PRESET_COMPLETE;
        }
        if (equalsId(receivedId, blankToNull(progressAlertId))) {
            return NotificationSoundLogic.Event.PROGRESS_ALERT;
        }
        if (equalsId(receivedId, base)) {
            return NotificationSoundLogic.Event.RECORD;
        }
        return null;
    }

    /**
     * Final playback decision. The mute key is the higher-priority flag and stops every known
     * milestone sound; unknown ids are unaffected.
     */
    public static boolean shouldPlay(String receivedId, Flags flags, boolean muted,
                                     String milestoneId, String worldFirstId,
                                     String presetCompleteId, String progressAlertId) {
        NotificationSoundLogic.Event event = categorize(receivedId, milestoneId, worldFirstId,
                presetCompleteId, progressAlertId);
        if (event == null) {
            return true;
        }
        if (muted) {
            return false;
        }
        return (flags == null ? Flags.defaults() : flags).enabled(event);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean equalsId(String received, String configured) {
        if (configured == null || received == null) {
            return false;
        }
        if (configured.equals(received)) {
            return true;
        }
        // PlaySoundEvent.getName() exposes only the path; Config ids are fully qualified.
        int colon = configured.indexOf(':');
        return colon >= 0 && configured.substring(colon + 1).equals(received);
    }
}
