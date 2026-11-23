package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure-Java logic for the F17 scoreboard integration, shared across all Minecraft versions via
 * {@code common/src/main/java}: objective-name validation, config resolution, and the
 * "player data -> scores to write" computation.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class ScoreboardLogic {
    /** Objective names this mod accepts: characters usable unquoted in commands, 1..32 long. */
    public static final Pattern OBJECTIVE_NAME = Pattern.compile("^[A-Za-z0-9_.+-]{1,32}$");

    private ScoreboardLogic() {
    }

    /** Whether {@code name} is a valid objective name (non-empty, matches {@link #OBJECTIVE_NAME}). */
    public static boolean isValidObjectiveName(String name) {
        return name != null && OBJECTIVE_NAME.matcher(name).matches();
    }

    /** Config validator for {@code scoreboardObjective} / {@code scoreboardWorldFirstObjective}: empty or a valid name. */
    public static boolean isValidConfigValue(Object value) {
        return value instanceof String s && (s.isEmpty() || isValidObjectiveName(s));
    }

    /**
     * Effective objective names after resolving the two config values.
     *
     * @param records             objective for record counts, or {@code null} when disabled
     * @param worldFirsts         objective for world-first counts, or {@code null} when disabled
     * @param duplicateIgnored    whether the world-first objective was disabled because it equals {@code records}
     */
    public record Objectives(String records, String worldFirsts, boolean duplicateIgnored) {
        public boolean anyEnabled() {
            return records != null || worldFirsts != null;
        }
    }

    /**
     * Resolves the raw config values: empty or invalid values disable that objective; when both
     * name the same objective, the world-first one is disabled ({@code duplicateIgnored = true},
     * the caller logs a warning).
     */
    public static Objectives resolve(String recordsRaw, String worldFirstsRaw) {
        String records = isValidObjectiveName(recordsRaw) ? recordsRaw : null;
        String worldFirsts = isValidObjectiveName(worldFirstsRaw) ? worldFirstsRaw : null;
        if (records != null && records.equals(worldFirsts)) {
            return new Objectives(records, null, true);
        }
        return new Objectives(records, worldFirsts, false);
    }

    /** The score holder for a player's stored name, or {@code null} when it is empty (the player is not written). */
    public static String holderName(String lastKnownName) {
        return lastKnownName == null || lastKnownName.isEmpty() ? null : lastKnownName;
    }

    /** Counts world firsts per holder UUID (or any key) from the holders of every world-first record. */
    public static <K> Map<K, Integer> countWorldFirsts(Iterable<K> holders) {
        Map<K, Integer> counts = new HashMap<>();
        for (K holder : holders) {
            if (holder != null) {
                counts.merge(holder, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * One player's inputs for the score computation.
     *
     * @param id            player key (UUID)
     * @param lastKnownName stored player name ("" = unknown)
     * @param recordCount   number of milestone records
     * @param lastSeen      epoch millis of the last login/logout (0 = unknown); breaks name ties
     */
    public record PlayerInput<K>(K id, String lastKnownName, int recordCount, long lastSeen) {
    }

    /** Scores to write for one score holder. */
    public record PlayerScore<K>(K id, String holder, int records, int worldFirsts) {
    }

    /**
     * Computes the scores to write: one entry per distinct holder name, skipping players without a
     * name. When several players share a stored name (e.g. after a name change), the one seen most
     * recently wins (ties keep the first in iteration order). Result is in input order.
     */
    public static <K> List<PlayerScore<K>> computeScores(Collection<PlayerInput<K>> players, Map<K, Integer> worldFirstCounts) {
        Map<String, PlayerInput<K>> winners = new LinkedHashMap<>();
        for (PlayerInput<K> p : players) {
            String holder = holderName(p.lastKnownName());
            if (holder == null) {
                continue;
            }
            PlayerInput<K> current = winners.get(holder);
            if (current == null || p.lastSeen() > current.lastSeen()) {
                winners.put(holder, p);
            }
        }
        List<PlayerScore<K>> out = new ArrayList<>(winners.size());
        for (Map.Entry<String, PlayerInput<K>> e : winners.entrySet()) {
            PlayerInput<K> p = e.getValue();
            out.add(new PlayerScore<>(p.id(), e.getKey(), Math.max(0, p.recordCount()),
                    worldFirstCounts.getOrDefault(p.id(), 0)));
        }
        return out;
    }

    /**
     * On login: the old name whose scores should be removed from both objectives, or {@code null}.
     * Nothing is removed when the name did not change, the old name is empty, or another player
     * still uses the old name ({@code otherPlayersNames}: stored names of every other player).
     */
    public static String staleNameOnLogin(String previousName, String currentName, Collection<String> otherPlayersNames) {
        String old = holderName(previousName);
        if (old == null || Objects.equals(old, currentName) || otherPlayersNames.contains(old)) {
            return null;
        }
        return old;
    }
}
