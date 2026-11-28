package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.TooltipOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-68/79/73: old tooltip signatures and shipped defaults stay byte-compatible. */
class TooltipWiringTest {
    @Test
    void tooltipLinesKeepsItsSingleStringSignature() throws Exception {
        Method method = ClientMilestoneHandler.class.getMethod("tooltipLines", String.class);
        assertNotNull(method);
        assertEquals(java.util.List.class, method.getReturnType());
        assertEquals(1L, Arrays.stream(ClientMilestoneHandler.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("tooltipLines")).count(),
                "no overload may change the signature existing tests call");
    }

    @Test
    void unloadedConfigKeepsTheShippedDefaults() {
        // In plain JUnit neither the NeoForge nor the Forge config is registered.
        assertEquals(TooltipOptions.DEFAULT, Config.tooltipOptions());
        assertEquals(3, Config.tooltipMaxLines());
        assertTrue(Config.tooltipShowTarget());
        assertTrue(Config.tooltipShowRecorded());
        assertEquals("default", Config.tooltipSort());
        assertEquals("default", Config.tooltipStatusColor());
        assertTrue(Config.tooltipTagNames());
        assertFalse(Config.externalTooltipIntegration());
        assertTrue(Config.searchCompletion());
        assertTrue(Config.searchHistoryEnabled());
    }

    @Test
    void configDeclaresEveryNewKeyExactlyOnce() throws Exception {
        String src = System.getProperty("itemmilestonelog.mainSourceDir");
        assertNotNull(src);
        java.nio.file.Path root = java.nio.file.Path.of(src).toAbsolutePath().normalize();
        java.nio.file.Path rel = java.nio.file.Path.of("com/pengin0503/itemmilestonelog/Config.java");
        String text = null;
        for (int i = 0; i < 6 && root != null; i++) {
            java.nio.file.Path candidate = root.resolve(rel);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                text = java.nio.file.Files.readString(candidate, java.nio.charset.StandardCharsets.UTF_8);
                break;
            }
            root = root.getParent();
        }
        assertNotNull(text, "Config.java must be discoverable from itemmilestonelog.mainSourceDir");
        for (String key : new String[] {
                "tooltipMaxLines", "tooltipShowTarget", "tooltipShowRecorded", "tooltipSort",
                "tooltipStatusColor", "tooltipTagNames", "externalTooltipIntegration",
                "searchCompletion", "searchHistoryEnabled"}) {
            assertEquals(1, text.split("\"" + key + "\"", -1).length - 1,
                    key + " must be declared exactly once in Config.SPEC");
        }
    }
}
