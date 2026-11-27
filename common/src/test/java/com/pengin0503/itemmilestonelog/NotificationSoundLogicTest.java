package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-47 / F-150-48: per-event selection, opt-in world-first sound, mute and volume precedence. */
class NotificationSoundLogicTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String LEGACY = "minecraft:ui.toast.challenge_complete";

    private static NotificationSoundLogic.SoundContext context() {
        return new NotificationSoundLogic.SoundContext(
                ACTOR,
                true, false,
                false, false, false, false,
                true, false,
                false, false,
                List.of(), Set.of(), Set.of(), Map.of(),
                LEGACY, "", "", "", 100);
    }

    private static NotificationSoundLogic.SoundContext with(NotificationSoundLogic.SoundContext c,
            java.util.function.Function<NotificationSoundLogic.SoundContext, NotificationSoundLogic.SoundContext> f) {
        return f.apply(c);
    }

    @Test
    void priorityPicksExactlyOneEventWithWorldFirstHighest() {
        assertEquals(NotificationSoundLogic.Event.WORLD_FIRST, NotificationSoundLogic.select(
                NotificationSoundLogic.Event.PROGRESS_ALERT, NotificationSoundLogic.Event.RECORD,
                NotificationSoundLogic.Event.PRESET_COMPLETE, NotificationSoundLogic.Event.WORLD_FIRST));
        assertEquals(NotificationSoundLogic.Event.PRESET_COMPLETE, NotificationSoundLogic.select(
                NotificationSoundLogic.Event.RECORD, NotificationSoundLogic.Event.PRESET_COMPLETE));
        assertEquals(NotificationSoundLogic.Event.RECORD, NotificationSoundLogic.select(
                NotificationSoundLogic.Event.RECORD, NotificationSoundLogic.Event.PROGRESS_ALERT));
        assertEquals(NotificationSoundLogic.Event.PROGRESS_ALERT,
                NotificationSoundLogic.select(NotificationSoundLogic.Event.PROGRESS_ALERT));
        assertNull(NotificationSoundLogic.select());

        var request = NotificationSoundLogic.plan(with(context(), c -> new NotificationSoundLogic.SoundContext(
                ACTOR, c.masterEnabled(), c.actorMuted(), true, true, true, true,
                c.progressAlertsEnabled(), c.actorProgressMuted(), c.announceWorldFirst(),
                c.announceWorldFirstSound(), c.worldFirstChatRecipients(), c.mutedBroadcastRecipients(),
                c.mutedSoundRecipients(), c.personalVolumes(), c.milestoneSoundId(),
                "minecraft:world_first", "minecraft:preset", "minecraft:progress", c.serverVolume())));
        assertEquals(1, request.size(), "one player gets one sound per window");
        assertEquals(NotificationSoundLogic.Event.WORLD_FIRST, request.get(0).event());
        assertEquals("minecraft:world_first", request.get(0).soundId());
        assertEquals(ACTOR, request.get(0).recipient());
    }

    @Test
    void emptyEventIdsReuseMilestoneSoundAndNothingChangesForPlainRecords() {
        var plain = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        true, false, false, false, true, false, false, false,
                        List.of(), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "", 100)));
        assertEquals(1, plain.size());
        assertEquals(NotificationSoundLogic.Event.RECORD, plain.get(0).event());
        assertEquals(LEGACY, plain.get(0).soundId());
        assertEquals(100, plain.get(0).volume());

        var worldFirst = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        true, true, false, false, true, false, false, false,
                        List.of(), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "", 100)));
        assertEquals(LEGACY, worldFirst.get(0).soundId(), "blank worldFirstSound reuses milestoneSound");
        assertEquals(LEGACY, NotificationSoundLogic.resolveSoundId(
                NotificationSoundLogic.Event.PRESET_COMPLETE, LEGACY, "", "", ""));
        assertNull(NotificationSoundLogic.resolveSoundId(null, LEGACY, "", "", ""));
    }

    @Test
    void progressAlertIsSilentByDefaultAndOptInWhenConfigured() {
        var silent = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        false, false, false, true, true, false, false, false,
                        List.of(), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "", 100)));
        assertTrue(silent.isEmpty(), "no new sound appears for progress alerts unless configured");

        var configured = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        false, false, false, true, true, false, false, false,
                        List.of(), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "minecraft:progress", 100)));
        assertEquals(1, configured.size());
        assertEquals(NotificationSoundLogic.Event.PROGRESS_ALERT, configured.get(0).event());
        assertEquals("minecraft:progress", configured.get(0).soundId());

        var mutedProgress = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        false, false, false, true, true, true, false, false,
                        List.of(), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "minecraft:progress", 100)));
        assertTrue(mutedProgress.isEmpty());
        var progressDisabled = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        false, false, false, true, false, false, false, false,
                        List.of(), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "minecraft:progress", 100)));
        assertTrue(progressDisabled.isEmpty());
    }

    @Test
    void masterSwitchAndPersonalMuteStopAllSounds() {
        var masterOff = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, false, false,
                        true, true, true, true, true, false, true, true,
                        List.of(ACTOR, BOB), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "", 100)));
        assertTrue(masterOff.isEmpty());

        var actorMuted = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, true,
                        true, true, false, true, true, false, true, true,
                        List.of(ACTOR, BOB), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "minecraft:progress", 100)));
        assertEquals(1, actorMuted.size(), "only the other world-first recipient hears the announcement");
        assertEquals(BOB, actorMuted.get(0).recipient());
        assertTrue(actorMuted.get(0).announcement());
    }

    @Test
    void worldFirstAnnouncementIsDefaultOffAndOptInMatchesChatRecipients() {
        var defaultOff = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        true, true, false, false, true, false, true, false,
                        List.of(ACTOR, BOB), Set.of(), Set.of(), Map.of(), LEGACY, "", "", "", 100)));
        assertEquals(1, defaultOff.size(), "default keeps only the actor's record/world-first sound");
        assertEquals(ACTOR, defaultOff.get(0).recipient());

        var optedIn = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, false,
                        true, true, false, false, true, false, true, true,
                        List.of(ACTOR, BOB, CAROL), Set.of(CAROL), Set.of(BOB), Map.of(),
                        LEGACY, "minecraft:world_first", "", "", 100)));
        assertEquals(1, optedIn.size(), "actor already served, BOB sound-muted, CAROL broadcast-muted");
        assertEquals(ACTOR, optedIn.get(0).recipient());

        var onlyOthers = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, true,
                        true, true, false, false, true, false, true, true,
                        List.of(ACTOR, BOB, CAROL), Set.of(), Set.of(), Map.of(),
                        LEGACY, "minecraft:world_first", "", "", 100)));
        assertEquals(2, onlyOthers.size());
        assertTrue(onlyOthers.stream().allMatch(NotificationSoundLogic.SoundRequest::announcement));
        assertTrue(onlyOthers.stream().allMatch(r -> "minecraft:world_first".equals(r.soundId())));
    }

    @Test
    void perRecipientVolumeUsesPersonalValueOrServerDefault() {
        var requests = NotificationSoundLogic.plan(with(context(),
                c -> new NotificationSoundLogic.SoundContext(ACTOR, true, true,
                        true, true, false, false, true, false, true, true,
                        List.of(ACTOR, BOB, CAROL), Set.of(), Set.of(),
                        Map.of(BOB, 50, CAROL, 0), LEGACY, "", "", "", 40)));
        assertEquals(2, requests.size());
        NotificationSoundLogic.SoundRequest bob = requests.stream()
                .filter(r -> r.recipient().equals(BOB)).findFirst().orElseThrow();
        NotificationSoundLogic.SoundRequest carol = requests.stream()
                .filter(r -> r.recipient().equals(CAROL)).findFirst().orElseThrow();
        assertEquals(50, bob.volume(), "personal 50 overrides server 40");
        assertEquals(0, carol.volume(), "personal 0 is a volume-0 packet, not silence-by-omission");
    }
}
