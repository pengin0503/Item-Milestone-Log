package com.pengin0503.itemmilestonelog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * F-150-87 (N200): pure-Java renderer for the client-only {@code /imlclient diag} output.
 *
 * <p>This class never touches Minecraft, NeoForge, the network or the file system: the per-tree
 * command gathers plain values and passes them in as {@link Inputs}. Only counts, booleans and
 * version names are accepted, and free-form strings are reduced to a safe token, so no player
 * name, UUID, address, webhook URL, {@code env:} value or absolute home path can reach chat
 * (D-150-236). Output is capped at {@value #MAX_LINES} lines and uses exactly the six heading
 * keys defined by the spec ({@code command.itemmilestonelog.imlclient.diag.*}).
 */
public final class ClientDiagRender {
    /** Hard cap from F-150-87-1; the current renderer emits six lines. */
    public static final int MAX_LINES = 20;
    /** Prefix of the six new lang keys; no other key is introduced by this feature. */
    public static final String KEY_PREFIX = "command.itemmilestonelog.imlclient.diag.";
    /** The six spec-defined headings, in emission order. */
    public static final String[] LINE_KEYS = {
            KEY_PREFIX + "version",
            KEY_PREFIX + "channels",
            KEY_PREFIX + "keys",
            KEY_PREFIX + "cache",
            KEY_PREFIX + "local",
            KEY_PREFIX + "sync"
    };

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private ClientDiagRender() {
    }

    /**
     * All values needed for one diagnostic snapshot. {@code keysBound} has four entries in the
     * order open-gui, add-hovered, mute-sound, copy-recent; a shorter array is padded with
     * "unbound" so a malformed caller cannot fabricate extra bindings.
     */
    public record Inputs(String loader, String mcVersion,
                         boolean requestSync, boolean milestoneAction, boolean syncMilestones,
                         boolean syncExtra, boolean milestoneEffect,
                         boolean[] keysBound,
                         boolean cachePresent, long cacheSavedAtMillis,
                         int cacheTargets, int cacheRecords, int cachePresets, long cacheFileBytes,
                         int profiles, int pins, int history,
                         int syncTargets, int syncRecords, int syncPresets) {
        public Inputs {
            keysBound = keysBound == null ? new boolean[4] : keysBound.clone();
        }

        private boolean bound(int index) {
            return index >= 0 && index < keysBound.length && keysBound[index];
        }
    }

    /** One translatable chat line: a fixed spec key plus already-sanitised string arguments. */
    public record Line(String key, String[] args) {
        public Line {
            args = args == null ? new String[0] : args.clone();
        }
    }

    /** Renders at most {@value #MAX_LINES} lines; channel absence is reported as {@code no}. */
    public static List<Line> render(Inputs in) {
        if (in == null) {
            throw new IllegalArgumentException("diag inputs must not be null");
        }
        List<Line> lines = new ArrayList<>(LINE_KEYS.length);
        lines.add(new Line(LINE_KEYS[0], new String[]{
                safeToken(in.loader()), safeToken(in.mcVersion())}));
        lines.add(new Line(LINE_KEYS[1], new String[]{String.join(" ",
                "requestSync=" + yesNo(in.requestSync()),
                "action=" + yesNo(in.milestoneAction()),
                "sync=" + yesNo(in.syncMilestones()),
                "extra=" + yesNo(in.syncExtra()),
                "effect=" + yesNo(in.milestoneEffect()))}));
        lines.add(new Line(LINE_KEYS[2], new String[]{String.join(" ",
                "open=" + yesNo(in.bound(0)),
                "hover=" + yesNo(in.bound(1)),
                "mute=" + yesNo(in.bound(2)),
                "copy=" + yesNo(in.bound(3)))}));
        lines.add(new Line(LINE_KEYS[3], new String[]{String.join(" ",
                "present=" + yesNo(in.cachePresent()),
                "saved=" + stamp(in.cacheSavedAtMillis()),
                "targets=" + count(in.cacheTargets()),
                "records=" + count(in.cacheRecords()),
                "presets=" + count(in.cachePresets()),
                "bytes=" + count(in.cacheFileBytes()))}));
        lines.add(new Line(LINE_KEYS[4], new String[]{String.join(" ",
                "profiles=" + count(in.profiles()),
                "pins=" + count(in.pins()),
                "history=" + count(in.history()))}));
        lines.add(new Line(LINE_KEYS[5], new String[]{String.join(" ",
                "targets=" + count(in.syncTargets()),
                "records=" + count(in.syncRecords()),
                "presets=" + count(in.syncPresets()))}));
        if (lines.size() > MAX_LINES) {
            return List.copyOf(lines.subList(0, MAX_LINES));
        }
        return List.copyOf(lines);
    }

    /** Yes/no only; never a token, key code or configuration value. */
    public static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    /** Non-negative decimal count; no file names or paths. */
    public static String count(long value) {
        return Long.toString(Math.max(0L, value));
    }

    /** UTC stamp with no path separator, colon, scheme or home directory. */
    public static String stamp(long epochMillis) {
        return epochMillis <= 0L ? "none" : STAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Whitelists loader/version names: anything outside {@code [A-Za-z0-9._+-]} or longer than
     * 32 characters becomes {@code unknown}. A hostile value can therefore never smuggle a URL,
     * {@code env:} reference or absolute path into the diagnostic chat output.
     */
    public static String safeToken(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 32) {
            return "unknown";
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == '+';
            if (!allowed) {
                return "unknown";
            }
        }
        return raw;
    }
}
