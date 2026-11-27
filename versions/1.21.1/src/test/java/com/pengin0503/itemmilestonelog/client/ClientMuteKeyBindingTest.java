package com.pengin0503.itemmilestonelog.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** F-150-50 (N121): the optional mute key stays unbound by default, like the two existing keys. */
class ClientMuteKeyBindingTest {
    @Test
    void muteKeyIsUnboundByDefaultAndUsesTheLocalisedName() {
        assertEquals("key.itemmilestonelog.mute_notification_sound",
                ItemMilestoneLogClient.KEY_MUTE_NOTIFICATION_SOUND);
        assertEquals(ItemMilestoneLogClient.KEY_MUTE_NOTIFICATION_SOUND,
                ItemMilestoneLogClient.MUTE_NOTIFICATION_SOUND_MAPPING.getName());
        assertEquals(InputConstants.UNKNOWN,
                ItemMilestoneLogClient.MUTE_NOTIFICATION_SOUND_MAPPING.getKey());
    }
}
