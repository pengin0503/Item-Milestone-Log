package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void rejectedServerHandOffReleasesInFlightState() throws Exception {
        MilestoneIo.start(message -> {
        });
        assertTrue(MilestoneIo.tryBeginImport());
        MilestoneImportAll.Job job =
                MilestoneImportAll.newJobForTest(Path.of("irrelevant.json"), "irrelevant.json");

        assertTrue(MilestoneImportAll.submitForTest(
                job,
                j -> {
                },
                runnable -> {
                    throw new RejectedExecutionException("server stopped");
                }));

        awaitReleased();
        assertNull(MilestoneImportAll.activeForTest());
        assertFalse(MilestoneIo.isImportInFlight(),
                "a rejected server hand-off must not leave importall busy");
    }

    private static void awaitReleased() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (MilestoneIo.isImportInFlight() && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
    }
}
