package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * N252: GameTest assertions. 1.21.5+ changed {@code GameTestHelper#assertTrue} to take a
 * {@code Component} message instead of a {@code String}, so assertions go through this interface.
 */
public interface GameTestCompat {
    void assertTrue(GameTestHelper helper, boolean condition, String message);
}
