package com.pengin0503.itemmilestonelog;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetsTest {
    @Test
    void allPresetsContainValidIdentifiers() {
        assertFalse(Presets.PRESETS.isEmpty());
        for (Map.Entry<String, List<String>> entry : Presets.PRESETS.entrySet()) {
            assertFalse(entry.getValue().isEmpty(), "Preset should not be empty: " + entry.getKey());
            for (String itemId : entry.getValue()) {
                assertTrue(itemId.contains(":"), "Preset item ID missing namespace: " + itemId);
                assertNotNull(Identifier.tryParse(itemId), "Invalid Identifier: " + itemId);
            }
        }
    }
}
