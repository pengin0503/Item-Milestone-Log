package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-43 / F-150-44: the single destination precedence table (spec 1.2.1). */
class NotificationDestinationLogicTest {
    private static final NotificationDestinationLogic.Channel[] CHANNELS =
            NotificationDestinationLogic.Channel.values();
    private static final NotificationDestinationLogic.Choice[] CHOICES =
            NotificationDestinationLogic.Choice.values();

    private final List<String> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            warnings.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @AfterEach
    void detachWarningCapture() {
        Logger.getLogger(NotificationDestinationLogic.class.getName()).removeHandler(capture);
        NotificationDestinationLogic.resetWarningStateForTests();
    }

    private void captureWarnings() {
        NotificationDestinationLogic.resetWarningStateForTests();
        warnings.clear();
        Logger.getLogger(NotificationDestinationLogic.class.getName()).addHandler(capture);
    }

    @Test
    void parseChannelAcceptsTheFourLowercaseConfigValuesAndRejectsServer() {
        assertEquals(NotificationDestinationLogic.Channel.CHAT, NotificationDestinationLogic.parseChannel("chat"));
        assertEquals(NotificationDestinationLogic.Channel.ACTION_BAR,
                NotificationDestinationLogic.parseChannel("actionbar"));
        assertEquals(NotificationDestinationLogic.Channel.BOTH, NotificationDestinationLogic.parseChannel("both"));
        assertEquals(NotificationDestinationLogic.Channel.OFF, NotificationDestinationLogic.parseChannel("off"));
        assertEquals(NotificationDestinationLogic.Channel.CHAT, NotificationDestinationLogic.parseChannel("CHAT"));
        captureWarnings();
        assertEquals(NotificationDestinationLogic.Channel.CHAT, NotificationDestinationLogic.parseChannel("server"));
        assertEquals(NotificationDestinationLogic.Channel.CHAT, NotificationDestinationLogic.parseChannel(null));
        assertEquals(NotificationDestinationLogic.Channel.CHAT, NotificationDestinationLogic.parseChannel("nope"));
        assertEquals(1, warnings.size(), "an invalid config value must warn at most once");
        assertFalse(NotificationDestinationLogic.isValidChannel("server"));
        assertTrue(NotificationDestinationLogic.isValidChannel("actionbar"));
    }

    @Test
    void parseChoiceAcceptsEnumNamesAndTreatsMissingAsFollowServer() {
        for (NotificationDestinationLogic.Choice choice : CHOICES) {
            assertEquals(choice, NotificationDestinationLogic.parseChoice(choice.name()));
            assertTrue(NotificationDestinationLogic.isValidChoice(choice.name()));
        }
        assertEquals(NotificationDestinationLogic.Choice.FOLLOW_SERVER,
                NotificationDestinationLogic.parseChoice(null));
        assertEquals(NotificationDestinationLogic.Choice.FOLLOW_SERVER,
                NotificationDestinationLogic.parseChoice(""));
        captureWarnings();
        assertEquals(NotificationDestinationLogic.Choice.FOLLOW_SERVER,
                NotificationDestinationLogic.parseChoice("not_a_choice"));
        assertEquals(1, warnings.size(), "an invalid stored value must warn at most once");
        assertFalse(NotificationDestinationLogic.isValidChoice("not_a_choice"));
    }

    @Test
    void resolveIsTheCompleteFourByFivePrecedenceMatrix() {
        for (NotificationDestinationLogic.Channel server : CHANNELS) {
            assertEquals(server, NotificationDestinationLogic.resolve(server, null),
                    "missing personal data follows the server default");
            assertEquals(server, NotificationDestinationLogic.resolve(server,
                    NotificationDestinationLogic.Choice.FOLLOW_SERVER));
            for (NotificationDestinationLogic.Choice choice : CHOICES) {
                NotificationDestinationLogic.Channel expected = choice == NotificationDestinationLogic.Choice.FOLLOW_SERVER
                        ? server
                        : NotificationDestinationLogic.Channel.valueOf(choice.name());
                assertEquals(expected, NotificationDestinationLogic.resolve(server, choice),
                        "server=" + server + " choice=" + choice);
            }
        }
        assertEquals(NotificationDestinationLogic.Channel.CHAT,
                NotificationDestinationLogic.resolve(null, NotificationDestinationLogic.Choice.FOLLOW_SERVER));
    }

    @Test
    void sendsChatAndActionBarAreExactlyTheChannelMembershipRules() {
        for (NotificationDestinationLogic.Channel channel : CHANNELS) {
            boolean expectedChat = channel == NotificationDestinationLogic.Channel.CHAT
                    || channel == NotificationDestinationLogic.Channel.BOTH;
            boolean expectedBar = channel == NotificationDestinationLogic.Channel.ACTION_BAR
                    || channel == NotificationDestinationLogic.Channel.BOTH;
            assertEquals(expectedChat, NotificationDestinationLogic.sendsChat(channel), channel.name());
            assertEquals(expectedBar, NotificationDestinationLogic.sendsActionBar(channel), channel.name());
        }
        assertFalse(NotificationDestinationLogic.sendsChat(NotificationDestinationLogic.Channel.OFF));
        assertFalse(NotificationDestinationLogic.sendsActionBar(NotificationDestinationLogic.Channel.OFF));
    }

    @Test
    void labelsAreTheLiteralCommandWords() {
        assertEquals("server", NotificationDestinationLogic.label(NotificationDestinationLogic.Choice.FOLLOW_SERVER));
        assertEquals("actionbar", NotificationDestinationLogic.label(NotificationDestinationLogic.Choice.ACTION_BAR));
        assertEquals("actionbar", NotificationDestinationLogic.label(NotificationDestinationLogic.Channel.ACTION_BAR));
        assertEquals("chat", NotificationDestinationLogic.label((NotificationDestinationLogic.Channel) null));
    }
}
