package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.HoverShortcutPolicy;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PR-2: the four hover shortcuts dispatch only to existing C2S actions and ordinals. */
class HoverShortcutDispatchTest {
    @Test
    void everyShortcutResolvesToAnExistingActionOrdinal() {
        for (HoverShortcutPolicy.Operation operation : HoverShortcutPolicy.Operation.values()) {
            MilestoneActionPayload.Action action = ClientMilestoneHandler.actionFor(operation);
            assertEquals(operation.actionOrdinal(), action.ordinal(), operation.name());
        }
        assertEquals(MilestoneActionPayload.Action.ADD_TARGET,
                ClientMilestoneHandler.actionFor(HoverShortcutPolicy.Operation.ADD_COUNT_ONE));
        assertEquals(MilestoneActionPayload.Action.ADD_TARGET,
                ClientMilestoneHandler.actionFor(HoverShortcutPolicy.Operation.ADD_STACK_COUNT));
        assertEquals(MilestoneActionPayload.Action.REMOVE_TARGET,
                ClientMilestoneHandler.actionFor(HoverShortcutPolicy.Operation.REMOVE_TARGET));
        assertEquals(MilestoneActionPayload.Action.ADD_HAND,
                ClientMilestoneHandler.actionFor(HoverShortcutPolicy.Operation.ADD_HAND));
    }

    @Test
    void actionOrdinalsRemainTheOriginalNineWithNoTailAddition() {
        MilestoneActionPayload.Action[] values = MilestoneActionPayload.Action.values();
        assertEquals(9, values.length);
        assertEquals(List.of("ADD_TARGET", "ADD_HAND", "REMOVE_TARGET", "APPLY_PRESET",
                        "RESET_RECORD", "RESET_ALL", "SAVE_PRESET", "DELETE_PRESET",
                        "MUTE_NOTIFICATION_SOUND"),
                Arrays.stream(values).map(MilestoneActionPayload.Action::name).toList());
        assertEquals(0, MilestoneActionPayload.Action.ADD_TARGET.ordinal());
        assertEquals(1, MilestoneActionPayload.Action.ADD_HAND.ordinal());
        assertEquals(2, MilestoneActionPayload.Action.REMOVE_TARGET.ordinal());
        assertEquals(8, MilestoneActionPayload.Action.MUTE_NOTIFICATION_SOUND.ordinal());
    }
}
