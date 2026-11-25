package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F17: synchronizes each player's milestone record count and world-first count to optional
 * Minecraft scoreboard objectives configured by {@link Config#SCOREBOARD_OBJECTIVE} and
 * {@link Config#SCOREBOARD_WORLD_FIRST_OBJECTIVE}.
 *
 * <p>Objectives are created lazily on first write with the {@code dummy} criterion and
 * {@link ServerLang} fallback display names; pre-existing objectives are reused as-is regardless
 * of criterion, display slots are never changed, and clearing the config never deletes existing
 * objectives or scores.
 */
public final class ScoreboardSync {
    static final String RECORDS_DISPLAY_KEY = "scoreboard.itemmilestonelog.records";
    static final String WORLD_FIRSTS_DISPLAY_KEY = "scoreboard.itemmilestonelog.world_firsts";

    private static volatile String lastWarnedDuplicateObjective = null;

    private ScoreboardSync() {
    }

    /** Clears the duplicate-objective warning dedup state (called on server stop). */
    public static void resetWarningState() {
        lastWarnedDuplicateObjective = null;
    }

    /**
     * Writes the current record and world-first counts for {@code playerId} to the configured
     * scoreboard objectives. No-op when both objectives are disabled, the player has no data, or
     * the player's score-holder name is empty.
     */
    public static void refreshScores(@Nullable MinecraftServer server, @Nullable UUID playerId) {
        if (server == null || playerId == null) {
            return;
        }
        ScoreboardLogic.Objectives objectives = resolveObjectives();
        if (!objectives.anyEnabled()) {
            return;
        }
        refreshScores(server.getScoreboard(), MilestoneData.get(server), playerId, objectives, server);
    }

    /**
     * Writes the current record and world-first counts for every player in {@link MilestoneData}
     * (server start, {@code firsts resetall}, {@code importall}).
     */
    public static void refreshAll(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        ScoreboardLogic.Objectives objectives = resolveObjectives();
        if (!objectives.anyEnabled()) {
            return;
        }
        refreshAll(server.getScoreboard(), MilestoneData.get(server), objectives, server);
    }

    /**
     * Deletes {@code playerId}'s scores from both configured objectives (for {@code admin purge} and
     * F37 {@code prunedata}). Must be called <em>before</em> {@link MilestoneData#purgePlayer(UUID)}
     * while the player's stored name is still present. Never creates absent objectives; if another
     * surviving player shares the same stored name, refreshes that player's scores instead.
     */
    public static void deleteScores(@Nullable MinecraftServer server, @Nullable UUID playerId) {
        if (server == null || playerId == null) {
            return;
        }
        ScoreboardLogic.Objectives objectives = resolveObjectives();
        if (!objectives.anyEnabled()) {
            return;
        }
        deleteScores(server.getScoreboard(), MilestoneData.get(server), playerId, objectives, server);
    }

    /**
     * On login: if the player's name changed since their previous {@code lastKnownName}, deletes
     * the old name's scores from both configured objectives (unless another player still uses that
     * old name, in which case the surviving holder's scores are refreshed). Never creates absent
     * objectives. Call after {@code pd.lastKnownName} has been updated to the current name.
     */
    public static void removeStaleNameScores(
            @Nullable MinecraftServer server,
            @Nullable UUID playerId,
            @Nullable String previousName) {
        if (server == null || playerId == null) {
            return;
        }
        ScoreboardLogic.Objectives objectives = resolveObjectives();
        if (!objectives.anyEnabled()) {
            return;
        }
        removeStaleNameScores(server.getScoreboard(), MilestoneData.get(server), playerId, previousName, objectives, server);
    }

    // --------------------------------------------------------- testable package-private core

    static void refreshScores(
            Scoreboard scoreboard,
            MilestoneData data,
            UUID playerId,
            ScoreboardLogic.Objectives objectives,
            @Nullable MinecraftServer server) {
        if (!objectives.anyEnabled() || playerId == null) {
            return;
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null) {
            return;
        }
        String holderName = ScoreboardLogic.holderName(resolvePlayerName(server, playerId, pd));
        if (holderName == null) {
            return;
        }
        List<ScoreboardLogic.PlayerInput<UUID>> matching = collectPlayersWithName(server, data, holderName, null);
        if (matching.isEmpty()) {
            return;
        }
        Map<UUID, Integer> worldFirstCounts = objectives.worldFirsts() != null ? countWorldFirsts(data) : Map.of();
        List<ScoreboardLogic.PlayerScore<UUID>> scores = ScoreboardLogic.computeScores(matching, worldFirstCounts);
        if (!scores.isEmpty()) {
            writeSingleScore(scoreboard, scores.get(0), objectives);
        }
    }

    static void refreshAll(
            Scoreboard scoreboard,
            MilestoneData data,
            ScoreboardLogic.Objectives objectives,
            @Nullable MinecraftServer server) {
        if (!objectives.anyEnabled()) {
            return;
        }
        Map<UUID, MilestoneData.PlayerData> all = data.allPlayers();
        if (all.isEmpty()) {
            return;
        }
        List<ScoreboardLogic.PlayerInput<UUID>> inputs = new ArrayList<>(all.size());
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : all.entrySet()) {
            UUID uuid = entry.getKey();
            MilestoneData.PlayerData pd = entry.getValue();
            inputs.add(new ScoreboardLogic.PlayerInput<>(
                    uuid, resolvePlayerName(server, uuid, pd), pd.records.size(), pd.lastSeen));
        }
        Map<UUID, Integer> worldFirstCounts = objectives.worldFirsts() != null ? countWorldFirsts(data) : Map.of();
        List<ScoreboardLogic.PlayerScore<UUID>> scores = ScoreboardLogic.computeScores(inputs, worldFirstCounts);
        if (scores.isEmpty()) {
            return;
        }
        Objective recordsObj = objectives.records() != null
                ? getOrCreateObjective(scoreboard, objectives.records(), RECORDS_DISPLAY_KEY)
                : null;
        Objective worldFirstsObj = objectives.worldFirsts() != null
                ? getOrCreateObjective(scoreboard, objectives.worldFirsts(), WORLD_FIRSTS_DISPLAY_KEY)
                : null;
        for (ScoreboardLogic.PlayerScore<UUID> ps : scores) {
            if (recordsObj != null) {
                scoreboard.getOrCreatePlayerScore(ps.holder(), recordsObj).setScore(ps.records());
            }
            if (worldFirstsObj != null) {
                scoreboard.getOrCreatePlayerScore(ps.holder(), worldFirstsObj).setScore(ps.worldFirsts());
            }
        }
    }

    static void deleteScores(
            Scoreboard scoreboard,
            MilestoneData data,
            UUID playerId,
            ScoreboardLogic.Objectives objectives,
            @Nullable MinecraftServer server) {
        if (!objectives.anyEnabled() || playerId == null) {
            return;
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null) {
            return;
        }
        String holderName = ScoreboardLogic.holderName(resolvePlayerName(server, playerId, pd));
        if (holderName == null) {
            return;
        }
        List<ScoreboardLogic.PlayerInput<UUID>> surviving = collectPlayersWithName(server, data, holderName, playerId);
        if (surviving.isEmpty()) {
            resetHolderFromObjectives(scoreboard, holderName, objectives);
            return;
        }
        Map<UUID, Integer> worldFirstCounts = objectives.worldFirsts() != null ? countWorldFirsts(data) : Map.of();
        List<ScoreboardLogic.PlayerScore<UUID>> scores = ScoreboardLogic.computeScores(surviving, worldFirstCounts);
        if (!scores.isEmpty()) {
            writeSingleScore(scoreboard, scores.get(0), objectives);
        }
    }

    static void removeStaleNameScores(
            Scoreboard scoreboard,
            MilestoneData data,
            UUID playerId,
            @Nullable String previousName,
            ScoreboardLogic.Objectives objectives,
            @Nullable MinecraftServer server) {
        if (!objectives.anyEnabled() || playerId == null) {
            return;
        }
        String oldName = ScoreboardLogic.holderName(previousName);
        if (oldName == null) {
            return;
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        String currentName = pd != null ? resolvePlayerName(server, playerId, pd) : null;
        if (Objects.equals(oldName, currentName)) {
            return;
        }
        List<String> otherNames = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            if (!entry.getKey().equals(playerId)) {
                String name = ScoreboardLogic.holderName(resolvePlayerName(server, entry.getKey(), entry.getValue()));
                if (name != null) {
                    otherNames.add(name);
                }
            }
        }
        String stale = ScoreboardLogic.staleNameOnLogin(oldName, currentName, otherNames);
        if (stale != null) {
            resetHolderFromObjectives(scoreboard, stale, objectives);
            return;
        }
        // Another player now uses oldName; refresh their scores in case the renamed player previously shadowed them.
        List<ScoreboardLogic.PlayerInput<UUID>> surviving = collectPlayersWithName(server, data, oldName, playerId);
        if (!surviving.isEmpty()) {
            Map<UUID, Integer> worldFirstCounts = objectives.worldFirsts() != null ? countWorldFirsts(data) : Map.of();
            List<ScoreboardLogic.PlayerScore<UUID>> scores = ScoreboardLogic.computeScores(surviving, worldFirstCounts);
            if (!scores.isEmpty()) {
                writeSingleScore(scoreboard, scores.get(0), objectives);
            }
        }
    }

    static ScoreboardLogic.Objectives resolveObjectives() {
        ScoreboardLogic.Objectives objectives = Config.scoreboardObjectives();
        warnIfDuplicate(objectives);
        return objectives;
    }

    static void warnIfDuplicate(ScoreboardLogic.Objectives objectives) {
        if (objectives.duplicateIgnored()) {
            String name = objectives.records();
            if (!Objects.equals(lastWarnedDuplicateObjective, name)) {
                lastWarnedDuplicateObjective = name;
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] scoreboardObjective and scoreboardWorldFirstObjective are both set to '{}'; ignoring scoreboardWorldFirstObjective",
                        name);
            }
        } else {
            lastWarnedDuplicateObjective = null;
        }
    }

    private static List<ScoreboardLogic.PlayerInput<UUID>> collectPlayersWithName(
            @Nullable MinecraftServer server,
            MilestoneData data,
            String holderName,
            @Nullable UUID excludePlayerId) {
        List<ScoreboardLogic.PlayerInput<UUID>> matching = new ArrayList<>(1);
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            UUID id = entry.getKey();
            if (excludePlayerId != null && id.equals(excludePlayerId)) {
                continue;
            }
            MilestoneData.PlayerData other = entry.getValue();
            String name = ScoreboardLogic.holderName(resolvePlayerName(server, id, other));
            if (holderName.equals(name)) {
                matching.add(new ScoreboardLogic.PlayerInput<>(id, name, other.records.size(), other.lastSeen));
            }
        }
        return matching;
    }

    private static String resolvePlayerName(
            @Nullable MinecraftServer server,
            UUID uuid,
            MilestoneData.PlayerData pd) {
        if (server != null && server.getPlayerList() != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                String name = online.getScoreboardName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }
        return pd.lastKnownName != null ? pd.lastKnownName : "";
    }

    private static Map<UUID, Integer> countWorldFirsts(MilestoneData data) {
        List<UUID> holders = new ArrayList<>(data.worldFirsts().size());
        for (WorldFirstRecord wf : data.worldFirsts().values()) {
            if (wf != null) {
                holders.add(wf.playerUuid());
            }
        }
        return ScoreboardLogic.countWorldFirsts(holders);
    }

    private static void writeSingleScore(
            Scoreboard scoreboard,
            ScoreboardLogic.PlayerScore<UUID> ps,
            ScoreboardLogic.Objectives objectives) {
        if (objectives.records() != null) {
            Objective recordsObj = getOrCreateObjective(scoreboard, objectives.records(), RECORDS_DISPLAY_KEY);
            scoreboard.getOrCreatePlayerScore(ps.holder(), recordsObj).setScore(ps.records());
        }
        if (objectives.worldFirsts() != null) {
            Objective worldFirstsObj = getOrCreateObjective(scoreboard, objectives.worldFirsts(), WORLD_FIRSTS_DISPLAY_KEY);
            scoreboard.getOrCreatePlayerScore(ps.holder(), worldFirstsObj).setScore(ps.worldFirsts());
        }
    }

    private static void resetHolderFromObjectives(
            Scoreboard scoreboard,
            String holderName,
            ScoreboardLogic.Objectives objectives) {
        if (objectives.records() != null) {
            Objective obj = scoreboard.getObjective(objectives.records());
            if (obj != null) {
                scoreboard.resetPlayerScore(holderName, obj);
            }
        }
        if (objectives.worldFirsts() != null) {
            Objective obj = scoreboard.getObjective(objectives.worldFirsts());
            if (obj != null) {
                scoreboard.resetPlayerScore(holderName, obj);
            }
        }
    }

    private static Objective getOrCreateObjective(Scoreboard scoreboard, String name, String displayNameKey) {
        Objective existing = scoreboard.getObjective(name);
        if (existing != null) {
            return existing;
        }
        return scoreboard.addObjective(
                name,
                ObjectiveCriteria.DUMMY,
                ServerLang.tr(displayNameKey),
                ObjectiveCriteria.DUMMY.getDefaultRenderType());
    }
}
