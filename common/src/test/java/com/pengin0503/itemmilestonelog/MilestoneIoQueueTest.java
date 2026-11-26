package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class MilestoneIoQueueTest {
    private MilestoneIoQueue queue;

    @AfterEach
    void cleanup() {
        if (queue != null) {
            queue.shutdownNow();
            queue = null;
        }
        MilestoneIo.reset();
    }

    @Test
    void runsTasksSeriallyInSubmissionOrderOnDaemonThread() {
        queue = new MilestoneIoQueue(message -> {
        });
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        List<String> threadNames = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> daemonFlags = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 20; i++) {
            int value = i;
            assertTrue(queue.submit(MilestoneIoLogic.TaskKind.BACKUP, () -> {
                order.add(value);
                threadNames.add(Thread.currentThread().getName());
                daemonFlags.add(Thread.currentThread().isDaemon());
            }));
        }

        queue.beginDrain();
        MilestoneIoLogic.DrainResult result = queue.awaitDrain(10_000L, null);

        assertTrue(result.completed());
        assertEquals(20, order.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(i, order.get(i));
        }
        assertEquals(List.of(MilestoneIoQueue.THREAD_NAME), List.copyOf(threadNames).subList(0, 1));
        assertTrue(daemonFlags.stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void rejectsSecondImportWhileOneIsInFlight() {
        queue = new MilestoneIoQueue(message -> {
        });
        assertTrue(queue.tryBeginImport());
        assertTrue(queue.isImportInFlight());
        assertFalse(queue.tryBeginImport());
        queue.endImport();
        assertFalse(queue.isImportInFlight());
        assertTrue(queue.tryBeginImport());
        queue.endImport();
    }

    @Test
    void stopsAcceptingNormalTasksAfterBeginDrainButAllowsFinalBackup() {
        queue = new MilestoneIoQueue(message -> {
        });
        queue.beginDrain();
        assertFalse(queue.isAccepting());
        assertFalse(queue.submit(MilestoneIoLogic.TaskKind.IMPORT_ALL, () -> {
        }));
        assertFalse(queue.tryBeginImport(), "no import may begin after the drain transition");

        AtomicBoolean ran = new AtomicBoolean();
        assertTrue(queue.submitFinal(() -> ran.set(true)));
        assertTrue(queue.awaitDrain(5_000L, null).completed());
        assertTrue(ran.get());
    }

    @Test
    void shutdownNowReleasesDiscardedTaskCountersAndWakesTheDrain() throws Exception {
        queue = new MilestoneIoQueue(message -> {
        });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(queue.submit(MilestoneIoLogic.TaskKind.IMPORT_ALL, () -> {
            started.countDown();
            // Keep running through shutdownNow()'s interrupt so the discarded vs. running split
            // stays deterministic for the counter assertions below.
            while (true) {
                try {
                    if (release.await(5, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    // ignore the shutdownNow interrupt and keep the task running
                }
            }
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        for (int i = 0; i < 5; i++) {
            assertTrue(queue.submit(MilestoneIoLogic.TaskKind.BACKUP, () -> {
            }));
        }
        assertEquals(6, queue.pendingTaskCount());

        queue.shutdownNow();
        // Five never-started tasks were discarded; only the running task remains counted.
        assertEquals(1, queue.pendingTaskCount(), "shutdownNow must release discarded task counters");
        assertEquals(1, queue.pendingByKindSnapshot().get(MilestoneIoLogic.TaskKind.IMPORT_ALL));
        assertFalse(queue.pendingByKindSnapshot().containsKey(MilestoneIoLogic.TaskKind.BACKUP));

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.pendingTaskCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(0, queue.pendingTaskCount());
        assertTrue(queue.awaitDrain(1_000L, null).completed(),
                "a drain waiting on the queue must be woken after the counters reach zero");
    }

    @Test
    void emptyQueueDrainsImmediately() {
        queue = new MilestoneIoQueue(message -> {
        });
        queue.beginDrain();
        MilestoneIoLogic.DrainResult result = queue.awaitDrain(30_000L, null);
        assertTrue(result.completed());
        assertEquals(0, result.remainingTasks());
        assertTrue(result.elapsedMillis() < 2_000L);
        assertEquals(Map.of(), result.remainingByKind());
    }

    @Test
    void drainCompletesWhenTaskFinishesInTime() {
        queue = new MilestoneIoQueue(message -> {
        });
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        assertTrue(queue.submit(MilestoneIoLogic.TaskKind.IMPORT_ALL, () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
        }));
        queue.beginDrain();

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();

        MilestoneIoLogic.DrainResult result = queue.awaitDrain(10_000L, null);
        assertTrue(result.completed());
        assertTrue(finished.get());
    }

    @Test
    void drainTimesOutAndReportsRemainingKindThenCanBeForcedDown() {
        queue = new MilestoneIoQueue(message -> {
        });
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<MilestoneIoLogic.DrainResult> progress = new AtomicReference<>();
        assertTrue(queue.submit(MilestoneIoLogic.TaskKind.IMPORT_ALL, () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        queue.beginDrain();

        MilestoneIoLogic.DrainResult result = queue.awaitDrain(100L, progress::set);
        assertFalse(result.completed());
        assertEquals(1, result.remainingTasks());
        assertEquals(1, result.remainingByKind().get(MilestoneIoLogic.TaskKind.IMPORT_ALL));

        release.countDown();
        queue.shutdownNow();
        assertTrue(queue.isShutdown());
        assertFalse(queue.submit(MilestoneIoLogic.TaskKind.BACKUP, () -> {
        }));
    }

    @Test
    void freshExecutorIsCreatedAfterReset() {
        MilestoneIo.start(message -> {
        });
        MilestoneIoQueue first = MilestoneIo.current();
        assertNotNull(first);
        assertTrue(MilestoneIo.isAccepting());
        assertTrue(MilestoneIo.tryBeginImport());
        MilestoneIo.endImport();

        MilestoneIo.beginStopping();
        MilestoneIo.shutdown();
        MilestoneIo.reset();
        assertNull(MilestoneIo.current());

        MilestoneIo.start(message -> {
        });
        MilestoneIoQueue second = MilestoneIo.current();
        assertNotNull(second);
        assertTrue(first != second);
        assertTrue(MilestoneIo.isAccepting());
    }
}
