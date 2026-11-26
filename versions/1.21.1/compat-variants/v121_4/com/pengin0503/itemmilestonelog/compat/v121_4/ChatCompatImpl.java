package com.pengin0503.itemmilestonelog.compat.v121_4;

import com.pengin0503.itemmilestonelog.compat.ChatCompat;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 1.21 - 1.21.4 {@link ChatCompat}: the legacy action + value {@link ClickEvent} /
 * {@link HoverEvent} constructors.
 */
public final class ChatCompatImpl implements ChatCompat {
    @Override
    public ClickEvent runCommand(String command) {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
    }

    @Override
    public ClickEvent suggestCommand(String command) {
        return new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command);
    }

    @Override
    public ClickEvent copyToClipboard(String value) {
        return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
    }

    @Override
    @Nullable
    public String suggestCommandValue(@Nullable ClickEvent event) {
        return event != null && event.getAction() == ClickEvent.Action.SUGGEST_COMMAND ? event.getValue() : null;
    }

    @Override
    public HoverEvent showItem(ItemStack stack) {
        return new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(stack));
    }
}
