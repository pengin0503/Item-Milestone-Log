package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-74 (N72): four styles, mod-key targeting, and byte-identical default behaviour. */
class ChatStylePolicyTest {
    @Test
    void defaultModeChangesNothing() {
        assertEquals(ChatStylePolicy.Mode.DEFAULT, ChatStylePolicy.Mode.fromConfig("default"));
        assertEquals("", ChatStylePolicy.styleFor(ChatStylePolicy.Mode.DEFAULT).color());
        assertFalse(ChatStylePolicy.styleFor(ChatStylePolicy.Mode.DEFAULT).bold());
        assertEquals("default", ChatStylePolicy.configName(ChatStylePolicy.Mode.DEFAULT));
    }

    @Test
    void allFourModesUseOnlyExistingFormattingNames() {
        assertEquals(new ChatStylePolicy.Style(false, "GRAY"),
                ChatStylePolicy.styleFor(ChatStylePolicy.Mode.SUBTLE));
        assertEquals(new ChatStylePolicy.Style(true, "GOLD"),
                ChatStylePolicy.styleFor(ChatStylePolicy.Mode.BOLD));
        assertEquals(new ChatStylePolicy.Style(true, "WHITE"),
                ChatStylePolicy.styleFor(ChatStylePolicy.Mode.HIGH_CONTRAST));
        for (ChatStylePolicy.Mode mode : ChatStylePolicy.Mode.values()) {
            String color = ChatStylePolicy.styleFor(mode).color();
            assertTrue(color.isEmpty() || List.of("GRAY", "GOLD", "WHITE").contains(color),
                    "no custom hex colour: " + color);
        }
    }

    @Test
    void onlyOwnTranslationKeysAreStylable() {
        assertTrue(ChatStylePolicy.isStylable(List.of("message.itemmilestonelog.recorded")));
        assertTrue(ChatStylePolicy.isStylable(List.of("gui.itemmilestonelog.status.recorded")));
        assertTrue(ChatStylePolicy.isStylable(List.of("other.mod.key", "message.itemmilestonelog.recorded")));
        assertFalse(ChatStylePolicy.isStylable(List.of("other.mod.key", "chat.type.text")));
        assertFalse(ChatStylePolicy.isStylable(List.of()));
        assertFalse(ChatStylePolicy.isStylable(null));
        assertFalse(ChatStylePolicy.isOwnKey("message.other_mod.recorded"));
    }

    @Test
    void playerTextLinesAreNeverRestyled() {
        assertFalse(ChatStylePolicy.isStylable(List.of("message.itemmilestonelog.pinned_entry")));
        assertFalse(ChatStylePolicy.isStylable(
                List.of("command.itemmilestonelog.imlclient.pin.list", "message.itemmilestonelog.recorded")));
    }

    @Test
    void unknownConfigValuesFallBackToDefault() {
        assertEquals(ChatStylePolicy.Mode.DEFAULT, ChatStylePolicy.Mode.fromConfig(null));
        assertEquals(ChatStylePolicy.Mode.DEFAULT, ChatStylePolicy.Mode.fromConfig("rainbow"));
        assertEquals(ChatStylePolicy.Mode.HIGH_CONTRAST, ChatStylePolicy.Mode.fromConfig("high-contrast"));
        assertEquals(ChatStylePolicy.Mode.HIGH_CONTRAST, ChatStylePolicy.Mode.fromConfig("HIGH_CONTRAST"));
        assertEquals("high-contrast", ChatStylePolicy.configName(ChatStylePolicy.Mode.HIGH_CONTRAST));
        assertTrue(ChatStylePolicy.isValidConfigValue("subtle"));
        assertTrue(ChatStylePolicy.isValidConfigValue("high-contrast"));
        assertTrue(ChatStylePolicy.isValidConfigValue("default"));
        assertFalse(ChatStylePolicy.isValidConfigValue("rainbow"));
    }
}
