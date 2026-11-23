package com.pengin0503.itemmilestonelog;

import java.util.List;
import java.util.Objects;

/**
 * Pure-Java pagination and command-link helpers for long chat lists (F30),
 * shared across all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class Pagination {
    /** Number of rows shown per page when a page number is specified. */
    public static final int DEFAULT_PAGE_SIZE = 15;

    /** Root command prefix used in clickable page-navigation links. */
    public static final String ROOT_COMMAND = "/milestonelog";

    /** Filter modes for {@code /milestonelog targets [pending|done] [<page>]}. */
    public enum TargetFilter {
        ALL,
        PENDING,
        DONE
    }

    /**
     * One sliced page of a list together with its 1-based page metadata.
     *
     * @param items      immutable slice of items on this page (empty when {@link #isOutOfRange()})
     * @param page       1-based requested page number
     * @param totalPages total number of pages (0 when {@code totalItems == 0})
     * @param totalItems total number of items before slicing
     */
    public record PageSlice<T>(List<T> items, int page, int totalPages, int totalItems) {
        public PageSlice {
            Objects.requireNonNull(items, "items");
            items = List.copyOf(items);
        }

        /** Whether the requested page does not exist (page &lt; 1 or page &gt; totalPages). */
        public boolean isOutOfRange() {
            return page < 1 || page > totalPages;
        }

        /** Whether a previous page link should be shown for this page. */
        public boolean hasPrevious() {
            return !isOutOfRange() && page > 1;
        }

        /** Whether a next page link should be shown for this page. */
        public boolean hasNext() {
            return !isOutOfRange() && page < totalPages;
        }
    }

    private Pagination() {
    }

    /** Computes the total number of pages using {@link #DEFAULT_PAGE_SIZE}. */
    public static int totalPages(int totalItems) {
        return totalPages(totalItems, DEFAULT_PAGE_SIZE);
    }

    /**
     * Computes the total number of pages for {@code totalItems} with {@code pageSize} rows per page.
     * Returns 0 when {@code totalItems <= 0}.
     */
    public static int totalPages(int totalItems, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
        }
        if (totalItems <= 0) {
            return 0;
        }
        return (totalItems - 1) / pageSize + 1;
    }

    /** Slices {@code allItems} for the 1-based {@code page} using {@link #DEFAULT_PAGE_SIZE}. */
    public static <T> PageSlice<T> slice(List<T> allItems, int page) {
        return slice(allItems, page, DEFAULT_PAGE_SIZE);
    }

    /**
     * Slices {@code allItems} for the 1-based {@code page} with {@code pageSize} rows per page.
     * Preserves the exact order of {@code allItems}. When {@code page} is out of range,
     * returns a {@link PageSlice} with empty items and {@link PageSlice#isOutOfRange()} true.
     */
    public static <T> PageSlice<T> slice(List<T> allItems, int page, int pageSize) {
        Objects.requireNonNull(allItems, "allItems");
        int totalItems = allItems.size();
        int total = totalPages(totalItems, pageSize);
        if (page < 1 || page > total) {
            return new PageSlice<>(List.of(), page, total, totalItems);
        }
        long fromLong = (long) (page - 1) * pageSize;
        int from = (int) fromLong;
        int to = (int) Math.min(totalItems, fromLong + pageSize);
        return new PageSlice<>(allItems.subList(from, to), page, total, totalItems);
    }

    /** Builds the clickable {@code run_command} string for {@code commandPrefix} at 1-based {@code page}. */
    public static String pageCommand(String commandPrefix, int page) {
        Objects.requireNonNull(commandPrefix, "commandPrefix");
        if (commandPrefix.isBlank()) {
            throw new IllegalArgumentException("commandPrefix must not be blank");
        }
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1: " + page);
        }
        return commandPrefix + " " + page;
    }

    public static String listCommandPrefix() {
        return ROOT_COMMAND + " list";
    }

    public static String targetsCommandPrefix(TargetFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return switch (filter) {
            case ALL -> ROOT_COMMAND + " targets";
            case PENDING -> ROOT_COMMAND + " targets pending";
            case DONE -> ROOT_COMMAND + " targets done";
        };
    }

    public static String firstsCommandPrefix() {
        return ROOT_COMMAND + " firsts";
    }

    /** Preserves {@code playerQuery} spelled exactly as typed by the caller. */
    public static String lookupCommandPrefix(String playerQuery) {
        Objects.requireNonNull(playerQuery, "playerQuery");
        return ROOT_COMMAND + " lookup " + playerQuery;
    }

    public static String adminPlayersCommandPrefix() {
        return ROOT_COMMAND + " admin players";
    }

    /** Preserves {@code playerQuery} spelled exactly as typed by the caller. */
    public static String adminListCommandPrefix(String playerQuery) {
        Objects.requireNonNull(playerQuery, "playerQuery");
        return ROOT_COMMAND + " admin " + playerQuery + " list";
    }

    /** Preserves {@code playerQuery} spelled exactly as typed by the caller. */
    public static String adminTargetsCommandPrefix(String playerQuery) {
        Objects.requireNonNull(playerQuery, "playerQuery");
        return ROOT_COMMAND + " admin " + playerQuery + " targets";
    }
}
