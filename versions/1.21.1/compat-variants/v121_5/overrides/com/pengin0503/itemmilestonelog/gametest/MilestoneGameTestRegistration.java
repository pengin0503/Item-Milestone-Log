package com.pengin0503.itemmilestonelog.gametest;

import com.mojang.serialization.MapCodec;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.function.Consumer;

/**
 * NeoForge 1.21.5+ GameTest registration wrapper ({@link RegisterGameTestsEvent#registerTest} with
 * {@link GameTestInstance}). Delegates all test bodies to {@link MilestoneGameTests}.
 */
public final class MilestoneGameTestRegistration {
    private static final ResourceLocation EMPTY_STRUCTURE = ResourceLocation.withDefaultNamespace("empty");
    private static final int DEFAULT_MAX_TICKS = 100;

    private MilestoneGameTestRegistration() {
    }

    public static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition> env = event.registerEnvironment(
                ResourceLocation.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "default"));
        TestData<Holder<TestEnvironmentDefinition>> testData = new TestData<>(
                env,
                EMPTY_STRUCTURE,
                DEFAULT_MAX_TICKS,
                0,
                true);

        registerTest(event, "scan_records_target_in_inventory", testData,
                MilestoneGameTests::scanRecordsTargetInInventory);
        registerTest(event, "creative_carry_is_not_recorded_by_scan", testData,
                MilestoneGameTests::creativeCarryIsNotRecordedByScan);
    }

    private static void registerTest(
            RegisterGameTestsEvent event,
            String name,
            TestData<Holder<TestEnvironmentDefinition>> testData,
            Consumer<GameTestHelper> body) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, name);
        event.registerTest(id, new InlineGameTestInstance(testData, body));
    }

    private static final class InlineGameTestInstance extends GameTestInstance {
        private final Consumer<GameTestHelper> body;
        private final MapCodec<InlineGameTestInstance> codec;

        InlineGameTestInstance(
                TestData<Holder<TestEnvironmentDefinition>> info,
                Consumer<GameTestHelper> body) {
            super(info);
            this.body = body;
            this.codec = MapCodec.unit(this);
        }

        @Override
        public void run(GameTestHelper helper) {
            body.accept(helper);
        }

        @Override
        public MapCodec<? extends GameTestInstance> codec() {
            return codec;
        }

        @Override
        protected MutableComponent typeDescription() {
            return Component.literal(ItemMilestoneLog.MOD_ID);
        }
    }
}
