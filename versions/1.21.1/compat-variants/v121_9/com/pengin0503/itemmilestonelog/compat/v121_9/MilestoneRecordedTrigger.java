package com.pengin0503.itemmilestonelog.compat.v121_9;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.MilestoneTriggerLogic;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * F2: custom advancement criterion trigger ({@code itemmilestonelog:milestone_recorded}).
 *
 * <p>All conditions are optional:
 * <ul>
 *   <li>{@code player}: vanilla player predicate ({@link ContextAwarePredicate})</li>
 *   <li>{@code item}: exact string match against {@link TargetKey#itemId()} (tag targets pass their
 *       {@code "#..."} ID as-is; no tag-membership matching)</li>
 *   <li>{@code count}: vanilla {@link MinMaxBounds.Ints} against {@link TargetKey#count()}</li>
 *   <li>{@code world_first}: whether the recording is a world first (never matches {@code true}
 *       when {@link Config#WORLD_FIRST_ENABLED} is {@code false})</li>
 * </ul>
 */
public class MilestoneRecordedTrigger extends SimpleCriterionTrigger<MilestoneRecordedTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, TargetKey key, boolean worldFirst, boolean worldFirstEnabled) {
        this.trigger(player, instance -> instance.matches(key, worldFirst, worldFirstEnabled));
    }

    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<String> item,
            MinMaxBounds.Ints count,
            Optional<Boolean> worldFirst)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                        Codec.STRING.optionalFieldOf("item").forGetter(TriggerInstance::item),
                        MinMaxBounds.Ints.CODEC.optionalFieldOf("count", MinMaxBounds.Ints.ANY).forGetter(TriggerInstance::count),
                        Codec.BOOL.optionalFieldOf("world_first").forGetter(TriggerInstance::worldFirst))
                .apply(instance, TriggerInstance::new));

        public boolean matches(TargetKey key, boolean actualWorldFirst, boolean worldFirstEnabled) {
            return MilestoneTriggerLogic.matchesOptional(
                    this.item,
                    this.count.min(),
                    this.count.max(),
                    this.worldFirst,
                    key.itemId(),
                    key.count(),
                    actualWorldFirst,
                    worldFirstEnabled);
        }
    }
}
