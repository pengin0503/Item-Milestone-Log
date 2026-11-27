package com.pengin0503.itemmilestonelog;

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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
