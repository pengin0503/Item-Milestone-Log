package com.pengin0503.itemmilestonelog.client;

import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** PR-3 lesson 1 / PR-6: the two listeners must be registered on concrete event classes. */
class ClientEventConcretenessTest {
    @Test
    void chatAndSoundEventsAreConcreteInThisProfile() {
        assertFalse(Modifier.isAbstract(ClientChatReceivedEvent.class.getModifiers()));
        assertFalse(Modifier.isAbstract(PlaySoundEvent.class.getModifiers()));
    }
}
