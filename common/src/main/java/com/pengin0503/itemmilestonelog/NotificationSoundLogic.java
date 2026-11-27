package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F-150-47 / F-150-48 (N15 / N16): the single definition of notification-sound selection.
 *
 * <p>One player gets at most one sound per notification window; competing events resolve through
 * the priority {@code WORLD_FIRST > PRESET_COMPLETE > RECORD > PROGRESS_ALERT} (D-150-125). An
 * empty per-event config ID reuses {@code milestoneSound}, so 1.4.0 keeps its single sound for the
 * events that already had one. A progress-alert-only window stays silent unless
 * {@code progressAlertSound} is explicitly configured — that keeps the default behaviour
 * byte-for-byte identical (no new sound appears where 1.4.0 was silent).
 *
 * <p>Pure Java 21, no Minecraft/NeoForge dependency. The version layer turns each
 * {@link SoundRequest} into a vanilla sound packet carrying {@link SoundVolumeLogic} volume.
 */
public final class NotificationSoundLogic {
    /** The four soundable events, in ascending priority order. */
    public enum Event {
        PROGRESS_ALERT,
        RECORD,
        PRESET_COMPLETE,
        WORLD_FIRST
    }

    /** One resolved sound packet to send: who hears which ID at which 0..100 volume. */
    public record SoundRequest(UUID recipient, Event event, String soundId, int volume, boolean announcement) {
    }

    /** Immutable snapshot of everything the version layer knows about one window. */
    public record SoundContext(
            UUID actorId,
            boolean masterEnabled,
            boolean actorMuted,
            boolean hasRecord,
            boolean hasWorldFirst,
            boolean hasPresetComplete,
            boolean hasProgressAlert,
            boolean progressAlertsEnabled,
            boolean actorProgressMuted,
            boolean announceWorldFirst,
            boolean announceWorldFirstSound,
            List<UUID> worldFirstChatRecipients,
            Set<UUID> mutedBroadcastRecipients,
            Set<UUID> mutedSoundRecipients,
            Map<UUID, Integer> personalVolumes,
            String milestoneSoundId,
            String worldFirstSoundId,
            String presetCompleteSoundId,
            String progressAlertSoundId,
            int serverVolume) {
    }

    private NotificationSoundLogic() {
    }

    /** Highest-priority event among the supplied candidates; {@code null} when none are present. */
    public static Event select(Event... candidates) {
        Event best = null;
        if (candidates != null) {
            for (Event candidate : candidates) {
                if (candidate != null && (best == null || candidate.ordinal() > best.ordinal())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Per-event sound ID, falling back to {@code milestoneSound} when the event ID is empty/blank.
     * Returns {@code null} when neither the event nor the legacy sound ID names anything.
     */
    public static String resolveSoundId(Event event, String milestoneSoundId, String worldFirstSoundId,
                                        String presetCompleteSoundId, String progressAlertSoundId) {
        if (event == null) {
            return null;
        }
        String chosen = switch (event) {
            case WORLD_FIRST -> worldFirstSoundId;
            case PRESET_COMPLETE -> presetCompleteSoundId;
            case PROGRESS_ALERT -> progressAlertSoundId;
            case RECORD -> null;
        };
        if (chosen == null || chosen.isBlank()) {
            chosen = milestoneSoundId;
        }
        return chosen == null || chosen.isBlank() ? null : chosen;
    }

    /**
     * Plans all sound packets for one window: at most one for the actor (priority-selected) plus,
     * when the opt-in is on, one for each recipient of the world-first announcement.
     */
    public static List<SoundRequest> plan(SoundContext c) {
        if (c == null || !c.masterEnabled()) {
            return List.of();
        }
        List<SoundRequest> requests = new ArrayList<>();
        boolean actorMuted = c.actorMuted()
                || (c.mutedSoundRecipients() != null && c.mutedSoundRecipients().contains(c.actorId()));

        Event event = select(
                c.hasWorldFirst() ? Event.WORLD_FIRST : null,
                c.hasPresetComplete() ? Event.PRESET_COMPLETE : null,
                c.hasRecord() ? Event.RECORD : null,
                c.hasProgressAlert() && c.progressAlertsEnabled() && !c.actorProgressMuted()
                        && c.progressAlertSoundId() != null && !c.progressAlertSoundId().isBlank()
                        ? Event.PROGRESS_ALERT : null);
        if (event != null && !actorMuted) {
            String id = resolveSoundId(event, c.milestoneSoundId(), c.worldFirstSoundId(),
                    c.presetCompleteSoundId(), c.progressAlertSoundId());
            if (id != null) {
                requests.add(new SoundRequest(c.actorId(), event, id, volumeFor(c, c.actorId()), false));
            }
        }

        if (c.announceWorldFirst() && c.announceWorldFirstSound() && c.hasWorldFirst()
                && c.worldFirstChatRecipients() != null) {
            String id = resolveSoundId(Event.WORLD_FIRST, c.milestoneSoundId(), c.worldFirstSoundId(),
                    c.presetCompleteSoundId(), c.progressAlertSoundId());
            if (id != null) {
                boolean actorAlreadyServed = requests.stream()
                        .anyMatch(r -> r.recipient() != null && r.recipient().equals(c.actorId()));
                for (UUID recipient : c.worldFirstChatRecipients()) {
                    if (recipient == null) {
                        continue;
                    }
                    if (actorMuted && recipient.equals(c.actorId())) {
                        continue; // a muted actor hears neither its own sound nor the announcement copy
                    }
                    if (c.mutedSoundRecipients() != null && c.mutedSoundRecipients().contains(recipient)) {
                        continue;
                    }
                    if (c.mutedBroadcastRecipients() != null && c.mutedBroadcastRecipients().contains(recipient)) {
                        continue;
                    }
                    if (actorAlreadyServed && recipient.equals(c.actorId())) {
                        continue; // never send the actor two sounds for the same world-first batch
                    }
                    requests.add(new SoundRequest(recipient, Event.WORLD_FIRST, id,
                            volumeFor(c, recipient), true));
                }
            }
        }
        return List.copyOf(requests);
    }

    private static int volumeFor(SoundContext c, UUID recipient) {
        Integer personal = c.personalVolumes() != null ? c.personalVolumes().get(recipient) : null;
        return SoundVolumeLogic.resolve(
                personal != null ? personal : SoundVolumeLogic.FOLLOW_SERVER, c.serverVolume());
    }
}
