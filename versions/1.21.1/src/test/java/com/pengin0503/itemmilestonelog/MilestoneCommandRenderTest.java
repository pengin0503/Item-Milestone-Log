package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-MN-1 / R2-MN-2(b): pins rendered command output through the real handler seams. Version-local
 * because the {@code CommandSourceStack} constructor differs between the 1.21.1 and 26.x trees.
 */
class MilestoneCommandRenderTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void recapOverflowLineIsRenderedForReturnAndDaily() {
        List<CommandAnalysisModel.RecapEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries.add(new CommandAnalysisModel.RecapEntry(
                    "testmod:overflow_item", 1, "Holder" + i, 1_795_000_000_000L + i));
        }
        CommandAnalysisModel.RecapResult result = new CommandAnalysisModel.RecapResult(entries, 2, 0, 0);
        String more = ServerLang.trForLang("en_us", "message.itemmilestonelog.recap_more", 2).getString();
        for (CommandAnalysisModel.RecapKind kind : List.of(CommandAnalysisModel.RecapKind.RETURN,
                CommandAnalysisModel.RecapKind.DAILY)) {
            FakeRenderSource source = new FakeRenderSource();
            int lines = MilestoneCommands.sendRecapLines(source, null, result, kind, UTC);
            assertEquals(11, lines, kind.name());
            assertEquals(11, source.messages.size(), kind.name());
            assertEquals(1, countText(source, more), "one overflow line, " + kind);
        }
    }

    @Test
    void previewRendersTheRelatedSectionOnlyOnce() {
        List<TargetKey> relatedKeys = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            relatedKeys.add(TargetKey.of("c:tag_" + i));
        }
        Set<TargetKey> effective = Set.of(relatedKeys.get(0));
        String baseHeader = ServerLang.trForLang("en_us", "message.itemmilestonelog.info_tag_header").getString();
        String previewHeader = ServerLang.trForLang("en_us",
                "message.itemmilestonelog.info_preview_header", 12).getString();
        String more = ServerLang.trForLang("en_us", "message.itemmilestonelog.info_tag_more", 2).getString();
        String targetStatus = ServerLang.trForLang("en_us", "message.itemmilestonelog.info_status_target").getString();
        String noneStatus = ServerLang.trForLang("en_us", "message.itemmilestonelog.info_status_none").getString();

        for (boolean preview : List.of(false, true)) {
            FakeRenderSource source = new FakeRenderSource();
            int lines = MilestoneCommands.sendInfoRelatedTags(source, null, relatedKeys, Map.of(),
                    effective, Set.of(), EMPTY_TAGS, Map.of(), UTC, preview);
            assertEquals(12, lines, "header + 10 rows + overflow for preview=" + preview);
            assertEquals(12, source.messages.size());
            assertEquals(preview ? 0 : 1, countText(source, baseHeader), "base header count for preview=" + preview);
            assertEquals(preview ? 1 : 0, countText(source, previewHeader));
            assertEquals(1, countText(source, more), "one overflow line: " + preview);
            assertTrue(hasText(source, targetStatus), "first row keeps its real target status");
            assertTrue(hasText(source, noneStatus), "untracked rows render as none");
        }
        // The non-preview path is the one that uses the base header.
        FakeRenderSource plain = new FakeRenderSource();
        MilestoneCommands.sendInfoRelatedTags(plain, null, relatedKeys, Map.of(), effective,
                Set.of(), EMPTY_TAGS, Map.of(), UTC, false);
        assertEquals(1, countText(plain, baseHeader));
    }

    // ------------------------------------------ F-150-52 / F-150-53 diagnostics (PR-2)

    private static DiagnosticsSnapshot.Snapshot diagnosticsSnapshot(
            boolean dirExists, int negativeTicks, int unknownEpoch) {
        return new DiagnosticsSnapshot.Snapshot("World", 2, 1234,
                new DiagnosticsSnapshot.DataCounts(false, 3, 10, 8, 2, negativeTicks, unknownEpoch),
                new DiagnosticsSnapshot.BackupState(dirExists, dirExists ? 4 : 0,
                        dirExists ? "milestones-20261127-120000" : "none"),
                new DiagnosticsSnapshot.DiscordState("RESOLVED", true, 7, 1234L),
                new DiagnosticsSnapshot.IoState(true, true, 1, false, "BACKUP=1", "itemmilestonelog-io"),
                new DiagnosticsSnapshot.RuntimeState(false, 0.0, "Server thread", true, 2,
                        true, 0L, true, 3, true, false, true, true, 512L, 1024L));
    }

    @Test
    void doctorRendersWithinCapForHealthyBrokenAndMissingBackupDir() {
        String ok = ServerLang.trForLang("en_us", "message.itemmilestonelog.doctor_ok").getString();
        String warnings = ServerLang.trForLang("en_us", "message.itemmilestonelog.doctor_warning_count", 1).getString();
        List<boolean[]> cases = List.of(
                new boolean[]{true, false},   // healthy
                new boolean[]{true, true},    // negative/unknown-epoch records seeded
                new boolean[]{false, false}); // backup directory missing
        for (boolean[] flags : cases) {
            FakeRenderSource source = new FakeRenderSource();
            int negative = flags[1] ? 2 : 0;
            int lines = MilestoneCommands.sendDoctorLines(source, diagnosticsSnapshot(flags[0], negative, negative));
            assertEquals(lines, source.messages.size(), "one chat message per rendered line");
            assertTrue(lines <= DiagnosticsSnapshot.MAX_LINES, "doctor rendered " + lines + " lines");
            assertTrue(lines >= 15, "doctor summary must not silently drop sections");
            for (Component message : source.messages) {
                String text = message.getString();
                assertFalse(text.contains("https://"), "no webhook URL in doctor output: " + text);
                assertFalse(text.contains("discord.com/api/webhooks"), "no webhook token in doctor output");
            }
            if (flags[0] && !flags[1]) {
                assertEquals(1, countText(source, ok), "healthy fixture prints doctor_ok");
            } else {
                assertTrue(hasText(source, warnings), "broken fixture prints warning count");
            }
        }
    }

    @Test
    void adminDebugRendersWithinCapWithoutTracesOrUrls() {
        FakeRenderSource source = new FakeRenderSource();
        int lines = MilestoneCommands.sendAdminDebugLines(source, diagnosticsSnapshot(true, 0, 0));
        assertEquals(lines, source.messages.size());
        assertTrue(lines <= DiagnosticsSnapshot.MAX_LINES, "debug rendered " + lines + " lines");
        String header = ServerLang.trForLang("en_us", "message.itemmilestonelog.admin_debug_header").getString();
        assertTrue(hasText(source, header));
        String unavailable = ServerLang.trForLang("en_us",
                "message.itemmilestonelog.admin_debug_unavailable", "Average TPS").getString();
        assertTrue(hasText(source, unavailable), "TPS API is absent in every version -> fixed wording");
        for (Component message : source.messages) {
            String text = message.getString();
            assertFalse(text.contains("Exception"), "no exception trace in debug output: " + text);
            assertFalse(text.contains("https://"), "no webhook URL in debug output: " + text);
        }
    }

    @Test
    void diagnosticsReadsNeverCreatePlayersOrDirtySavedData() {
        MilestoneData data = new MilestoneData();
        DiagnosticsSnapshot.DataCounts empty = MilestoneCommands.dataCountsFor(data);
        assertEquals(0, empty.players());
        assertEquals(0, empty.records());
        assertTrue(empty.empty());
        assertTrue(data.allPlayers().isEmpty(), "diagnostics must not insert PlayerData");
        assertFalse(data.isDirty(), "diagnostics must not dirty a data-less set");

        UUID playerId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        data.player(playerId).records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(-5));
        boolean dirtyBefore = data.isDirty();
        int playersBefore = data.allPlayers().size();
        DiagnosticsSnapshot.DataCounts broken = MilestoneCommands.dataCountsFor(data);
        assertEquals(1, broken.negativeTicks(), "negative ticks are counted, not repaired");
        assertEquals(1, broken.unknownEpoch(), "unknown epoch is counted, not repaired");
        assertEquals(1, broken.players());
        assertEquals(dirtyBefore, data.isDirty(), "a read must not change the dirty flag");
        assertEquals(playersBefore, data.allPlayers().size(), "a read must not create players");
    }

    @Test
    void discordTestPayloadPreviewEqualsSenderConstructionWithoutSending() throws Exception {
        for (boolean embeds : List.of(false, true)) {
            for (String language : List.of("en_us", "ja_jp")) {
                FakeRenderSource source = new FakeRenderSource();
                assertEquals(2, MilestoneCommands.discordTestPayloadPreview(source, embeds, language));
                assertEquals(2, source.messages.size(), "header and one JSON line only");
                assertEquals(ServerLang.tr(source,
                                "message.itemmilestonelog.discordtest_payload_header").getString(),
                        source.messages.get(0).getString(),
                        "the header follows the caller's language, not the configured payload language");
                String preview = source.messages.get(1).getString();
                assertFalse(preview.contains("\n"), "JSON preview must fit on one chat line");
                assertFalse(preview.contains("https://"), "no URL in payload preview");
                assertFalse(preview.contains("discordtest_sending"), "preview must not send");
                assertFalse(preview.contains("discordtest_success"), "preview must not report a send result");

                // Rebuild with the preview's own timestamp so Instant.now() is deterministic;
                // the same builder means the preview keeps the sender's truncation rules.
                com.google.gson.JsonObject body =
                        com.google.gson.JsonParser.parseString(preview).getAsJsonObject();
                java.time.Instant timestamp = embeds
                        ? java.time.Instant.parse(body.getAsJsonArray("embeds")
                                .get(0).getAsJsonObject().get("timestamp").getAsString())
                        : null;
                assertEquals(preview, TextEscaping.removeLineBreaks(
                                DiscordNotifier.buildTestPayloadJson(embeds, language, timestamp)),
                        "preview must be byte-equal to the sender's payload construction");
            }
        }
        java.lang.reflect.Field inFlight = DiscordNotifier.class.getDeclaredField("TEST_IN_PROGRESS");
        inFlight.setAccessible(true);
        assertFalse(((java.util.concurrent.atomic.AtomicBoolean) inFlight.get(null)).get(),
                "preview must not claim TEST_IN_PROGRESS");
        assertEquals(0, DiscordNotifier.pendingSendCount(), "preview must not enqueue a send");
    }

    @Test
    void discordStatusLinesRenderSnapshotFieldsWithoutSecrets() {
        long now = 1_700_000_000_000L;
        DiscordStatusSnapshot snapshot = new DiscordStatusSnapshot(
                "RESOLVED", "DISCORD_WEBHOOK", true, "all", true, 7, now - 1_000L, now, true, 4);
        FakeRenderSource source = new FakeRenderSource();
        int lines = MilestoneCommands.sendDiscordStatusLines(source, snapshot);
        assertEquals(snapshot.lines().size(), lines);
        assertEquals(lines, source.messages.size());
        assertTrue(source.messages.get(0).getString().contains("RESOLVED"));
        assertTrue(source.messages.get(1).getString().contains("DISCORD_WEBHOOK"));
        assertTrue(source.messages.stream().anyMatch(c -> c.getString().contains("7")));
        for (Component message : source.messages) {
            assertFalse(message.getString().contains("https://"), "no webhook URL in status output");
        }
    }

    private static final TagIndex EMPTY_TAGS = TagIndex.of(Map.of());

    private static int countText(FakeRenderSource source, String text) {
        int count = 0;
        for (Component message : source.messages) {
            if (message.getString().contains(text)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasText(FakeRenderSource source, String text) {
        return countText(source, text) > 0;
    }

    private static final class FakeRenderSource extends CommandSourceStack {
        private final List<Component> messages = new ArrayList<>();

        private FakeRenderSource() {
            super(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 0, "test",
                    Component.literal("test"), null, null);
        }

        @Override
        public void sendFailure(Component message) {
            messages.add(message);
        }

        @Override
        public void sendSuccess(Supplier<Component> message, boolean allowLogging) {
            messages.add(message.get());
        }
    }
}
