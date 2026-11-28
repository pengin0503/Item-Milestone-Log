package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-81 (N194): four sound ids, default-on flags, mute as the top-level gate. */
class ClientSoundFilterTest {
    private static final String BASE = "minecraft:ui.toast.challenge_complete";

    @Test
    void defaultsKeepAllFourSoundsAudible() {
        ClientSoundFilter.Flags flags = ClientSoundFilter.Flags.defaults();
        for (NotificationSoundLogic.Event event : NotificationSoundLogic.Event.values()) {
            assertTrue(flags.enabled(event), event.name());
        }
        assertEquals(4, NotificationSoundLogic.Event.values().length);
    }

    @Test
    void matchesEveryConfiguredSoundId() {
        String wf = "minecraft:entity.player.levelup";
        String pc = "minecraft:ui.toast.challenge_complete.preset";
        String pa = "minecraft:block.note_block.bell";
        assertEquals(NotificationSoundLogic.Event.WORLD_FIRST,
                ClientSoundFilter.categorize(wf, BASE, wf, pc, pa));
        assertEquals(NotificationSoundLogic.Event.PRESET_COMPLETE,
                ClientSoundFilter.categorize(pc, BASE, wf, pc, pa));
        assertEquals(NotificationSoundLogic.Event.PROGRESS_ALERT,
                ClientSoundFilter.categorize(pa, BASE, wf, pc, pa));
        assertEquals(NotificationSoundLogic.Event.RECORD,
                ClientSoundFilter.categorize(BASE, BASE, wf, pc, pa));
    }

    @Test
    void unknownIdsAreNeverSilenced() {
        assertTrue(ClientSoundFilter.shouldPlay("custom:unknown", new ClientSoundFilter.Flags(
                false, false, false, false), true, BASE, "", "", ""),
                "unknown ids bypass both the flags and the mute gate (D-150-224)");
        assertTrue(ClientSoundFilter.shouldPlay("custom:unknown", ClientSoundFilter.Flags.defaults(),
                false, BASE, "", "", ""));
        assertEquals(null, ClientSoundFilter.categorize(null, BASE, "", "", ""));
    }

    @Test
    void emptyEventIdsReuseTheLegacySoundAndMuteOverridesEverything() {
        assertEquals(NotificationSoundLogic.Event.RECORD,
                ClientSoundFilter.categorize(BASE, BASE, "", "", ""));
        assertEquals(NotificationSoundLogic.Event.RECORD,
                ClientSoundFilter.categorize("ui.toast.challenge_complete", BASE, "", "", ""),
                "PlaySoundEvent.getName() reports the path only");
        assertFalse(ClientSoundFilter.shouldPlay(BASE, ClientSoundFilter.Flags.defaults(),
                true, BASE, "", "", ""));
        ClientSoundFilter.Flags off = new ClientSoundFilter.Flags(true, false, true, true);
        assertFalse(ClientSoundFilter.shouldPlay("minecraft:entity.player.levelup", off,
                false, BASE, "minecraft:entity.player.levelup", "", ""));
        assertTrue(ClientSoundFilter.shouldPlay(BASE, off, false, BASE, "", "", ""));
    }
}
