package com.pengin0503.itemmilestonelog.compat;

import net.neoforged.fml.config.ModConfig;

/**
 * N251: mod config type selection. 26.3 registers this mod's config as
 * {@link ModConfig.Type#LOCAL} instead of {@code COMMON}; per-version
 * implementations live in the {@code compat.v26_*} packages.
 */
public interface ConfigCompat {
    ModConfig.Type type();
}
