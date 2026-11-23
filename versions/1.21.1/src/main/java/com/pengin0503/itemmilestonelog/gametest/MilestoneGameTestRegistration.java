package com.pengin0503.itemmilestonelog.gametest;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * NeoForge 1.21.1 GameTest registration wrapper ({@link GameTestHolder} / {@link RegisterGameTestsEvent}).
 * Delegates all test bodies to {@link MilestoneGameTests}.
 */
@GameTestHolder(ItemMilestoneLog.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MilestoneGameTestRegistration {
    private static final String EMPTY_TEMPLATE = "empty";

    private MilestoneGameTestRegistration() {
    }

    public static void register(RegisterGameTestsEvent event) {
        event.register(MilestoneGameTestRegistration.class);
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void scanRecordsTargetInInventory(GameTestHelper helper) {
        MilestoneGameTests.scanRecordsTargetInInventory(helper);
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void creativeCarryIsNotRecordedByScan(GameTestHelper helper) {
        MilestoneGameTests.creativeCarryIsNotRecordedByScan(helper);
    }
}
