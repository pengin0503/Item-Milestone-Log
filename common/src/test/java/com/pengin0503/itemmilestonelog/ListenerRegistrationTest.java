package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Static guard for the PR-3 client-construction crash: NeoForge's
 * {@code LivingDamageEvent} is an abstract parent of {@code Pre}/{@code Post}, and FML throws
 * "Cannot register listeners for abstract class ..." while constructing the client mod if a
 * listener is registered for that parent. Unit tests cannot see FML construction, so this test
 * reads the client sources of every tree and checks that every bus listener registered by the
 * client entry point has a concrete event parameter. The Forge 1.20.1 tree must keep its classic
 * concrete {@code LivingDamageEvent}.
 */
class ListenerRegistrationTest {
    private static final String CLIENT =
            "src/main/java/com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.java";
    private static final Pattern REGISTRATION = Pattern.compile(
            "addListener\\s*\\(\\s*(?:EventPriority\\.\\w+\\s*,\\s*)?ItemMilestoneLogClient::(\\w+)\\s*\\)");
    private static final Pattern PARAM = Pattern.compile(
            "void\\s+(\\w+)\\s*\\(\\s*([A-Za-z_][\\w.]*)\\s+\\w+\\s*\\)");

    /** Locates the repository root from the current working dir (CI project dir or repo root). */
    private static Path repoRoot() {
        String explicit = System.getProperty("itemmilestonelog.repoRoot");
        Path start = explicit == null || explicit.isBlank() ? Path.of("").toAbsolutePath() : Path.of(explicit);
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("versions/1.21.1/src/main/java"))) {
                return p;
            }
        }
        return fail("cannot locate repository root from " + start);
    }

    private static String read(Path root, String version) throws IOException {
        Path file = root.resolve("versions").resolve(version).resolve(CLIENT);
        assertTrue(Files.isRegularFile(file), "missing client source: " + file);
        return Files.readString(file);
    }

    /** Every handler method referenced by an addListener call, with its event parameter type. */
    private static List<String> registeredEventTypes(String source) {
        List<String> types = new ArrayList<>();
        Matcher reg = REGISTRATION.matcher(source);
        while (reg.find()) {
            String handler = reg.group(1);
            Matcher param = PARAM.matcher(source);
            boolean found = false;
            while (param.find()) {
                if (handler.equals(param.group(1))) {
                    types.add(param.group(2));
                    found = true;
                    break;
                }
            }
            assertTrue(found, "no declaration found for handler " + handler);
        }
        assertFalse(types.isEmpty(), "no client addListener registrations found in source");
        return types;
    }

    @Test
    void neoForgeTreesRegisterConcreteDamageEvent() throws IOException {
        Path root = repoRoot();
        for (String version : new String[]{"1.21.1", "26.1.2", "26.2", "26.3"}) {
            String source = read(root, version);
            List<String> types = registeredEventTypes(source);
            assertFalse(types.contains("net.neoforged.neoforge.event.entity.living.LivingDamageEvent")
                            || types.contains("LivingDamageEvent"),
                    version + ": a listener is registered for the abstract LivingDamageEvent");
            assertTrue(types.contains("LivingDamageEvent.Post"),
                    version + ": expected concrete LivingDamageEvent.Post registration, got " + types);
            assertFalse(source.contains("onLivingDamage(LivingDamageEvent event)"),
                    version + ": handler still takes the abstract LivingDamageEvent");
        }
    }

    @Test
    void forgeTreeKeepsItsConcreteDamageEvent() throws IOException {
        String source = read(repoRoot(), "1.20.1-forge");
        List<String> types = registeredEventTypes(source);
        assertTrue(types.contains("LivingDamageEvent"),
                "Forge 1.20.1 LivingDamageEvent is a concrete class and must stay registered: " + types);
        assertFalse(types.contains("LivingDamageEvent.Post"),
                "Forge 1.20.1 has no Pre/Post split; do not introduce a NeoForge subclass here");
    }
}
