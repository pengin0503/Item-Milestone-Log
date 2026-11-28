package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-6 source-shape guard: every tree keeps the nine C2S ordinals and adds exactly one optional
 * S2C effect registration. Source scanning is deliberate — the five trees bind different network
 * APIs, and no runtime network call is possible in a unit test.
 */
class NetworkRegistrationShapeTest {
    private static final List<String> TREES = List.of(
            "1.21.1", "1.20.1-forge", "26.1.2", "26.2", "26.3");

    private static Path repoRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("versions/1.21.1/src/main/java"))) {
                return p;
            }
        }
        throw new IllegalStateException("cannot locate repository root from " + start);
    }

    private static String source(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int from = 0; (from = text.indexOf(needle, from)) >= 0; from += needle.length()) {
            count++;
        }
        return count;
    }

    @Test
    void everyTreeRegistersExactlyFiveS2CPayloadsAndKeepsTheNineC2SActions() throws IOException {
        for (String tree : TREES) {
            String base = "versions/" + tree + "/src/main/java/com/pengin0503/itemmilestonelog/";
            String networking = source(base + "network/ModNetworking.java");
            String action = source(base + "network/MilestoneActionPayload.java");
            String tracker = source(base + "Tracker.java");
            if (tree.equals("1.20.1-forge")) {
                assertEquals(5, occurrences(networking, "messageBuilder("), tree);
                assertTrue(networking.contains("NetworkDirection.PLAY_TO_CLIENT"), tree);
                assertTrue(networking.contains("acceptMissingOr"), tree);
                assertTrue(tracker.contains("ModNetworking.isRemotePresent(player)"), tree);
                assertTrue(tracker.contains("MilestoneEffectPayload.Kind.PRESET_COMPLETE"), tree);
            } else {
                // 2 C2S + 3 S2C = the five registered payloads; no sixth channel.
                assertEquals(3, occurrences(networking, "registrar.playToClient("), tree);
                assertEquals(2, occurrences(networking, "registrar.playToServer("), tree);
                assertTrue(networking.contains(".optional()"), tree);
                assertTrue(tracker.contains("player.connection.hasChannel(MilestoneEffectPayload.TYPE)"), tree);
                assertTrue(tracker.contains("PacketDistributor.sendToPlayer(player"), tree);
            }
            assertTrue(networking.contains(tree.equals("1.20.1-forge")
                            ? "MilestoneEffectPayload.class" : "MilestoneEffectPayload.TYPE"), tree);
            assertTrue(networking.contains("ClientEffectHandler"), tree);
            // The C2S enum still has exactly the original nine ordinals (0..8); no ordinal 9.
            int open = action.indexOf("enum Action {");
            assertTrue(open >= 0, tree);
            int close = action.indexOf('}', open);
            String body = action.substring(open, close).substring("enum Action {".length()).trim();
            assertEquals(9, body.split("\\s*,\\s*").length, tree + " C2S ordinals must stay 0..8");
            assertFalse(body.contains("ORDINALS_AFTER_EIGHT"), tree);
        }
    }

    @Test
    void allFiveClientEntryPointsWireChatSoundAndEffects() throws IOException {
        for (String tree : TREES) {
            String path = "versions/" + tree + "/src/main/java/com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.java";
            String client = source(path);
            assertTrue(client.contains("setClientEffectHandler(ClientEffects::onEffect)"), path);
            assertTrue(client.contains("addListener(ItemMilestoneLogClient::onClientChat)"), path);
            assertTrue(client.contains("addListener(ItemMilestoneLogClient::onClientSound)"), path);
            assertTrue(client.contains("ClientSoundGate.toggleMuted()"), path);
            assertTrue(client.contains("warnKeyConflicts()"), path);
        }
        for (String variant : List.of("v121_9", "v121_11")) {
            String path = "versions/1.21.1/compat-variants/" + variant
                    + "/overrides/com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.java";
            String client = source(path);
            assertTrue(client.contains("setClientEffectHandler(ClientEffects::onEffect)"), path);
            assertTrue(client.contains("addListener(ItemMilestoneLogClient::onClientChat)"), path);
            assertTrue(client.contains("addListener(ItemMilestoneLogClient::onClientSound)"), path);
        }
    }

    @Test
    void soundVolumeLogicRemainsUntouched() throws IOException {
        String shared = source("common/src/main/java/com/pengin0503/itemmilestonelog/SoundVolumeLogic.java");
        assertTrue(shared.contains("FOLLOW_SERVER"), "the single volume path must remain in place");
        for (String tree : TREES) {
            String networking = source("versions/" + tree
                    + "/src/main/java/com/pengin0503/itemmilestonelog/network/ModNetworking.java");
            assertFalse(networking.contains("SoundVolumeLogic"),
                    "no second volume path may be introduced in " + tree);
        }
    }
}
