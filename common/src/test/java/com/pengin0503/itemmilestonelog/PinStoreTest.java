package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-62: client-local pins, 64-item cap, normalisation and lenient storage. */
class PinStoreTest {
    @TempDir
    Path configDir;

    @Test
    void normalisationMatchesTargetKeyRules() {
        assertEquals(new PinStore.Pin("minecraft:diamond", 10), PinStore.normalize("  diamond#10  "));
        assertEquals(new PinStore.Pin("minecraft:diamond", 1), PinStore.normalize(" diamond "));
        assertEquals(new PinStore.Pin("#c:ingots/iron", 5), PinStore.normalize("#c:ingots/iron#5"));
        assertNull(PinStore.normalize("minecraft:air"));
        assertNull(PinStore.normalize("  "));
        assertNull(PinStore.normalize("bad#0"));
        assertNull(PinStore.normalize("minecraft:diamond#1000001"));
        assertNull(PinStore.normalize("minecraft:diamond#1#2"));
        assertNull(PinStore.normalize("a:b:c"));
        assertNull(PinStore.normalize(null));
        assertEquals("minecraft:diamond#10",
                PinStore.normalize(" diamond#10 ").encoded());
    }

    @Test
    void addRejectsMissingSyncAndUnknownTargets() {
        assertEquals(PinStore.AddOutcome.NO_SYNC,
                PinStore.add(configDir, "minecraft:diamond", null).outcome());
        assertEquals(PinStore.AddOutcome.NOT_IN_SYNC,
                PinStore.add(configDir, "minecraft:diamond", Set.of("minecraft:stone")).outcome());
        assertEquals(PinStore.AddOutcome.INVALID_ID,
                PinStore.add(configDir, "minecraft:air", Set.of("minecraft:air")).outcome());
        assertTrue(PinStore.read(configDir).isEmpty());
    }

    @Test
    void addRemoveAndClearKeepInsertionOrder() {
        Set<String> available = Set.of("minecraft:diamond#10", "minecraft:stone");
        assertEquals(PinStore.AddOutcome.ADDED,
                PinStore.add(configDir, "minecraft:diamond#10", available).outcome());
        assertEquals(PinStore.AddOutcome.ADDED,
                PinStore.add(configDir, "stone", available).outcome());
        assertEquals(PinStore.AddOutcome.DUPLICATE,
                PinStore.add(configDir, " diamond#10 ", available).outcome());
        assertEquals(List.of(new PinStore.Pin("minecraft:diamond", 10),
                        new PinStore.Pin("minecraft:stone", 1)),
                PinStore.read(configDir));

        assertTrue(PinStore.remove(configDir, "diamond#10"));
        assertFalse(PinStore.remove(configDir, "diamond#10"));
        assertFalse(PinStore.remove(configDir, "minecraft:air"));
        assertEquals(List.of(new PinStore.Pin("minecraft:stone", 1)), PinStore.read(configDir));

        PinStore.clear(configDir);
        assertTrue(PinStore.read(configDir).isEmpty());
        assertTrue(Files.exists(PinStore.fileFor(configDir)));
    }

    @Test
    void capEvictsTheOldestPinAndNeverExceedsSixtyFour() {
        Set<String> available = IntStream.range(0, 70)
                .mapToObj(i -> "minecraft:item_" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (int i = 0; i < PinStore.MAX_PINS + 1; i++) {
            assertEquals(PinStore.AddOutcome.ADDED,
                    PinStore.add(configDir, "minecraft:item_" + i, available).outcome());
        }
        List<PinStore.Pin> pins = PinStore.read(configDir);
        assertEquals(64, pins.size());
        assertEquals("minecraft:item_1", pins.get(0).itemId());
        assertEquals("minecraft:item_64", pins.get(63).itemId());
        assertFalse(pins.contains(new PinStore.Pin("minecraft:item_0", 1)));
    }

    @Test
    void loadingAnOversizedFileKeepsTheNewestPins() throws Exception {
        StringBuilder json = new StringBuilder("{\"v\":\"1\",\"pins\":[");
        for (int i = 0; i < PinStore.MAX_PINS + 6; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append("minecraft:item_").append(i).append('"');
        }
        json.append("]}");
        Path file = PinStore.fileFor(configDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);

        List<PinStore.Pin> pins = PinStore.read(configDir);
        assertEquals(PinStore.MAX_PINS, pins.size());
        assertEquals("minecraft:item_6", pins.get(0).itemId());
        assertEquals("minecraft:item_" + (PinStore.MAX_PINS + 5), pins.get(pins.size() - 1).itemId());
    }

    @Test
    void corruptJsonAndBadEntriesAreLenient() throws Exception {
        assertTrue(PinStore.read(configDir).isEmpty());
        Files.createDirectories(PinStore.fileFor(configDir).getParent());
        Files.writeString(PinStore.fileFor(configDir), "{not json", StandardCharsets.UTF_8);
        assertTrue(PinStore.read(configDir).isEmpty());
        Files.writeString(PinStore.fileFor(configDir),
                "{\"v\":\"1\",\"pins\":[\"minecraft:diamond\",\"minecraft:air\",\"bad#0\",5]}",
                StandardCharsets.UTF_8);
        assertEquals(List.of(new PinStore.Pin("minecraft:diamond", 1)), PinStore.read(configDir));
    }

    @Test
    void pinFileNeverContainsConnectionDetails() throws Exception {
        PinStore.add(configDir, "minecraft:diamond",
                Set.of("minecraft:diamond"));
        String body = Files.readString(PinStore.fileFor(configDir), StandardCharsets.UTF_8);
        assertEquals("pins.json", PinStore.fileFor(configDir).getFileName().toString());
        assertTrue(body.contains("minecraft:diamond"));
        assertFalse(body.contains("mc.example.com"));
    }
}
