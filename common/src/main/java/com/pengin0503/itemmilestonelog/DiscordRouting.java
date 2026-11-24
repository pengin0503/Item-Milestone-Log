package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure-Java routing logic for Discord webhook destinations (F46 + F49).
 *
 * <p>Decides which destination(s) ({@link Destination#MAIN} = {@code discordWebhookUrl},
 * {@link Destination#WORLD_FIRST} = {@code discordWorldFirstWebhookUrl}) receive each kind of
 * Discord post, and partitions F27 grouped batches by destination before grouping thresholds apply.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class DiscordRouting {
    /** Discord webhook destination (F46). */
    public enum Destination {
        /** Primary webhook configured by {@code discordWebhookUrl}. */
        MAIN,
        /** Dedicated world-first webhook configured by {@code discordWorldFirstWebhookUrl}. */
        WORLD_FIRST
    }

    /** Kind of Discord post being routed. */
    public enum PostKind {
        /** Single non-world-first milestone record. */
        RECORD,
        /** Single world-first milestone record. */
        WORLD_FIRST_RECORD,
        /** Grouped batch (F27) containing only non-world-first records. */
        GROUPED_NORMAL_ONLY,
        /** Grouped batch (F27) containing only world-first records. */
        GROUPED_WORLD_FIRST_ONLY,
        /** Grouped batch (F27) containing both non-world-first and world-first records. */
        GROUPED_MIXED,
        /** Preset completion notification (F19). */
        PRESET_COMPLETION,
        /** Periodic digest notification (F9). */
        DIGEST,
        /** Manual test for the main webhook ({@code /milestonelog discordtest}, F49). */
        TEST_MAIN,
        /** Manual test for the world-first webhook ({@code /milestonelog discordtest worldfirst}, F49). */
        TEST_WORLD_FIRST
    }

    /** Result of splitting a batch of records by destination before applying F27 grouping thresholds. */
    public record RecordBatchSplit<T>(List<T> mainRecords, List<T> worldFirstRecords) {
    }

    private DiscordRouting() {
    }

    /**
     * Pure routing function deciding which destination(s) receive a Discord post of {@code kind}.
     *
     * <ul>
     *   <li>When {@code worldFirstUrlSet} is true, world-first posts go ONLY to {@link Destination#WORLD_FIRST}
     *       (never duplicated to {@link Destination#MAIN}).</li>
     *   <li>{@link Destination#MAIN} receives non-world-first record posts only when {@code mainUrlSet}
     *       is true and {@code discordNotifyScope} is not {@code "world_first"}.</li>
     *   <li>When {@code worldFirstUrlSet} is false, world-first record posts fall back to {@link Destination#MAIN}
     *       when {@code mainUrlSet} is true (regardless of {@code discordNotifyScope}).</li>
     *   <li>{@link PostKind#PRESET_COMPLETION} (F19) and {@link PostKind#DIGEST} (F9) always target
     *       {@link Destination#MAIN} only, independent of {@code discordNotifyScope} and {@code worldFirstUrlSet}.</li>
     *   <li>{@link PostKind#TEST_MAIN} targets {@link Destination#MAIN}; {@link PostKind#TEST_WORLD_FIRST}
     *       targets {@link Destination#WORLD_FIRST}.</li>
     * </ul>
     */
    public static Set<Destination> route(
            PostKind kind,
            boolean mainUrlSet,
            boolean worldFirstUrlSet,
            String discordNotifyScope) {
        Objects.requireNonNull(kind, "kind");
        boolean worldFirstOnlyScope = "world_first".equals(discordNotifyScope);
        EnumSet<Destination> targets = EnumSet.noneOf(Destination.class);
        switch (kind) {
            case RECORD, GROUPED_NORMAL_ONLY -> {
                if (mainUrlSet && !worldFirstOnlyScope) {
                    targets.add(Destination.MAIN);
                }
            }
            case WORLD_FIRST_RECORD, GROUPED_WORLD_FIRST_ONLY -> {
                if (worldFirstUrlSet) {
                    targets.add(Destination.WORLD_FIRST);
                } else if (mainUrlSet) {
                    targets.add(Destination.MAIN);
                }
            }
            case GROUPED_MIXED -> {
                if (worldFirstUrlSet) {
                    targets.add(Destination.WORLD_FIRST);
                    if (mainUrlSet && !worldFirstOnlyScope) {
                        targets.add(Destination.MAIN);
                    }
                } else if (mainUrlSet) {
                    targets.add(Destination.MAIN);
                }
            }
            case PRESET_COMPLETION, DIGEST, TEST_MAIN -> {
                if (mainUrlSet) {
                    targets.add(Destination.MAIN);
                }
            }
            case TEST_WORLD_FIRST -> {
                if (worldFirstUrlSet) {
                    targets.add(Destination.WORLD_FIRST);
                }
            }
        }
        return targets;
    }

    /**
     * Routes a single milestone record to at most one destination ({@link Destination#MAIN},
     * {@link Destination#WORLD_FIRST}, or {@code null} if not sent).
     */
    public static Destination routeRecord(
            boolean worldFirst,
            boolean mainUrlSet,
            boolean worldFirstUrlSet,
            String discordNotifyScope) {
        Set<Destination> targets = route(
                worldFirst ? PostKind.WORLD_FIRST_RECORD : PostKind.RECORD,
                mainUrlSet,
                worldFirstUrlSet,
                discordNotifyScope);
        if (targets.contains(Destination.WORLD_FIRST)) {
            return Destination.WORLD_FIRST;
        }
        if (targets.contains(Destination.MAIN)) {
            return Destination.MAIN;
        }
        return null;
    }

    /**
     * Classifies a batch's world-first composition into the matching grouped {@link PostKind},
     * or returns {@code null} if the batch is null or empty.
     */
    public static <T> PostKind classifyGroupedBatch(List<T> records, Predicate<T> isWorldFirst) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        boolean anyNormal = false;
        boolean anyWorldFirst = false;
        for (T record : records) {
            if (isWorldFirst != null && isWorldFirst.test(record)) {
                anyWorldFirst = true;
            } else {
                anyNormal = true;
            }
            if (anyNormal && anyWorldFirst) {
                return PostKind.GROUPED_MIXED;
            }
        }
        return anyWorldFirst ? PostKind.GROUPED_WORLD_FIRST_ONLY : PostKind.GROUPED_NORMAL_ONLY;
    }

    /**
     * Partitions a batch of simultaneous milestone records into per-destination lists (preserving
     * input order) so F27 grouping thresholds ({@value NotificationGrouping#GROUP_THRESHOLD})
     * are evaluated independently per destination (F46).
     */
    public static <T> RecordBatchSplit<T> splitRecordsByDestination(
            List<T> records,
            Predicate<T> isWorldFirst,
            boolean mainUrlSet,
            boolean worldFirstUrlSet,
            String discordNotifyScope) {
        if (records == null || records.isEmpty()) {
            return new RecordBatchSplit<>(List.of(), List.of());
        }
        List<T> main = new ArrayList<>();
        List<T> worldFirst = new ArrayList<>();
        for (T record : records) {
            boolean wf = isWorldFirst != null && isWorldFirst.test(record);
            Destination dest = routeRecord(wf, mainUrlSet, worldFirstUrlSet, discordNotifyScope);
            if (dest == Destination.MAIN) {
                main.add(record);
            } else if (dest == Destination.WORLD_FIRST) {
                worldFirst.add(record);
            }
        }
        return new RecordBatchSplit<>(List.copyOf(main), List.copyOf(worldFirst));
    }
}
