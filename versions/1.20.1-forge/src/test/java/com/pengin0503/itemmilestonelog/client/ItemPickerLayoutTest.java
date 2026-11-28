package com.pengin0503.itemmilestonelog.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-150-75 / F-150-84 (FL-5): completion and history may only use the existing search EditBox.
 * The frozen layout constants are asserted from source so any coordinate, size or extra widget
 * change fails the build even though the GUI is not rendered in unit tests.
 */
class ItemPickerLayoutTest {
    private static String source() throws Exception {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        assertNotNull(mainSourceDir, "itemmilestonelog.mainSourceDir must be set by the test runner");
        Path rel = Path.of("com/pengin0503/itemmilestonelog/client/ItemPickerScreen.java");
        Path root = Path.of(mainSourceDir).toAbsolutePath().normalize();
        for (int i = 0; i < 6 && root != null; i++) {
            Path candidate = root.resolve(rel);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            root = root.getParent();
        }
        throw new AssertionError("Could not locate ItemPickerScreen.java below " + mainSourceDir);
    }

    @Test
    void frozenSearchFieldGeometryIsUnchanged() throws Exception {
        String src = source();
        assertTrue(src.contains("int searchWidth = Math.min(340, this.width - 40);"),
                "the search width formula must not change");
        assertTrue(src.contains("this.font, searchX, 24, searchWidth, 20,"),
                "the search field must stay at y=24 with height 20");
        assertTrue(src.contains("private static final int MAX_DISPLAYED_ROWS = 300;"),
                "the displayed-row cap must not change");
        assertTrue(src.contains("Component.translatable(\"gui.itemmilestonelog.picker.search_hint\")"),
                "the default hint key must still exist");
        long editBoxes = src.lines().filter(line -> line.contains("addRenderableWidget(new EditBox(")).count();
        assertEquals(2, editBoxes, "search and quantity EditBoxes only; no dropdown widget");
    }

    @Test
    void keyPressedOnlyRecallsHistoryAndAcceptsCompletion() throws Exception {
        String src = source();
        assertTrue(src.contains("public boolean keyPressed("), "the picker handles Tab/Up/Down itself");
        assertTrue(src.contains("cycleHistory("), "Up/Down walk the local history");
        assertTrue(src.contains("suggestions.get(0).displayName()"), "Tab accepts the first candidate");
        assertTrue(src.contains("ItemNameCompletion.complete("), "completion is routed through the pure helper");
        assertTrue(src.contains("SearchHistoryStore."), "history is routed through the local store");
        Method[] methods = ItemPickerScreen.class.getDeclaredMethods();
        List<String> named = java.util.Arrays.stream(methods).map(Method::getName).toList();
        assertTrue(named.contains("keyPressed"));
    }
}
