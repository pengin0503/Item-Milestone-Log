package com.pengin0503.itemmilestonelog.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** F-150-71 (N68): fourth key stays unbound and does not disturb the three existing defaults. */
class ClientCopyRecentKeyBindingTest {
    @Test
    void copyKeyIsUnboundAndNamedAfterTheLangKey() {
        assertEquals("key.itemmilestonelog.copy_recent", ItemMilestoneLogClient.KEY_COPY_RECENT);
        assertEquals(ItemMilestoneLogClient.KEY_COPY_RECENT,
                ItemMilestoneLogClient.COPY_RECENT_MAPPING.getName());
        assertEquals(InputConstants.UNKNOWN, ItemMilestoneLogClient.COPY_RECENT_MAPPING.getKey());
    }

    @Test
    void existingThreeKeyDefaultsAreUnchanged() {
        assertEquals("key.itemmilestonelog.open_gui", ItemMilestoneLogClient.KEY_OPEN_GUI);
        assertEquals("key.itemmilestonelog.add_hovered", ItemMilestoneLogClient.KEY_ADD_HOVERED);
        assertEquals("key.itemmilestonelog.mute_notification_sound",
                ItemMilestoneLogClient.KEY_MUTE_NOTIFICATION_SOUND);
        assertEquals(InputConstants.UNKNOWN, ItemMilestoneLogClient.OPEN_GUI_MAPPING.getKey());
        assertEquals(InputConstants.UNKNOWN, ItemMilestoneLogClient.ADD_HOVERED_MAPPING.getKey());
        assertEquals(InputConstants.UNKNOWN,
                ItemMilestoneLogClient.MUTE_NOTIFICATION_SOUND_MAPPING.getKey());
    }
}
