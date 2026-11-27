package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * F-150-38 (N167): pure rotation and display-order rules for {@link HolderEntry} lists.
 *
 * <p>Only config-folder / SavedData persistence uses this class; export/import JSON and backup
 * formats are unchanged. Must remain Java 21 compatible and Minecraft free.
 */
public final class FirstsHolderHistory {
    /** Maximum number of stored holder changes per target key. */
    public static final int HISTORY_LIMIT = 20;

    private FirstsHolderHistory() {
    }

    /**
     * Appends {@code entry} to an oldest-first stored list, rotating out the oldest entry once
     * {@link #HISTORY_LIMIT} is exceeded. Never mutates {@code current}; invalid entries are a
     * no-op. Returns an immutable list.
     */
    public static List<HolderEntry> append(List<HolderEntry> current, HolderEntry entry) {
        List<HolderEntry> base = current == null ? List.of() : current;
        if (entry == null || !entry.isValid()) {
            return List.copyOf(base);
        }
        List<HolderEntry> updated = new ArrayList<>(base);
        updated.add(entry);
        while (updated.size() > HISTORY_LIMIT) {
            updated.remove(0);
        }
        return List.copyOf(updated);
    }

    /** Returns a copy of an oldest-first stored list in newest-first display order. */
    public static List<HolderEntry> newestFirst(List<HolderEntry> stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        List<HolderEntry> reversed = new ArrayList<>(stored);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }
}
