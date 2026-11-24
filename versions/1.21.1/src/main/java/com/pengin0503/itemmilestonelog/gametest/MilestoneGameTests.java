package com.pengin0503.itemmilestonelog.gametest;

import com.pengin0503.itemmilestonelog.MilestoneCommandRunner;
import com.pengin0503.itemmilestonelog.MilestoneData;
import com.pengin0503.itemmilestonelog.MilestoneService;
import com.pengin0503.itemmilestonelog.Tracker;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Shared GameTest bodies verifying server-side milestone recording and creative-carry filtering
 * on a real {@link MinecraftServer} with mock {@link ServerPlayer} instances.
 */
public final class MilestoneGameTests {
    private static final TargetKey DIAMOND_TARGET = TargetKey.of("minecraft:diamond");
    private static final TargetKey EMERALD_TARGET = TargetKey.of("minecraft:emerald");

    private MilestoneGameTests() {
    }

    /**
     * Gives a mock player a tracked target item in their inventory, invokes the tracker's
     * inventory scan path, and asserts that the milestone is recorded in {@link MilestoneData}.
     */
    public static void scanRecordsTargetInInventory(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MilestoneData data = MilestoneData.get(server);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            MilestoneService.ActionResult added = MilestoneService.addTarget(
                    player, DIAMOND_TARGET.itemId(), DIAMOND_TARGET.count());
            McCompat.gameTest().assertTrue(helper, added.success(), "Expected addTarget to succeed for diamond");
            McCompat.gameTest().assertFalse(helper,
                    data.player(player.getUUID()).records.containsKey(DIAMOND_TARGET),
                    "Target must not be recorded before inventory scan");

            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 1));
            Tracker.scanInventory(player);

            MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
            McCompat.gameTest().assertTrue(helper, pd != null, "Expected PlayerData to exist after scan");
            MilestoneRecord record = pd.records.get(DIAMOND_TARGET);
            McCompat.gameTest().assertTrue(helper, record != null, "Expected inventory scan to record diamond milestone in MilestoneData");
            McCompat.gameTest().assertTrue(helper, record.playTicks() >= 0, "Expected recorded playTicks to be non-negative");
        } finally {
            cleanup(data, player, DIAMOND_TARGET);
        }
        helper.succeed();
    }

    /**
     * Gives a mock player in Creative mode a tracked target item, switches them to Survival mode
     * so {@link Tracker#onGameModeChange} marks the carried item in {@code carriedFromCreative},
     * runs the inventory scan path, and asserts that the milestone is NOT recorded.
     */
    public static void creativeCarryIsNotRecordedByScan(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MilestoneData data = MilestoneData.get(server);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.setGameMode(GameType.CREATIVE);
            MilestoneService.ActionResult added = MilestoneService.addTarget(
                    player, EMERALD_TARGET.itemId(), EMERALD_TARGET.count());
            McCompat.gameTest().assertTrue(helper, added.success(), "Expected addTarget to succeed for emerald");

            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 1));
            boolean modeChanged = player.setGameMode(GameType.SURVIVAL);
            McCompat.gameTest().assertTrue(helper, modeChanged, "Expected transition from CREATIVE to SURVIVAL to succeed");

            MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
            McCompat.gameTest().assertTrue(helper,
                    pd != null && pd.carriedFromCreative.contains(EMERALD_TARGET.itemId()),
                    "Expected leaving creative mode to mark emerald in carriedFromCreative");

            Tracker.scanInventory(player);

            McCompat.gameTest().assertFalse(helper,
                    pd.records.containsKey(EMERALD_TARGET),
                    "Expected inventory scan not to record an item carried from creative mode");
            McCompat.gameTest().assertTrue(helper,
                    pd.carriedFromCreative.contains(EMERALD_TARGET.itemId()),
                    "Expected carriedFromCreative mark to remain while the item stays in inventory");
        } finally {
            cleanup(data, player, EMERALD_TARGET);
        }
        helper.succeed();
    }

    private static void cleanup(MilestoneData data, ServerPlayer player, TargetKey target) {
        player.getInventory().clearContent();
        data.purgePlayer(player.getUUID());
        data.removeWorldFirst(target);
        Tracker.clearPendingNotifications();
        MilestoneCommandRunner.clearPending();
    }
}
