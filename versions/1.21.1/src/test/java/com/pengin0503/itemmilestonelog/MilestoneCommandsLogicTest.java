package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure decision helpers extracted from the command handlers so bulk-undo, consent-based share,
 * confirm gating, granttag and pin semantics can be tested without a running Minecraft server.
 */
class MilestoneCommandsLogicTest {

    @Test
    void addedKeysNormalizesAndDeduplicatesValidRefs() {
        List<CommandAnalysisModel.TargetRef> refs = List.of(
                new CommandAnalysisModel.TargetRef("diamond", 3),
                new CommandAnalysisModel.TargetRef("minecraft:diamond", 3),
                new CommandAnalysisModel.TargetRef("#c:ingots/iron", 1),
                new CommandAnalysisModel.TargetRef("minecraft:diamond", 3));
        assertEquals(List.of(new TargetKey("minecraft:diamond", 3), new TargetKey("#c:ingots/iron", 1)),
                MilestoneCommands.addedKeys(refs));
    }

    @Test
    void addedKeysSkipsNullInvalidAndOutOfRangeRefs() {
        List<CommandAnalysisModel.TargetRef> refs = new ArrayList<>();
        refs.add(null);
        refs.add(new CommandAnalysisModel.TargetRef("", 1));
        refs.add(new CommandAnalysisModel.TargetRef("bad id", 1));
        refs.add(new CommandAnalysisModel.TargetRef("minecraft:diamond", 0));
        refs.add(new CommandAnalysisModel.TargetRef("minecraft:diamond", TargetKey.MAX_COUNT + 1));
        refs.add(new CommandAnalysisModel.TargetRef("minecraft:iron_ingot", 1));
        assertEquals(List.of(new TargetKey("minecraft:iron_ingot", 1)), MilestoneCommands.addedKeys(refs));
        assertTrue(MilestoneCommands.addedKeys(null).isEmpty());
        assertTrue(MilestoneCommands.addedKeys(List.of()).isEmpty());
    }

    @Test
    void pendingShareCodeIsKeyedByRecipientOnly() {
        UUID recipient = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        MilestoneCommands.putPendingShareCode(recipient, "IML1:shared");
        assertEquals("IML1:shared", MilestoneCommands.pendingShareCode(recipient));
        assertNull(MilestoneCommands.pendingShareCode(unrelated));
        assertTrue(MilestoneCommands.pendingShareCodeMatches(recipient, "  IML1:shared  "));
        MilestoneCommands.removePendingShareCode(recipient);
    }

    @Test
    void pendingShareCodeMatchesRejectsMismatchAndMissingCodes() {
        UUID recipient = UUID.randomUUID();
        assertFalse(MilestoneCommands.pendingShareCodeMatches(recipient, "IML1:any"));
        MilestoneCommands.putPendingShareCode(recipient, "IML1:abc");
        assertFalse(MilestoneCommands.pendingShareCodeMatches(recipient, "IML1:wrong"));
        assertFalse(MilestoneCommands.pendingShareCodeMatches(recipient, null));
        assertFalse(MilestoneCommands.pendingShareCodeMatches(null, "IML1:abc"));
        assertTrue(MilestoneCommands.pendingShareCodeMatches(recipient, "IML1:abc"));
        MilestoneCommands.removePendingShareCode(recipient);
    }

    @Test
    void pendingShareCodeRevocationInvalidatesThePresentedCode() {
        UUID recipient = UUID.randomUUID();
        MilestoneCommands.putPendingShareCode(recipient, "IML1:abc");
        assertTrue(MilestoneCommands.pendingShareCodeMatches(recipient, "IML1:abc"));
        MilestoneCommands.removePendingShareCode(recipient);
        assertNull(MilestoneCommands.pendingShareCode(recipient));
        assertFalse(MilestoneCommands.pendingShareCodeMatches(recipient, "IML1:abc"));
    }

    @Test
    void pendingShareCodeIsConsumedOnlyByAnExactMatch() {
        UUID recipient = UUID.randomUUID();
        MilestoneCommands.putPendingShareCode(recipient, "IML1:abc");
        assertFalse(MilestoneCommands.consumePendingShareCode(recipient, "IML1:wrong"));
        assertTrue(MilestoneCommands.pendingShareCodeMatches(recipient, "IML1:abc"));
        assertTrue(MilestoneCommands.consumePendingShareCode(recipient, "  IML1:abc  "));
        assertFalse(MilestoneCommands.consumePendingShareCode(recipient, "IML1:abc"));
        assertNull(MilestoneCommands.pendingShareCode(recipient));
    }

    @Test
    void presetProtectAppliesOnlyWithConfirmForBothDirections() {
        assertFalse(MilestoneCommands.presetProtectApplies(true, false));
        assertFalse(MilestoneCommands.presetProtectApplies(false, false));
        assertTrue(MilestoneCommands.presetProtectApplies(true, true));
        assertTrue(MilestoneCommands.presetProtectApplies(false, true));
    }

    @Test
    void grantTagRecordKeepsAnExistingRecordUnchanged() {
        MilestoneRecord existing = new MilestoneRecord(7, MilestoneRecord.UNKNOWN_DAY, 100L);
        assertSame(existing, MilestoneCommands.grantTagRecord(existing, 99_999, 200L));
        assertEquals(7, MilestoneCommands.grantTagRecord(existing, 99_999, 200L).playTicks());
    }

    @Test
    void grantTagRecordCreatesARecordWhenAbsent() {
        assertEquals(new MilestoneRecord(42, MilestoneRecord.UNKNOWN_DAY, 1_234L),
                MilestoneCommands.grantTagRecord(null, 42, 1_234L));
    }

    @Test
    void pinLiteralValueIsTrueOnlyForOn() {
        assertTrue(MilestoneCommands.pinLiteralValue("on"));
        assertFalse(MilestoneCommands.pinLiteralValue("off"));
        assertFalse(MilestoneCommands.pinLiteralValue("ON"));
        assertFalse(MilestoneCommands.pinLiteralValue("yes"));
        assertFalse(MilestoneCommands.pinLiteralValue(null));
    }
}
