package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * F51: pure decision logic behind {@code /milestonelog info <item> [<count>]} and
 * {@code /milestonelog info hand}.
 *
 * <p>Everything here works only on saved {@link MilestoneData.PlayerData} snapshots, the
 * {@link TagIndex} cache and plain possession counts, so it covers offline players and is
 * unit-testable without a server (same approach as {@link MilestoneRankings}).
 *
 * <p>The info output (spec F51) and which function decides each step:
 * <ol>
 *   <li>{@code info_header} with the target display name (F12 hover) — rendering only</li>
 *   <li>own status: {@link #ownStatus} — recorded ({@code info_status_recorded} with the F48
 *       record formatting), unrecorded effective target ({@code info_status_target}, or
 *       {@code info_status_target_progress} with the current possession and the goal for quantity
 *       targets and for possessed tag targets; items carried out of creative count 0 and both
 *       kinds get the F50 {@code target_creative_carried} note), or neither
 *       ({@code info_status_none})</li>
 *   <li>world first: {@code info_world_first} (F47 holder-name rule) or
 *       {@code info_world_first_none} — rendering only</li>
 *   <li>recorder count and own rank: {@link #playersLine} — the F13 {@code top <item>} counting
 *       (hidden players are excluded for non-privileged viewers, the viewer themself is always
 *       included, same tie rules). The whole line is omitted when {@code allowLookup} is false
 *       and the viewer is not privileged — the same gate F6 uses for looking up other players</li>
 *   <li>related tag targets: {@link #relatedTagKeys} + {@link #relatedTagEntries} — tag keys
 *       containing the queried item that appear in the viewer's effective targets, own records,
 *       or the world firsts, at most {@link #TAG_LIMIT} rows plus {@code info_tag_more}</li>
 * </ol>
 */
public final class ItemInfoLogic {
    /** Maximum number of related tag rows before the {@code info_tag_more} line (F51 step 5). */
    public static final int TAG_LIMIT = 10;

    private ItemInfoLogic() {
    }

    /** Which message the viewer's own status line (F51 step 2) shows. */
    public enum OwnStatusKind {
        /** The viewer recorded this key: {@code info_status_recorded} with the record attached. */
        RECORDED,
        /** Unrecorded count-1 target with no possession: {@code info_status_target}. */
        TARGET,
        /** Unrecorded target with known possession: {@code info_status_target_progress} (current / goal). */
        TARGET_PROGRESS,
        /** Neither a record nor a target: {@code info_status_none}. */
        NONE
    }

    /**
     * The viewer's status for one key. Also reused as the short status of a related tag row
     * (F51 step 5).
     *
     * @param kind            which message to render
     * @param record          the viewer's record for {@link OwnStatusKind#RECORDED}
     * @param creativeCarried whether the F50 {@code target_creative_carried} note applies to a
     *                        {@link OwnStatusKind#TARGET_PROGRESS} line (the item, or any member
     *                        of the tag, is carried out of creative mode)
     * @param current         current simultaneous possession ({@code 0} for a creative-carried item)
     * @param goal             the target quantity
     */
    public record OwnStatus(OwnStatusKind kind, @Nullable MilestoneRecord record,
                            boolean creativeCarried, int current, int goal) {
    }

    /** One {@code info_tag_entry} row: a tag key with the viewer's short status on it. */
    public record TagEntry(TargetKey key, OwnStatus status) {
    }

    /**
     * F51 step 5. A tag key that is neither the viewer's target nor their record appears only
     * through the world firsts (spec: 世界初のみ); such a row shows the viewer's own status,
     * which is {@code info_status_none}.
     *
     * @param entries at most {@link #TAG_LIMIT} rows in the order of {@link #relatedTagKeys}
     * @param more    number of rows cut by the cap ({@code info_tag_more}; 0 when nothing was cut)
     */
    public record RelatedTags(List<TagEntry> entries, int more) {
        public RelatedTags {
            Objects.requireNonNull(entries, "entries");
        }

        /** Whether the {@code info_tag_header} line (and any rows) should be sent at all. */
        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    /**
     * F51 step 4: how many players recorded the key and the viewer's own rank.
     *
     * @param shown   whether the line is rendered at all
     * @param players number of players who recorded the key and are visible to the viewer
     * @param ownRank the viewer's competition rank, or {@code null} when they have no record
     *                ({@code info_players_unranked})
     */
    public record PlayersLine(boolean shown, int players, @Nullable Integer ownRank) {
        static final PlayersLine OMITTED = new PlayersLine(false, 0, null);
    }

    /**
     * F51 step 2: the viewer's status for {@code key}.
     *
     * @param records             the viewer's records
     * @param effectiveTargets    the viewer's effective targets (personal + {@code defaultTargets})
     * @param carriedFromCreative the viewer's items carried out of creative mode
     * @param tagIndex            the current tag membership snapshot
     * @param possessionCounts    simultaneous possession by target ID (item or {@code '#'}-prefixed
     *                            tag ID). Tag totals exclude creative-carried members, exactly as
     *                            {@code Tracker.countItemsInPossession} returns them.
     */
    public static OwnStatus ownStatus(
            TargetKey key,
            Map<TargetKey, MilestoneRecord> records,
            Set<TargetKey> effectiveTargets,
            Set<String> carriedFromCreative,
            TagIndex tagIndex,
            Map<String, Integer> possessionCounts) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(effectiveTargets, "effectiveTargets");
        Objects.requireNonNull(carriedFromCreative, "carriedFromCreative");
        Objects.requireNonNull(tagIndex, "tagIndex");
        Objects.requireNonNull(possessionCounts, "possessionCounts");
        MilestoneRecord record = records.get(key);
        if (record != null) {
            return new OwnStatus(OwnStatusKind.RECORDED, record, false, 0, 0);
        }
        if (!effectiveTargets.contains(key)) {
            return new OwnStatus(OwnStatusKind.NONE, null, false, 0, 0);
        }
        // F50: an item carried out of creative counts 0 (with the note); a tag total already
        // excludes carried members (F8b) and only gets the note when a member is carried.
        boolean carried = key.isTag()
                ? tagIndex.anyMemberIn(key.itemId(), carriedFromCreative)
                : carriedFromCreative.contains(key.itemId());
        int current = (key.isTag() || !carried) ? possessionCounts.getOrDefault(key.itemId(), 0) : 0;
        // Count-1 item targets have no progress line; for tag targets the aggregate possession is
        // requested (F8b), so a possessed tag still shows current / goal. Quantity targets always do.
        if (key.count() == TargetKey.MIN_COUNT && current <= 0) {
            return new OwnStatus(OwnStatusKind.TARGET, null, false, 0, key.count());
        }
        return new OwnStatus(OwnStatusKind.TARGET_PROGRESS, null, carried, current, key.count());
    }

    /**
     * Target IDs whose simultaneous possession the info output needs: the unrecorded targets
     * among {@code keys} (the queried key and the related tag keys). Tag IDs are always requested
     * (their totals exclude creative-carried members, F8b, and a tag target can show progress even
     * at count 1); an item ID with count 1 or carried out of creative is skipped (it counts 0 or
     * has no progress line, F50). Mirrors the progress counting of {@code targets}.
     */
    public static Set<String> possessionIds(
            Collection<TargetKey> keys,
            Map<TargetKey, MilestoneRecord> records,
            Set<String> carriedFromCreative) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(carriedFromCreative, "carriedFromCreative");
        Set<String> ids = new LinkedHashSet<>();
        for (TargetKey key : keys) {
            if (!records.containsKey(key)
                    && (key.isTag()
                        || (key.count() > TargetKey.MIN_COUNT
                            && !carriedFromCreative.contains(key.itemId())))) {
                ids.add(key.itemId());
            }
        }
        return ids;
    }

    /**
     * F51 step 5 selection: tag keys that contain {@code itemId} and appear in the viewer's
     * effective targets, own records, or the world firsts, ordered by tag ID and then count.
     * Empty when the item is not in the registry (or nothing matches), in which case the step's
     * header and rows are not shown at all.
     *
     * @param itemExistsInRegistry whether {@code itemId} names a registered item (removed-mod
     *                             IDs get no tag section)
     * @param worldFirstKeys       the world's world-first keys (any holder)
     */
    public static List<TargetKey> relatedTagKeys(
            String itemId,
            boolean itemExistsInRegistry,
            Set<TargetKey> effectiveTargets,
            Map<TargetKey, MilestoneRecord> records,
            Set<TargetKey> worldFirstKeys,
            TagIndex tagIndex) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(effectiveTargets, "effectiveTargets");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(worldFirstKeys, "worldFirstKeys");
        Objects.requireNonNull(tagIndex, "tagIndex");
        if (!itemExistsInRegistry) {
            return List.of();
        }
        List<TargetKey> keys = new ArrayList<>();
        // tagsContaining is sorted, and the TreeSet keeps each tag's keys in TargetKey order.
        for (String tagId : tagIndex.tagsContaining(itemId)) {
            Set<TargetKey> perTag = new TreeSet<>();
            collectKeysOfTag(tagId, effectiveTargets, perTag);
            collectKeysOfTag(tagId, records.keySet(), perTag);
            collectKeysOfTag(tagId, worldFirstKeys, perTag);
            keys.addAll(perTag);
        }
        return List.copyOf(keys);
    }

    /** Adds the keys of {@code source} whose item ID is exactly {@code tagId} to {@code out}. */
    private static void collectKeysOfTag(String tagId, Set<TargetKey> source, Set<TargetKey> out) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (TargetKey key : source) {
            if (key.itemId().equals(tagId)) {
                out.add(key);
            }
        }
    }

    /**
     * F51 step 5 rendering data: the first {@link #TAG_LIMIT} keys of {@code relatedKeys} with
     * the viewer's short status on each, plus how many rows the cap cut.
     */
    public static RelatedTags relatedTagEntries(
            List<TargetKey> relatedKeys,
            Map<TargetKey, MilestoneRecord> records,
            Set<TargetKey> effectiveTargets,
            Set<String> carriedFromCreative,
            TagIndex tagIndex,
            Map<String, Integer> possessionCounts) {
        Objects.requireNonNull(relatedKeys, "relatedKeys");
        List<TagEntry> entries = new ArrayList<>();
        int more = 0;
        for (TargetKey key : relatedKeys) {
            if (entries.size() >= TAG_LIMIT) {
                more++;
                continue;
            }
            entries.add(new TagEntry(key,
                    ownStatus(key, records, effectiveTargets, carriedFromCreative, tagIndex, possessionCounts)));
        }
        return new RelatedTags(List.copyOf(entries), more);
    }

    /**
     * F51 step 4: the recorder count and the viewer's own rank for {@code key}, counted exactly
     * like F13 {@code top <item>} via {@link MilestoneRankings#topByItem} (hidden players are
     * excluded for non-privileged viewers, the viewer themself is always included, competition
     * ranking with the same tie rules).
     *
     * <p>The whole line is omitted when {@code allowLookup} is false and the viewer is not
     * privileged — the same gate F6 uses for looking up other players. The {@code privileged}
     * flag comes from {@code MilestoneService.isPrivileged} in the caller (F43 widens that
     * check later).
     */
    public static PlayersLine playersLine(
            Map<UUID, MilestoneData.PlayerData> players,
            TargetKey key,
            MilestoneRankings.Viewer viewer,
            boolean allowLookup) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(viewer, "viewer");
        if (!LookupPolicy.canLookUpOthers(allowLookup, viewer.privileged())) {
            return PlayersLine.OMITTED;
        }
        MilestoneRankings.Ranking<MilestoneRankings.ItemEntry> ranking =
                MilestoneRankings.topByItem(players, key, viewer);
        MilestoneRankings.ItemEntry self = ranking.self();
        return new PlayersLine(true, ranking.entries().size(), self != null ? self.rank() : null);
    }

    /**
     * Item IDs suggested for {@code info <item>}: the viewer's effective targets and own
     * records, plus items recorded by players visible to the viewer when the F6 lookup gate
     * allows other players at all ({@code allowLookup}, or privileged). With the gate closed
     * only the viewer's own targets and records are suggested. Tag IDs never appear
     * ({@code <item>} is an item ID argument; tag targets are typed into the tag commands).
     */
    public static Set<String> suggestionItemIds(
            Map<UUID, MilestoneData.PlayerData> players,
            MilestoneRankings.Viewer viewer,
            boolean allowLookup,
            Set<TargetKey> ownEffectiveTargets,
            Map<TargetKey, MilestoneRecord> ownRecords) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(viewer, "viewer");
        Set<String> ids = new LinkedHashSet<>();
        addNonTagIds(ownEffectiveTargets, ids);
        if (ownRecords != null) {
            addNonTagIds(ownRecords.keySet(), ids);
        }
        if (LookupPolicy.canLookUpOthers(allowLookup, viewer.privileged())) {
            for (String id : MilestoneRankings.recordedItemIds(players, viewer)) {
                if (!TargetKey.isTagId(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** Adds the non-tag item IDs of {@code keys} to {@code out}. */
    private static void addNonTagIds(Set<TargetKey> keys, Set<String> out) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (TargetKey key : keys) {
            if (!key.isTag()) {
                out.add(key.itemId());
            }
        }
    }
}
