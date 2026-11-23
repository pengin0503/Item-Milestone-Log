package com.pengin0503.itemmilestonelog;

import javax.annotation.Nullable;

import java.util.UUID;

/**
 * F47: chooses the holder name shown for a world-first row in {@code /milestonelog firsts}.
 *
 * <p>Display only. Stored data, exports and the API's {@code WorldFirstRecord.playerName}
 * keep the record-time name.
 */
final class WorldFirstNames {

    private WorldFirstNames() {
    }

    /**
     * The chosen display name.
     *
     * @param name         the name to show (never null or empty)
     * @param recordedName the record-time name when the holder has been renamed since
     *                     (show {@code firsts_name_renamed}); {@code null} otherwise
     */
    record Choice(String name, @Nullable String recordedName) {
        boolean renamed() {
            return recordedName != null;
        }
    }

    /**
     * @param holderUuid     the world-first holder's UUID
     * @param recordedName   the name stored in the world-first record (may be null/empty)
     * @param hasPlayerData  whether saved data still holds PlayerData for the holder
     * @param lastKnownName  that PlayerData's {@code lastKnownName} (ignored when {@code hasPlayerData} is false)
     */
    static Choice choose(UUID holderUuid, @Nullable String recordedName,
                         boolean hasPlayerData, @Nullable String lastKnownName) {
        boolean hasRecorded = recordedName != null && !recordedName.isEmpty();
        if (hasPlayerData && lastKnownName != null && !lastKnownName.isEmpty()) {
            if (hasRecorded && !lastKnownName.equals(recordedName)) {
                return new Choice(lastKnownName, recordedName);
            }
            return new Choice(lastKnownName, null);
        }
        return new Choice(hasRecorded ? recordedName : holderUuid.toString(), null);
    }
}
