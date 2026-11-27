package com.pengin0503.itemmilestonelog;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MN-3: {@code discordtest} completion handoff must be strictly server-thread-only and must emit
 * an explicit abandoned outcome when delivery is impossible. Kept in a version-local class because
 * the {@code CommandSourceStack} constructor differs between 1.21.1 and the 26.x trees.
 */
class DiscordTestHandoffTest {
    @AfterEach
    void resetHooks() {
        MilestoneCommands.resetDiscordTestHooksForTest();
    }

    // MN-3: a completion cannot be delivered when the Discord executor is gone.
    @Test
    void discordtestResultIsAbandonedWhenTheExecutorIsGone() {
        List<DiscordTestResult> abandoned = new ArrayList<>();
        AtomicInteger scheduled = new AtomicInteger();
        try {
            MilestoneCommands.discordExecutorAliveForTest = false;
            MilestoneCommands.discordTestAbandonedForTest = abandoned::add;
            MilestoneCommands.discordTestSchedulerForTest = runnable -> {
                scheduled.incrementAndGet();
                runnable.run();
            };
            MilestoneCommands.handOffDiscordTestResult(null, null, null, null,
                    new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"));
            assertEquals(0, scheduled.get(), "no delivery may be attempted without an executor");
            assertEquals(1, abandoned.size());
            assertEquals(DiscordTestResult.Kind.ABANDONED, abandoned.get(0).kind());
            assertNull(abandoned.get(0).detail(), "an abandoned outcome must not carry a detail");
        } finally {
            MilestoneCommands.resetDiscordTestHooksForTest();
        }
    }

    // MN-3: an inline execution on the Discord thread must not touch the command source.
    @Test
    void inlineDiscordtestDeliveryOffTheServerThreadIsAbandoned() {
        List<DiscordTestResult> abandoned = new ArrayList<>();
        AtomicInteger scheduled = new AtomicInteger();
        try {
            MilestoneCommands.discordExecutorAliveForTest = true;
            MilestoneCommands.discordTestOnServerThreadForTest = false;
            MilestoneCommands.discordTestAbandonedForTest = abandoned::add;
            MilestoneCommands.discordTestSchedulerForTest = runnable -> {
                scheduled.incrementAndGet();
                runnable.run(); // models server.execute running inline during shutdown
            };
            // A null source is deliberate: if the guarded runnable tried to deliver, this test
            // would fail loudly instead of silently touching off-thread state.
            MilestoneCommands.handOffDiscordTestResult(null, null, null, null,
                    new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"));
            assertEquals(1, scheduled.get());
            assertEquals(1, abandoned.size());
            assertEquals(DiscordTestResult.Kind.ABANDONED, abandoned.get(0).kind());
        } finally {
            MilestoneCommands.resetDiscordTestHooksForTest();
        }
    }

    // MN-3: the normal path still delivers exactly once on the server thread.
    @Test
    void discordtestCompletionIsDeliveredOnceOnTheServerThread() {
        List<DiscordTestResult> abandoned = new ArrayList<>();
        AtomicInteger scheduled = new AtomicInteger();
        FakeDiscordSource source = new FakeDiscordSource();
        try {
            MilestoneCommands.discordExecutorAliveForTest = true;
            MilestoneCommands.discordTestOnServerThreadForTest = true;
            MilestoneCommands.discordTestAbandonedForTest = abandoned::add;
            MilestoneCommands.discordTestSchedulerForTest = runnable -> {
                scheduled.incrementAndGet();
                runnable.run();
            };
            MilestoneCommands.handOffDiscordTestResult(null, source, null, null,
                    new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"));
            assertEquals(1, scheduled.get());
            assertEquals(1, source.successCount);
            assertEquals(0, source.failureCount);
            assertTrue(abandoned.isEmpty());
        } finally {
            MilestoneCommands.resetDiscordTestHooksForTest();
        }
    }

    private static final class FakeDiscordSource extends CommandSourceStack {
        private int successCount;
        private int failureCount;

        private FakeDiscordSource() {
            super(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 0, "test",
                    Component.literal("test"), null, null);
        }

        @Override
        public void sendFailure(Component message) {
            failureCount++;
        }

        @Override
        public void sendSuccess(java.util.function.Supplier<Component> message, boolean allowLogging) {
            successCount++;
        }
    }
}
