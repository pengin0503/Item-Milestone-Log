package com.pengin0503.itemmilestonelog.compat.client;

import com.pengin0503.itemmilestonelog.TagTargets;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * PR-3: version-isolated helpers for the pinned-target HUD (client). Drawing itself uses
 * the tree's {@code GuiGraphicsExtractor}; only these small APIs differ across versions.
 *
 * <p>1.21.6+ renamed {@code GameNarrator.say(Component)} to
 * {@code saySystemQueued(Component)}. The queued method keeps the same non-interrupting
 * system narration semantics as 1.21 - 1.21.5's {@code say} (and matches the 26.x shim).
 */
public final class HudCompat {
    private HudCompat() {
    }

    /** F1 hides the vanilla HUD; the overlay follows the same flag. */
    public static boolean hudDisabled(Minecraft mc) {
        return mc.options.hideGui;
    }

    /** Standard narration through the built-in narrator (no new dependency). */
    public static void narrate(Minecraft mc, Component text) {
        mc.getNarrator().saySystemQueued(text);
    }

    public static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    public static boolean chatOpen(Minecraft mc) {
        return screen(mc) instanceof ChatScreen;
    }

    /** Precomputed display name for one pinned id; never called during a render frame. */
    public static String itemName(String itemId) {
        if (TargetKey.isTagId(itemId)) {
            return TagTargets.displayName(itemId.substring(1)).getString();
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return itemId;
        }
        return BuiltInRegistries.ITEM.getOptional(id)
                .map(item -> Component.translatable(item.getDescriptionId()).getString())
                .orElse(itemId);
    }
}
