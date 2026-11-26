package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure-Java line model for {@code /milestonelog preset show} (spec 1.4.0 F-140-16 / N07),
 * shared across all Minecraft versions.
 *
 * <p>The command resolves a preset into a {@link ShowData} snapshot (display names are resolved
 * for the receiving language by the caller) and maps the returned {@link Line}s to translation
 * keys. All metadata-presence decisions, number formatting and row-cap/omission handling live
 * here so they are unit-testable without Minecraft or NeoForge.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetShowLogic {
    /** Outcome of a show request; the command maps each status to its own message. */
    public enum Status {
        /** {@code presetsEnabled == false}; the command reports the existing disabled message. */
        DISABLED,
        /** The requested name does not resolve to a preset. */
        UNKNOWN,
        /** The preset could be shown. */
        OK
    }

    /** One output line: a kind plus the string values the command substitutes into its lang key. */
    public enum LineKind {
        DESCRIPTION,
        CATEGORY,
        VERSION,
        ORDER,
        REQUIRED_MODS,
        UNLOCK_AFTER_HOURS,
        NEXT_PRESETS,
        GROUP,
        ITEM,
        ITEMS_MORE
    }

    /**
     * One line of the show output.
     *
     * @param kind   which translation key / layout the command uses
     * @param values the values substituted into that key, already stringified and locale-free
     */
    public record Line(LineKind kind, List<String> values) {
        public Line {
            Objects.requireNonNull(kind, "kind");
            values = values == null ? List.of() : List.copyOf(values);
        }

        public static Line of(LineKind kind, String... values) {
            return new Line(kind, values == null ? List.of() : List.of(values));
        }
    }

    /**
     * A group summary as shown by {@code preset show}: the already-resolved display name (or the
     * group ID) and the number of member keys after validation (F-140-10).
     */
    public record GroupSummary(String displayName, int itemCount) {
        public GroupSummary {
            displayName = displayName == null ? "" : displayName;
            itemCount = Math.max(0, itemCount);
        }
    }

    /**
     * Everything {@code preset show} displays apart from the header (which the command builds
     * with its existing preset-display-name helper so built-in translations keep working).
     *
     * @param description       resolved description, or null/blank when absent
     * @param category          normalized category, or null when absent
     * @param version           preset version, or null when unversioned
     * @param order             list order (default 0; always shown)
     * @param requiredMods      required mod IDs, empty when none
     * @param unlockAfterHours  unlock delay, null when unset; only values &gt; 0 are shown
     * @param nextPresets       next-preset IDs, empty when none
     * @param groups            group summaries, in definition order
     * @param items             expanded item keys, in definition order
     */
    public record ShowData(
            String description,
            String category,
            Integer version,
            int order,
            List<String> requiredMods,
            Double unlockAfterHours,
            List<String> nextPresets,
            List<GroupSummary> groups,
            List<TargetKey> items) {

        public ShowData {
            requiredMods = requiredMods == null ? List.of() : List.copyOf(requiredMods);
            nextPresets = nextPresets == null ? List.of() : List.copyOf(nextPresets);
            groups = groups == null ? List.of() : List.copyOf(groups);
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Result of a show request.
     *
     * @param status {@link Status#DISABLED} / {@link Status#UNKNOWN} carry no lines
     * @param lines  output lines after the header, in display order
     */
    public record Result(Status status, List<Line> lines) {
        public Result {
            Objects.requireNonNull(status, "status");
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    private PresetShowLogic() {
    }

    /**
     * Builds the show result. Disabled wins over an unknown name, matching the existing
     * {@code presetsEnabled == false} behavior of the other preset commands.
     */
    public static Result show(ShowData data, boolean presetsEnabled, int maxRows) {
        if (!presetsEnabled) {
            return new Result(Status.DISABLED, List.of());
        }
        if (data == null) {
            return new Result(Status.UNKNOWN, List.of());
        }
        return new Result(Status.OK, lines(data, maxRows));
    }

    /** Builds the metadata, group and item lines for an already-resolved preset snapshot. */
    public static List<Line> lines(ShowData data, int maxRows) {
        Objects.requireNonNull(data, "data");
        List<Line> lines = new ArrayList<>();

        if (hasText(data.description())) {
            lines.add(Line.of(LineKind.DESCRIPTION, data.description().trim()));
        }
        if (hasText(data.category())) {
            lines.add(Line.of(LineKind.CATEGORY, data.category().trim()));
        }
        if (data.version() != null) {
            lines.add(Line.of(LineKind.VERSION, String.valueOf(data.version())));
        }
        lines.add(Line.of(LineKind.ORDER, String.valueOf(data.order())));

        String requiredMods = joinNonBlank(data.requiredMods());
        if (!requiredMods.isEmpty()) {
            lines.add(Line.of(LineKind.REQUIRED_MODS, requiredMods));
        }
        if (data.unlockAfterHours() != null && data.unlockAfterHours() > 0.0d) {
            lines.add(Line.of(LineKind.UNLOCK_AFTER_HOURS, formatNumber(data.unlockAfterHours())));
        }
        String nextPresets = joinNonBlank(data.nextPresets());
        if (!nextPresets.isEmpty()) {
            lines.add(Line.of(LineKind.NEXT_PRESETS, nextPresets));
        }
        for (GroupSummary group : data.groups()) {
            if (group == null) {
                continue;
            }
            lines.add(Line.of(LineKind.GROUP, group.displayName(), String.valueOf(group.itemCount())));
        }

        List<TargetKey> items = new ArrayList<>();
        for (TargetKey key : data.items()) {
            if (key != null) {
                items.add(key);
            }
        }
        int limit = Math.max(0, Math.min(maxRows, items.size()));
        for (int i = 0; i < limit; i++) {
            TargetKey key = items.get(i);
            lines.add(Line.of(LineKind.ITEM, key.itemId(), String.valueOf(key.count())));
        }
        int hidden = items.size() - limit;
        if (hidden > 0) {
            lines.add(Line.of(LineKind.ITEMS_MORE, String.valueOf(hidden)));
        }
        return List.copyOf(lines);
    }

    /** Locale-independent rendering of an unlock delay ({@code 24} rather than {@code 24.0}). */
    static String formatNumber(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1.0e18d) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String joinNonBlank(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (!hasText(value)) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(value.trim());
        }
        return joined.toString();
    }
}
