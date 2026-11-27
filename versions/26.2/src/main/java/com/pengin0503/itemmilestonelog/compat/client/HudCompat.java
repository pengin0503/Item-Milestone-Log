package com.pengin0503.itemmilestonelog.compat.client;

import com.pengin0503.itemmilestonelog.TagTargets;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * PR-3: version-isolated helpers for the pinned-target HUD (client). Drawing itself uses
 * the tree's {@code GuiGraphicsExtractor}; only these small APIs differ across versions.
 */
public final class HudCompat {
    private HudCompat() {
    }

    /** 26.x no longer exposes {@code Options.hideGui}; F1 suppression is left to the vanilla HUD. */
    public static boolean hudDisabled(Minecraft mc) {
        return false;
    }

    /** Standard narration, queued so rendering never blocks on the narrator. */
    public static void narrate(Minecraft mc, Component text) {
        mc.getNarrator().saySystemQueued(text);
    }

    public static Screen screen(Minecraft mc) {
        return ClientCompat.screens().currentScreen(mc);
    }

    public static boolean chatOpen(Minecraft mc) {
        return screen(mc) instanceof ChatScreen;
    }

    /** Precomputed display name for one pinned id; never called during a render frame. */
    public static String itemName(String itemId) {
        if (TargetKey.isTagId(itemId)) {
            return TagTargets.displayName(itemId.substring(1)).getString();
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return itemId;
        }
        return BuiltInRegistries.ITEM.getOptional(id)
                .map(item -> Component.translatable(item.getDescriptionId()).getString())
                .orElse(itemId);
    }
}
