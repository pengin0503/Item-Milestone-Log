package com.pengin0503.itemmilestonelog.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-150-85: the logout hook has at most three side effects, and no client-local PR-5 feature
 * introduces a new Screen or a second listener registration.
 */
class ClientLocalOutputWiringTest {
    private static String source(String relative) throws IOException {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        assertNotNull(mainSourceDir, "itemmilestonelog.mainSourceDir must be set by the test runner");
        Path candidate = Path.of(mainSourceDir).toAbsolutePath().normalize().resolve(relative);
        assertTrue(Files.exists(candidate), "missing source: " + candidate);
        return Files.readString(candidate);
    }

    @Test
    void logoutHasOnlyCacheFlushBackupAndOptionalExport() throws IOException {
        String src = source("com/pengin0503/itemmilestonelog/client/ClientLocalOutput.java");
        int begin = src.indexOf("public static void onLogout()");
        assertTrue(begin >= 0, "logout hook must exist");
        int end = src.indexOf("/** Builds the display-only cache snapshot", begin);
        assertTrue(end > begin, "logout hook must be bounded");
        String body = src.substring(begin, end);
        assertEquals(1, count(body, "LocalSyncCache.write("));
        assertEquals(1, count(body, "rotateBackup(configDir)"));
        assertEquals(1, count(body, "ClientExportLogic.export("));
        assertFalse(body.contains("ScreenshotAccess"), "no screenshot on logout");
        assertFalse(body.contains("StreamStatusWriter.write"), "no OBS write on logout");
        assertFalse(body.contains("register"), "logout must not register listeners");
    }

    @Test
    void noNewScreenOrAbstractEventListenerWasAdded() throws IOException {
        String entry = source("com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.java");
        assertTrue(entry.contains("ClientLocalOutput.onLogout();"));
        assertTrue(entry.contains("ClientPlayerNetworkEvent.LoggingOut")
                        || entry.contains("RegisterClientCommandsEvent"),
                "only concrete client events may be registered");
        String compat = source("com/pengin0503/itemmilestonelog/compat/client/ScreenshotAccess.java");
        assertTrue(compat.contains("Class.forName"), "screenshot implementations load lazily");
        assertFalse(compat.contains("new ScreenshotCompatImpl"), "no constructor-time compat loading");
    }

    @Test
    void loginKeyIsDeterministicAndBackupRotationIsNotSnapshotGated() throws IOException {
        String hud = source("com/pengin0503/itemmilestonelog/client/MilestoneHudOverlay.java");
        assertTrue(hud.contains("return serverKey;"),
                "SF-2: serverKey() must be readable at login, before any snapshot exists");
        assertTrue(hud.contains("worldIdentity(mc)"),
                "SF-1: identity must use the save/world, not the dimension");
        assertTrue(hud.contains("ClientProfileStore.save("),
                "SF-4: profiles must be saved on disconnect");
        String local = source("com/pengin0503/itemmilestonelog/client/ClientLocalOutput.java");
        assertFalse(local.contains("Files.exists(LocalSyncCache.fileFor(configDir))"),
                "SF-2: connect-time rotation must not depend on a snapshot existing");
        assertTrue(local.contains("MilestoneHudOverlay.presetCaptureDue()"),
                "MN-5: preset captures share the record capture clock");
        String effects = source("com/pengin0503/itemmilestonelog/client/ClientEffects.java");
        assertTrue(effects.contains("RATE_LIMIT.allow(System.currentTimeMillis())"),
                "MN-5: preset effects must use the same limiter");
    }

    @Test
    void screenshotImplementationIsReflectiveOnly() throws IOException {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        assertNotNull(mainSourceDir);
        Path root = Path.of(mainSourceDir).toAbsolutePath().normalize();
        List<Path> impls = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().equals("ScreenshotCompatImpl.java"))
                    .forEach(impls::add);
        }
        assertFalse(impls.isEmpty(), "a screenshot compat implementation must exist in this tree");
        for (Path impl : impls) {
            String src = Files.readString(impl);
            assertTrue(src.contains("Class.forName(\"net.minecraft.client.Screenshot\")"),
                    impl + " must resolve the screenshot class reflectively");
            assertFalse(src.contains("Screenshot.grab("),
                    impl + " must not link a version-varying method directly");
        }
    }

    private static int count(String text, String needle) {
        int total = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            total++;
            from += needle.length();
        }
        return total;
    }
}
