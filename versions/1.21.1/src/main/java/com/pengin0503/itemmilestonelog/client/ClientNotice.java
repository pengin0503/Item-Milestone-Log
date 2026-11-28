package com.pengin0503.itemmilestonelog.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

/** One-shot action-bar notice with reflective lookup: the player overlay API varies across trees. */
public final class ClientNotice {
    private ClientNotice() {
    }

    public static void overlay(Component text) {
        try {
            Object player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            for (Method method : player.getClass().getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && "displayClientMessage".equals(method.getName())
                        && params[0] == Component.class && params[1] == boolean.class) {
                    method.invoke(player, text, true);
                    return;
                }
                if (params.length == 1 && "sendOverlayMessage".equals(method.getName())
                        && params[0] == Component.class) {
                    method.invoke(player, text);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // A missing notice API must never interrupt gameplay.
        }
    }
}
