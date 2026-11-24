package com.pengin0503.itemmilestonelog.compat.v26_2;

import com.pengin0503.itemmilestonelog.compat.ConfigCompat;
import net.neoforged.fml.config.ModConfig;

public final class ConfigCompatImpl implements ConfigCompat {
    @Override
    public ModConfig.Type type() {
        return ModConfig.Type.COMMON;
    }
}
