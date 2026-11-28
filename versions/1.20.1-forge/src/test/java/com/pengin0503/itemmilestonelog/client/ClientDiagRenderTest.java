package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.ClientDiagRender;
import com.pengin0503.itemmilestonelog.CommandHelp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-87 (N200): 20-line cap, six spec keys, no secrets, and "no" for missing channels. */
class ClientDiagRenderTest {
    private static ClientDiagRender.Inputs inputs(boolean channels, String loader, String mc) {
        return new ClientDiagRender.Inputs(loader, mc,
                channels, channels, channels, channels, channels,
                new boolean[]{true, false, true, false},
                true, 1_700_000_000_000L, 2, 3, 4, 512L,
                1, 2, 3, 4, 5, 6);
    }

    private static String allArgs(List<ClientDiagRender.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (ClientDiagRender.Line line : lines) {
            sb.append(line.key()).append(' ').append(String.join(" ", line.args())).append('\n');
        }
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void emitsSixSafeLinesUsingOnlyTheSpecHeadingKeys() {
        List<ClientDiagRender.Line> lines = ClientDiagRender.render(inputs(true, "neoforge", "V121_1"));
        assertEquals(6, lines.size());
        assertTrue(lines.size() <= ClientDiagRender.MAX_LINES);
        assertEquals(List.of(ClientDiagRender.LINE_KEYS),
                lines.stream().map(ClientDiagRender.Line::key).toList());
        for (ClientDiagRender.Line line : lines) {
            assertTrue(line.key().startsWith("command.itemmilestonelog.imlclient.diag."), line.key());
            assertTrue(line.args().length >= 1 && line.args().length <= 2);
        }
    }

    @Test
    void hostileValuesCannotLeakUrlsTokensOrHomePaths() {
        String rendered = allArgs(ClientDiagRender.render(inputs(true,
                "https://webhook.example/api/token/secret", "/home/hatch/.config/token=abc")));
        for (String secret : List.of("http", "webhook", "token", "/home", "env:", "secret", ".config")) {
            assertFalse(rendered.contains(secret), "leaked " + secret + ": " + rendered);
        }
        assertTrue(rendered.contains("unknown unknown"), rendered);
    }

    @Test
    void absentChannelsReportNoWithoutErrors() {
        String rendered = allArgs(ClientDiagRender.render(inputs(false, "forge", "V120_1")));
        assertTrue(rendered.contains("requestsync=no action=no sync=no extra=no effect=no"), rendered);
        assertTrue(rendered.contains("copy=no"), rendered);
    }

    @Test
    void clientDiagnosticsNeverEnterTheServerHelpTable() {
        assertFalse(CommandHelp.ENTRIES.stream().anyMatch(e ->
                "imlclient".equals(e.name()) || "diag".equals(e.name())),
                "server /milestonelog help must not gain client commands");
        assertEquals("/milestonelog", CommandHelp.ROOT_COMMAND);
    }
}
