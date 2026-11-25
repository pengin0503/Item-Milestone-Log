package com.pengin0503.itemmilestonelog.compat.v120_1;

import com.google.gson.JsonObject;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.MilestoneTriggerLogic;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;

import java.util.Optional;

/**
 * F2: custom advancement criterion trigger ({@code itemmilestonelog:milestone_recorded}).
 *
 * <p>1.20.1 style: {@link SimpleCriterionTrigger} with a JSON-based
 * {@link #createInstance(JsonObject, ContextAwarePredicate, DeserializationContext)} instead of
 * the 1.21 Codec. All conditions are optional:
 * <ul>
 *   <li>{@code player}: vanilla player predicate ({@link ContextAwarePredicate})</li>
 *   <li>{@code item}: exact string match against {@link TargetKey#itemId()} (tag targets pass their
 *       {@code "#..."} ID as-is; no tag-membership matching)</li>
 *   <li>{@code count}: vanilla {@link MinMaxBounds.Ints} against {@link TargetKey#count()}</li>
 *   <li>{@code world_first}: whether the recording is a world first (never matches {@code true}
 *       when world-first tracking is disabled)</li>
 * </ul>
 */
public class MilestoneRecordedTrigger extends SimpleCriterionTrigger<MilestoneRecordedTrigger.TriggerInstance> {
    private static final ResourceLocation ID =
            new ResourceLocation(ItemMilestoneLog.MOD_ID, "milestone_recorded");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(
            JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        Optional<String> item = json.has("item")
                ? Optional.of(GsonHelper.getAsString(json, "item"))
                : Optional.empty();
        MinMaxBounds.Ints count = json.has("count")
                ? MinMaxBounds.Ints.fromJson(json.get("count"))
                : MinMaxBounds.Ints.ANY;
        Optional<Boolean> worldFirst = json.has("world_first")
                ? Optional.of(GsonHelper.getAsBoolean(json, "world_first"))
                : Optional.empty();
        return new TriggerInstance(player, item, count, worldFirst);
    }

    public void trigger(ServerPlayer player, TargetKey key, boolean worldFirst, boolean worldFirstEnabled) {
        this.trigger(player, instance -> instance.matches(key, worldFirst, worldFirstEnabled));
    }

    /** 1.20.1 trigger instance: an {@link AbstractCriterionTriggerInstance} plus the F2 conditions. */
    public static class TriggerInstance extends AbstractCriterionTriggerInstance {
        private final Optional<String> item;
        private final MinMaxBounds.Ints count;
        private final Optional<Boolean> worldFirst;

        public TriggerInstance(
                ContextAwarePredicate player,
                Optional<String> item,
                MinMaxBounds.Ints count,
                Optional<Boolean> worldFirst) {
            super(ID, player);
            this.item = item;
            this.count = count;
            this.worldFirst = worldFirst;
        }

        public Optional<String> item() {
            return item;
        }

        public MinMaxBounds.Ints count() {
            return count;
        }

        public Optional<Boolean> worldFirst() {
            return worldFirst;
        }

        public boolean matches(TargetKey key, boolean actualWorldFirst, boolean worldFirstEnabled) {
            return MilestoneTriggerLogic.matchesOptional(
                    this.item,
                    Optional.ofNullable(this.count.getMin()),
                    Optional.ofNullable(this.count.getMax()),
                    this.worldFirst,
                    key.itemId(),
                    key.count(),
                    actualWorldFirst,
                    worldFirstEnabled);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            this.item.ifPresent(value -> json.addProperty("item", value));
            if (!this.count.isAny()) {
                json.add("count", this.count.serializeToJson());
            }
            this.worldFirst.ifPresent(value -> json.addProperty("world_first", value));
            return json;
        }
    }
}
