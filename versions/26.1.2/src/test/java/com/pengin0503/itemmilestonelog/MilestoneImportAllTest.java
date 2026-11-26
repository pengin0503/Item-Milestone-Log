package com.pengin0503.itemmilestonelog;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class MilestoneImportAllTest {
    @AfterEach
    void cleanup() {
        MilestoneImportAll.resetState();
        MilestoneIo.reset();
    }

    @Test
    void parseErrorStillRunsApplicationAndReleasesInFlightState() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        MilestoneImportAll.Job job =
                MilestoneImportAll.newJobForTest(Path.of("irrelevant.json"), "irrelevant.json");
        AtomicBoolean applyRan = new AtomicBoolean();

        assertTrue(MilestoneImportAll.submitForTest(
                job,
                j -> {
                    throw new StackOverflowError("deeply nested JSON");
                },
                runnable -> {
                    applyRan.set(true);
                    runnable.run();
                }));

        awaitReleased();
        assertTrue(applyRan.get(), "a parse Error must still schedule the apply step");
        assertNull(MilestoneImportAll.activeForTest());
        assertFalse(MilestoneIo.isImportInFlight(), "a parse Error must not leave importall busy");
    }

    @Test
    void unexpectedThrowableDuringHandOffReleasesStateWithoutTouchingFeedbackFromTheIoThread() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", source);
        AssertionError injected = new AssertionError("unexpected scheduling failure");
        AtomicReference<Thread> handOffThread = new AtomicReference<>();

        assertTrue(MilestoneImportAll.submitForTest(
                job,
                j -> {
                },
                runnable -> {
                    handOffThread.set(Thread.currentThread());
                    throw injected;
                }));

        awaitReleased();
        assertSame(injected, job.failure.get());
        assertNull(MilestoneImportAll.activeForTest());
        assertFalse(MilestoneIo.isImportInFlight());
        assertEquals(0, source.failureCount.get(),
                "the I/O thread must not call sendFailure on the CommandSourceStack");
        assertEquals(0, source.successCount.get());
        assertEquals(MilestoneIoQueue.THREAD_NAME, handOffThread.get().getName());
    }

    private static void awaitReleased() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (MilestoneIo.isImportInFlight() && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
    }

    /** Minimal CommandSourceStack whose feedback methods can be observed without a running server. */
    private static final class FakeSource extends CommandSourceStack {
        private final AtomicInteger failureCount = new AtomicInteger();
        private final AtomicInteger successCount = new AtomicInteger();

        private FakeSource() {
            super(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                    PermissionSet.NO_PERMISSIONS, "test", Component.literal("test"), null, null);
        }

        @Override
        public void sendFailure(Component message) {
            failureCount.incrementAndGet();
        }

        @Override
        public void sendSuccess(java.util.function.Supplier<Component> message, boolean allowLogging) {
            successCount.incrementAndGet();
        }
    }
}
