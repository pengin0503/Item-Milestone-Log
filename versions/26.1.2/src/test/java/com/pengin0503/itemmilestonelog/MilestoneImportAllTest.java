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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.nio.file.Path;
import java.util.UUID;
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

    @Test
    void feedbackTargetPinsLogoutRelogAndConsoleRouting() {
        // The executor logged out during the async parse: no stale success/failure packet.
        assertEquals(MilestoneImportAll.FeedbackTarget.NONE,
                MilestoneImportAll.feedbackTarget(true, false, false));
        // The executor relogged as a different ServerPlayer instance: that player is notified.
        assertEquals(MilestoneImportAll.FeedbackTarget.ONLINE_PLAYER,
                MilestoneImportAll.feedbackTarget(true, true, false));
        // Same-session player: keep using the original command source (language, permissions).
        assertEquals(MilestoneImportAll.FeedbackTarget.SOURCE,
                MilestoneImportAll.feedbackTarget(true, true, true));
        // Console/RCON has no executor UUID: feedback goes to the source.
        assertEquals(MilestoneImportAll.FeedbackTarget.SOURCE,
                MilestoneImportAll.feedbackTarget(false, false, false));
    }

    @Test
    void resolveOnlineExecutorReResolvesByUuidAndSkipsConsoleJobs() {
        UUID uuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AtomicReference<UUID> lookedUp = new AtomicReference<>();
        MilestoneImportAll.Job job = new MilestoneImportAll.Job(
                null, Path.of("irrelevant.json"), "irrelevant.json", null, uuid);
        job.onlineLookupForTest = id -> {
            lookedUp.set(id);
            return null; // offline executor
        };
        assertNull(MilestoneImportAll.resolveOnlineExecutor(job));
        assertEquals(uuid, lookedUp.get(), "the re-resolution must use the captured UUID");

        AtomicBoolean lookupRan = new AtomicBoolean();
        MilestoneImportAll.Job console = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", new FakeSource());
        console.onlineLookupForTest = id -> {
            lookupRan.set(true);
            return null;
        };
        assertNull(MilestoneImportAll.resolveOnlineExecutor(console));
        assertFalse(lookupRan.get(), "console execution must not resolve an online player");
    }

    @Test
    void loggedOutExecutorGetsNoFeedbackOnEitherOutcome() {
        UUID uuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = new MilestoneImportAll.Job(
                null, Path.of("irrelevant.json"), "irrelevant.json", source, uuid);
        job.onlineLookupForTest = id -> null;

        MilestoneImportAll.sendSuccess(job, MilestoneImportAll.resolveOnlineExecutor(job),
                Component.literal("done"));
        MilestoneImportAll.sendFailure(job, new IOException("parse failed"));
        assertEquals(0, source.successCount.get(), "a stale source must not receive success feedback");
        assertEquals(0, source.failureCount.get(), "a stale source must not receive failure feedback");
    }

    @Test
    void consoleExecutionSendsSuccessAndFailureToTheSource() {
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", source);

        MilestoneImportAll.sendSuccess(job, null, Component.literal("done"));
        assertEquals(1, source.successCount.get());
        MilestoneImportAll.sendFailure(job, new IOException("parse failed"));
        assertEquals(1, source.failureCount.get());
    }

    @Test
    void asyncApplyIsHandedToTheSchedulerAndRunsOnlyOnce() throws Exception {
        // A real MergePlan needs a live MinecraftServer, which the harness cannot construct.
        // This pins the hand-off and the once-only apply release with a plan-less parse.
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", source);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        AtomicReference<String> schedulerThread = new AtomicReference<>();
        assertTrue(MilestoneImportAll.submitForTest(
                job,
                j -> {
                },
                runnable -> {
                    schedulerThread.set(Thread.currentThread().getName());
                    scheduled.set(runnable);
                }));
        awaitParseDone(job);
        assertTrue(scheduled.get() != null, "the apply step must be handed to the server scheduler");
        assertEquals(MilestoneIoQueue.THREAD_NAME, schedulerThread.get(),
                "hand-off must happen on the I/O thread, not the caller");
        assertTrue(MilestoneImportAll.activeForTest() == job,
                "state stays in flight until the scheduled apply runs");

        scheduled.get().run();
        assertTrue(job.applied.get());
        assertNull(MilestoneImportAll.activeForTest());
        assertFalse(MilestoneIo.isImportInFlight());
        int failuresAfterFirst = source.failureCount.get();
        scheduled.get().run();
        assertEquals(failuresAfterFirst, source.failureCount.get(), "apply must be idempotent");
    }

    @Test
    void secondSubmitIsRejectedWhileOneJobIsActive() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeSource firstSource = new FakeSource();
        FakeSource secondSource = new FakeSource();
        MilestoneImportAll.Job first = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", firstSource);
        assertTrue(MilestoneImportAll.submitForTest(first, j -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
        }, runnable -> {
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            MilestoneImportAll.Job second = MilestoneImportAll.newJobForTest(
                    Path.of("irrelevant.json"), "irrelevant.json", secondSource);
            assertFalse(MilestoneImportAll.submitForTest(second, j -> {
            }, runnable -> {
            }), "ACTIVE.compareAndSet must reject a second importall");
            assertTrue(MilestoneImportAll.activeForTest() == first, "the first job must stay active");
            assertEquals(0, secondSource.successCount.get());
            assertEquals(0, secondSource.failureCount.get());
        } finally {
            release.countDown();
        }
        awaitParseDone(first);
        MilestoneImportAll.applyPendingOnStop(null);
        awaitReleased();
        assertNull(MilestoneImportAll.activeForTest());
    }

    @Test
    void applyPendingOnStopAppliesACompletedParseExactlyOnce() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", source);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        assertTrue(MilestoneImportAll.submitForTest(job, j -> {
            throw new IOException("parse failed during stop");
        }, scheduled::set));
        awaitParseDone(job);

        MilestoneImportAll.applyPendingOnStop(null);
        assertEquals(1, source.failureCount.get(), "the parsed failure must be delivered on stop");
        assertNull(MilestoneImportAll.activeForTest());
        assertFalse(MilestoneIo.isImportInFlight());

        MilestoneImportAll.applyPendingOnStop(null);
        if (scheduled.get() != null) {
            scheduled.get().run();
        }
        assertEquals(1, source.failureCount.get(), "both deliveries must be no-ops after the first apply");
    }

    @Test
    void applyPendingOnStopLeavesAnUnfinishedParseAlone() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", source);
        assertTrue(MilestoneImportAll.submitForTest(job, j -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new IOException("late parse failure");
        }, runnable -> {
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            MilestoneImportAll.applyPendingOnStop(null);
            assertTrue(MilestoneImportAll.activeForTest() == job,
                    "an unfinished parse must stay active for the normal drain");
            assertEquals(0, source.failureCount.get());
            assertTrue(MilestoneIo.isImportInFlight());
        } finally {
            release.countDown();
        }
        awaitParseDone(job);
        MilestoneImportAll.applyPendingOnStop(null);
        assertEquals(1, source.failureCount.get(), "the finished parse is then applied once");
        assertNull(MilestoneImportAll.activeForTest());
    }

    @Test
    void cancelPendingAbandonsAnUnfinishedJobWithoutApplyingIt() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", source);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        assertTrue(MilestoneImportAll.submitForTest(job, j -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new IOException("abandoned parse");
        }, scheduled::set));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        MilestoneImportAll.cancelPending("test stop timeout");
        assertNull(MilestoneImportAll.activeForTest());
        assertFalse(MilestoneIo.isImportInFlight(), "cancelPending must release the import slot");
        assertTrue(job.applied.get());
        release.countDown();
        awaitParseDone(job);
        if (scheduled.get() != null) {
            scheduled.get().run();
        }
        MilestoneImportAll.applyPendingOnStop(null);
        MilestoneImportAll.cancelPending("again");
        assertEquals(0, source.successCount.get(), "an abandoned job must never be applied");
        assertEquals(0, source.failureCount.get());
    }

    @Test
    void resetStateClearsActiveAndInFlightStateAndIsIdempotent() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeSource source = new FakeSource();
        MilestoneImportAll.Job job = MilestoneImportAll.newJobForTest(
                Path.of("irrelevant.json"), "irrelevant.json", source);
        assertTrue(MilestoneImportAll.submitForTest(job, j -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
        }, runnable -> {
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        MilestoneImportAll.resetState();
        assertNull(MilestoneImportAll.activeForTest());
        assertFalse(MilestoneIo.isImportInFlight());
        assertTrue(job.applied.get());
        MilestoneImportAll.resetState();
        release.countDown();
        awaitParseDone(job);
        assertEquals(0, source.successCount.get());
        assertEquals(0, source.failureCount.get());
    }

    private static void awaitParseDone(MilestoneImportAll.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!job.parseDone.get() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(job.parseDone.get(), "parse did not complete within 10 seconds");
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
