package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java snapshot of the Discord configuration/connection state shown by
 * {@code /milestonelog discordstatus} (F-150-54). Shared by all five Minecraft trees.
 *
 * <p>The snapshot deliberately contains only a resolution status enum name, an optional
 * environment variable <em>name</em>, counts and configuration flags. A resolved webhook URL,
 * its {@code configuredKey} and exception text never enter this record or its rendered lines.
 * It must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public record DiscordStatusSnapshot(
        String webhookStatus,
        String envVarName,
        boolean embedMode,
        String notifyScope,
        boolean digestEnabled,
        int intervalDays,
        long lastDigestEpochMillis,
        long nowMillis,
        boolean pendingCountAvailable,
        int pendingSends) {

    /** Hard cap on status lines, from F-150-54. */
    public static final int MAX_LINES = 12;

    /**
     * Next digest due time in epoch milliseconds. A first run is due at the current time
     * (the existing periodic check initializes its timestamp instead of sending), and an
     * overflowing addition saturates instead of wrapping into the past.
     */
    public long nextDigestDueMillis() {
        if (lastDigestEpochMillis <= 0L) {
            return Math.max(0L, nowMillis);
        }
        long due = lastDigestEpochMillis + DiscordDigestLogic.intervalMillis(intervalDays);
        return due < 0L ? Long.MAX_VALUE : due;
    }

    /** Renders at most {@value #MAX_LINES} lines; no value can contain the webhook URL. */
    public List<DiagnosticsSnapshot.Line> lines() {
        List<DiagnosticsSnapshot.Line> lines = new ArrayList<>(MAX_LINES);
        lines.add(DiagnosticsSnapshot.Line.plain(
                "message.itemmilestonelog.discordstatus_webhook", webhookStatus));
        if (envVarName != null && !envVarName.isBlank()) {
            lines.add(DiagnosticsSnapshot.Line.plain(
                    "message.itemmilestonelog.discordstatus_env", envVarName));
        }
        lines.add(DiagnosticsSnapshot.Line.plain(
                "message.itemmilestonelog.discordstatus_embed", embedMode));
        lines.add(DiagnosticsSnapshot.Line.plain(
                "message.itemmilestonelog.discordstatus_scope", notifyScope));
        lines.add(DiagnosticsSnapshot.Line.plain(
                "message.itemmilestonelog.discordstatus_digest", digestEnabled));
        lines.add(DiagnosticsSnapshot.Line.plain(
                "message.itemmilestonelog.discordstatus_interval", intervalDays));
        lines.add(DiagnosticsSnapshot.Line.plain(
                "message.itemmilestonelog.discordstatus_epoch", lastDigestEpochMillis));
        lines.add(DiagnosticsSnapshot.Line.plain(
                "message.itemmilestonelog.discordstatus_next", nextDigestDueMillis()));
        if (pendingCountAvailable) {
            lines.add(DiagnosticsSnapshot.Line.plain(
                    "message.itemmilestonelog.discordstatus_pending", pendingSends));
        } else {
            lines.add(DiagnosticsSnapshot.Line.plain(
                    "message.itemmilestonelog.discordstatus_pending_unavailable"));
        }
        return List.copyOf(lines);
    }
}
