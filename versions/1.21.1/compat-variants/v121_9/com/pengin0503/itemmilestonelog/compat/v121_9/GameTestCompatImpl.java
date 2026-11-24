package com.pengin0503.itemmilestonelog.compat.v121_9;

import com.pengin0503.itemmilestonelog.compat.GameTestCompat;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * 1.21.5+ {@link GameTestCompat}: the {@code String}-message overloads were removed;
 * the message is wrapped in a literal {@link Component}.
 */
public final class GameTestCompatImpl implements GameTestCompat {
    @Override
    public void assertTrue(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, Component.literal(message));
    }

    @Override
    public void assertFalse(GameTestHelper helper, boolean condition, String message) {
        helper.assertFalse(condition, Component.literal(message));
    }
}
