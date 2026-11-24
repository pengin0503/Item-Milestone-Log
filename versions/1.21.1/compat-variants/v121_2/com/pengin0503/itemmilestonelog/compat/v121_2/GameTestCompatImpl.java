package com.pengin0503.itemmilestonelog.compat.v121_2;

import com.pengin0503.itemmilestonelog.compat.GameTestCompat;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 1.21 / 1.21.1 {@link GameTestCompat}: the legacy {@code String}-message overload.
 */
public final class GameTestCompatImpl implements GameTestCompat {
    @Override
    public void assertTrue(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    @Override
    public void assertFalse(GameTestHelper helper, boolean condition, String message) {
        helper.assertFalse(condition, message);
    }
}
