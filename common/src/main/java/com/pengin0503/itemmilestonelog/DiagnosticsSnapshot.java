package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * F-150-52 ({@code doctor}) / F-150-53 ({@code admin debug}): pure, Minecraft-free collection and
 * line selection for the two read-only diagnostics commands (step 5, PR-2).
 *
 * <p>The version trees own every source of truth (SavedData, backup files, Discord config, the I/O
 * queue and the runtime). They read those sources without mutating them and hand the resulting
 * primitive values to this class. This class then decides which lines are rendered, how many
 * warnings exist and cap enforcement ({@value #MAX_LINES}). It never performs I/O, never touches
 * SavedData and never formats a player name, UUID or webhook URL into a line: every value is a
 * count, an enum name or a state word. Translation happens on the version side via
 * {@code ServerLang.tr}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class DiagnosticsSnapshot {
    /** Hard cap on rendered lines for both diagnostics commands (F-150-52 / F-150-53). */
    public static final int MAX_LINES = 20;

    // Doctor line keys (en/ja in the bundled language files).
    public static final String SECTION_SERVER = "message.itemmilestonelog.doctor_section_server";
    public static final String SECTION_DATA = "message.itemmilestonelog.doctor_section_data";
    public static final String SECTION_BACKUP = "message.itemmilestonelog.doctor_section_backup";
    public static final String SECTION_DISCORD = "message.itemmilestonelog.doctor_section_discord";
    public static final String SECTION_IO = "message.itemmilestonelog.doctor_section_io";
    public static final String DOCTOR_OK = "message.itemmilestonelog.doctor_ok";
    public static final String DOCTOR_WARNINGS = "message.itemmilestonelog.doctor_warning_count";

    // Debug line keys.
    public static final String DEBUG_HEADER = "message.itemmilestonelog.admin_debug_header";
    public static final String DEBUG_LINE = "message.itemmilestonelog.admin_debug_line";
    public static final String DEBUG_UNAVAILABLE = "message.itemmilestonelog.admin_debug_unavailable";

    private DiagnosticsSnapshot() {
    }

    /** Counts derived from live {@code MilestoneData}; no player name or UUID is retained. */
    public record DataCounts(boolean empty, int players, int records, int targets,
                             int worldFirsts, int negativeTicks, int unknownEpoch) {
        public DataCounts {
            if (players < 0 || records < 0 || targets < 0 || worldFirsts < 0
                    || negativeTicks < 0 || unknownEpoch < 0) {
                throw new IllegalArgumentException("diagnostic counts must not be negative");
            }
        }

        /** Number of records with an abnormal playtime tick or wall-clock value. */
        public int abnormal() {
            return negativeTicks + unknownEpoch;
        }
    }

    /** Read-only view of the backup directory produced by one {@code Files.list} scan per call. */
    public record BackupState(boolean dirExists, int matchingFiles, String newestTimeText) {
        public BackupState {
            Objects.requireNonNull(newestTimeText, "newestTimeText");
        }
    }

    /** Discord state derived from existing config / SavedData values only; never a resolved URL. */
    public record DiscordState(String webhookStatus, boolean digestEnabled,
                               int digestIntervalDays, long lastDigestEpochMillis) {
        public DiscordState {
            Objects.requireNonNull(webhookStatus, "webhookStatus");
        }
    }

    /** Read-only view of the {@code itemmilestonelog-io} executor. */
    public record IoState(boolean present, boolean accepting, int pending, boolean importInFlight,
                          String pendingByKindText, String ioThreadNames) {
        public IoState {
            Objects.requireNonNull(pendingByKindText, "pendingByKindText");
            Objects.requireNonNull(ioThreadNames, "ioThreadNames");
        }
    }

    /**
     * Runtime values for {@code admin debug}. Each optional source carries an availability flag so
     * a version that lacks the API prints the fixed "unavailable" wording instead of crashing or
     * inventing a value (D-150-144).
     */
    public record RuntimeState(
            boolean tpsAvailable, double tps,
            String threadName,
            boolean notificationsAvailable, int pendingNotifications,
            boolean droppedAvailable, long droppedPosts,
            boolean discordQueueAvailable, int pendingDiscordSends,
            boolean deadWebhookAvailable, boolean deadWebhook,
            boolean discordThreadAvailable, boolean discordThreadAlive,
            long totalMemoryMb, long maxMemoryMb) {
        public RuntimeState {
            Objects.requireNonNull(threadName, "threadName");
        }
    }

    /** Complete read-only snapshot handed to the renderer. */
    public record Snapshot(String worldName, int serverPlayers, int serverTicks,
                           DataCounts data, BackupState backup, DiscordState discord,
                           IoState io, RuntimeState runtime) {
        public Snapshot {
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(backup, "backup");
            Objects.requireNonNull(discord, "discord");
            Objects.requireNonNull(io, "io");
            Objects.requireNonNull(runtime, "runtime");
        }
    }

    /** One rendered chat line: a translation key, its arguments, a warning flag and whether arg0 is a key. */
    public record Line(String key, List<Object> args, boolean warning, boolean translateFirstArg) {
        public Line {
            Objects.requireNonNull(key, "key");
            args = List.copyOf(args);
        }

        public static Line plain(String key, Object... args) {
            return new Line(key, Arrays.asList(args), false, false);
        }

        public static Line warning(String key, Object... args) {
            return new Line(key, Arrays.asList(args), true, false);
        }

        /** A debug statistics row: {@code admin_debug_line} with a label key and a value. */
        public static Line stat(String labelKey, Object value) {
            return new Line(DEBUG_LINE, Arrays.asList(labelKey, value), false, true);
        }

        /** A debug statistics row whose source API is not available in this version. */
        public static Line unavailable(String labelKey) {
            return new Line(DEBUG_UNAVAILABLE, List.of(labelKey), false, true);
        }
    }

    /** Number of lines that get warning styling in the doctor summary. */
    public static int warningCount(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        int warnings = 0;
        if (snapshot.data().abnormal() > 0) {
            warnings++;
        }
        if (!snapshot.backup().dirExists()) {
            warnings++;
        }
        return warnings;
    }

    /** Whether the snapshot has any value worth a warning-coloured line. */
    public static boolean hasWarnings(Snapshot snapshot) {
        return warningCount(snapshot) > 0;
    }

    /**
     * Doctor summary: five sections plus condensed value lines and one result line. Exactly
     * {@value #MAX_LINES} lines at most; related values share a line so that no section is dropped.
     */
    public static List<Line> doctorLines(Snapshot s) {
        Objects.requireNonNull(s, "snapshot");
        DataCounts d = s.data();
        BackupState b = s.backup();
        DiscordState c = s.discord();
        IoState io = s.io();
        List<Line> lines = new ArrayList<>(MAX_LINES);
        lines.add(Line.plain(SECTION_SERVER));
        lines.add(Line.plain("message.itemmilestonelog.doctor_server_world", s.worldName()));
        lines.add(Line.plain("message.itemmilestonelog.doctor_server_load",
                s.serverPlayers(), s.serverTicks()));
        lines.add(Line.plain(SECTION_DATA));
        lines.add(Line.plain("message.itemmilestonelog.doctor_data_empty", d.empty()));
        lines.add(Line.plain("message.itemmilestonelog.doctor_data_counts",
                d.players(), d.records(), d.targets(), d.worldFirsts()));
        lines.add(d.abnormal() > 0
                ? Line.warning("message.itemmilestonelog.doctor_data_abnormal",
                        d.negativeTicks(), d.unknownEpoch())
                : Line.plain("message.itemmilestonelog.doctor_data_abnormal",
                        d.negativeTicks(), d.unknownEpoch()));
        lines.add(Line.plain(SECTION_BACKUP));
        lines.add(b.dirExists()
                ? Line.plain("message.itemmilestonelog.doctor_backup_dir", true)
                : Line.warning("message.itemmilestonelog.doctor_backup_dir", false));
        lines.add(Line.plain("message.itemmilestonelog.doctor_backup_files", b.matchingFiles()));
        lines.add(Line.plain("message.itemmilestonelog.doctor_backup_newest", b.newestTimeText()));
        lines.add(Line.plain(SECTION_DISCORD));
        lines.add(Line.plain("message.itemmilestonelog.doctor_discord_webhook", c.webhookStatus()));
        lines.add(Line.plain("message.itemmilestonelog.doctor_discord_digest",
                c.digestEnabled(), c.digestIntervalDays()));
        lines.add(Line.plain("message.itemmilestonelog.doctor_discord_epoch", c.lastDigestEpochMillis()));
        lines.add(Line.plain(SECTION_IO));
        lines.add(Line.plain("message.itemmilestonelog.doctor_io_state",
                io.present(), io.accepting(), io.importInFlight()));
        lines.add(Line.plain("message.itemmilestonelog.doctor_io_pending",
                io.pending(), io.ioThreadNames().isEmpty() ? "-" : io.ioThreadNames()));
        int warnings = warningCount(s);
        lines.add(warnings == 0
                ? Line.plain(DOCTOR_OK)
                : Line.warning(DOCTOR_WARNINGS, warnings));
        return cap(lines);
    }

    /**
     * {@code admin debug}: header plus runtime counters and queue state. Optional APIs fall back to
     * {@link Line#unavailable(String)} instead of throwing on a version that does not expose them.
     */
    public static List<Line> adminDebugLines(Snapshot s) {
        Objects.requireNonNull(s, "snapshot");
        RuntimeState r = s.runtime();
        IoState io = s.io();
        List<Line> lines = new ArrayList<>(MAX_LINES);
        lines.add(Line.plain(DEBUG_HEADER));
        lines.add(r.tpsAvailable()
                ? Line.stat("message.itemmilestonelog.admin_debug_stat_tps", r.tps())
                : Line.unavailable("message.itemmilestonelog.admin_debug_stat_tps"));
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_thread", r.threadName()));
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_io_pending", io.pending()));
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_io_kinds", io.pendingByKindText()));
        lines.add(io.ioThreadNames().isEmpty()
                ? Line.unavailable("message.itemmilestonelog.admin_debug_stat_io_thread")
                : Line.stat("message.itemmilestonelog.admin_debug_stat_io_thread", io.ioThreadNames()));
        lines.add(r.notificationsAvailable()
                ? Line.stat("message.itemmilestonelog.admin_debug_stat_notifications", r.pendingNotifications())
                : Line.unavailable("message.itemmilestonelog.admin_debug_stat_notifications"));
        lines.add(r.droppedAvailable()
                ? Line.stat("message.itemmilestonelog.admin_debug_stat_dropped", r.droppedPosts())
                : Line.unavailable("message.itemmilestonelog.admin_debug_stat_dropped"));
        lines.add(r.discordQueueAvailable()
                ? Line.stat("message.itemmilestonelog.admin_debug_stat_discord_queue", r.pendingDiscordSends())
                : Line.unavailable("message.itemmilestonelog.admin_debug_stat_discord_queue"));
        lines.add(r.deadWebhookAvailable()
                ? Line.stat("message.itemmilestonelog.admin_debug_stat_dead_webhook", r.deadWebhook())
                : Line.unavailable("message.itemmilestonelog.admin_debug_stat_dead_webhook"));
        lines.add(r.discordThreadAvailable()
                ? Line.stat("message.itemmilestonelog.admin_debug_stat_discord_thread", r.discordThreadAlive())
                : Line.unavailable("message.itemmilestonelog.admin_debug_stat_discord_thread"));
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_memory",
                r.totalMemoryMb() + "/" + r.maxMemoryMb()));
        // A condensed part of the doctor summary, so debug still shows the core read-only state
        // without exceeding the 20-line cap.
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_data",
                s.data().players() + "/" + s.data().records() + "/" + s.data().targets()));
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_webhook", s.discord().webhookStatus()));
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_backup", s.backup().matchingFiles()));
        lines.add(Line.stat("message.itemmilestonelog.admin_debug_stat_io_state", io.present()));
        return cap(lines);
    }

    private static List<Line> cap(List<Line> lines) {
        if (lines.size() <= MAX_LINES) {
            return List.copyOf(lines);
        }
        return List.copyOf(lines.subList(0, MAX_LINES));
    }
}
