package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.ChatStylePolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * F-150-74 (N72): applies a top-level style ONLY to this mod's own chat components.
 *
 * <p>Translation keys are read reflectively so no profile-scoped {@code TranslatableContents}
 * class is linked from the merged jar; nested click/hover events survive because we only copy the
 * component and change its top-level colour/bold style (D-150-210).
 */
public final class ChatStyler {
    private static final int MAX_DEPTH = 4;

    private ChatStyler() {
    }

    public static Component apply(Component message) {
        if (message == null) {
            return null;
        }
        ChatStylePolicy.Mode mode = ChatStylePolicy.Mode.fromConfig(
                MilestoneHudOverlay.effectiveOptions().chatStyleMode());
        if (mode == ChatStylePolicy.Mode.DEFAULT) {
            return message; // byte-identical default behaviour
        }
        List<String> keys = new ArrayList<>();
        collectKeys(message, keys, 0);
        if (!ChatStylePolicy.isStylable(keys)) {
            return message;
        }
        ChatStylePolicy.Style style = ChatStylePolicy.styleFor(mode);
        MutableComponent copy = message.copy();
        if (!style.color().isEmpty()) {
            ChatFormatting color = switch (style.color()) {
                case "GRAY" -> ChatFormatting.GRAY;
                case "GOLD" -> ChatFormatting.GOLD;
                case "WHITE" -> ChatFormatting.WHITE;
                default -> null;
            };
            if (color != null) {
                copy = copy.withStyle(color);
            }
        }
        if (style.bold()) {
            copy = copy.withStyle(ChatFormatting.BOLD);
        }
        return copy;
    }

    private static void collectKeys(Component component, List<String> out, int depth) {
        if (component == null || depth > MAX_DEPTH) {
            return;
        }
        try {
            Object contents = component.getContents();
            Method getKey = contents.getClass().getMethod("getKey");
            Object key = getKey.invoke(contents);
            if (key instanceof String text) {
                out.add(text);
            }
        } catch (ReflectiveOperationException ignored) {
            // Non-translatable contents: no key to inspect.
        }
        for (Component child : component.getSiblings()) {
            collectKeys(child, out, depth + 1);
        }
    }
}
