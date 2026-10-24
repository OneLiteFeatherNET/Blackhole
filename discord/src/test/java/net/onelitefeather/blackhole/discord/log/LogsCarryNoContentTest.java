package net.onelitefeather.blackhole.discord.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.discord.backend.Tickets.AuthorKind;
import net.onelitefeather.blackhole.discord.command.Lang;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-validating check of the rule "no message content, Ban IDs or Discord ids in logs": the whole flow runs, including the
 * failure paths, with a captured appender, and every line is searched for the secrets the test planted.
 */
class LogsCarryNoContentTest {

    private static final String SECRET_TEXT = "SECRET-MESSAGE-CONTENT-4711";
    private static final String BAN_ID = "K7M2-9QXA";

    @Test
    void no_log_line_of_the_whole_flow_contains_text_a_ban_id_or_a_discord_id() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Level before = root.getLevel();
        root.setLevel(Level.TRACE);
        root.addAppender(captured);
        try {
            Harness h = new Harness();
            FakeTicketBackend.Rec ticket = h.newTicket(1);
            h.opens(ticket);
            h.appeals.open(Lang.EN, Long.toString(Harness.PLAYER), "Display Name Secret", BAN_ID, SECRET_TEXT);
            long channel = h.channelOf(ticket);
            h.portalMessage(ticket, 1, AuthorKind.STAFF, SECRET_TEXT);
            h.guild.say(channel, Harness.PLAYER, SECRET_TEXT);
            h.backend.down = 0;
            h.guild.say(channel, Harness.PLAYER, SECRET_TEXT + " during outage");
            h.ticks(2);
            h.backend.down = null;
            h.guild.failOn.put("postRelay", new GatewayException("Missing Permissions " + SECRET_TEXT, true, null));
            h.portalMessage(ticket, 2, AuthorKind.STAFF, SECRET_TEXT + " again");
            h.ticks(2);
            h.guild.failOn.clear();
            ticket.leaked = true;
            ticket.appealStatus = "DENIED";
            h.ticks(3);
            // The leak scanner: a real code, a look-alike, an outage and a missing permission.
            h.guild.addChannel(500L, "general", 0);
            h.backend.banIds.put("K7M29QXA", ticket);
            h.guild.say(500L, Harness.PLAYER, "oops #K7M2-9QXA " + SECRET_TEXT);
            h.guild.say(500L, Harness.PLAYER, "#ABCD-EFGH " + SECRET_TEXT);
            h.backend.down = 0;
            h.guild.say(500L, Harness.PLAYER, "#K7M2-9QXA during outage " + SECRET_TEXT);
            h.drain();
            h.scheduler.advance(java.time.Duration.ofMinutes(10));
            h.backend.down = null;
            h.guild.failOn.put("deleteMessage", new GatewayException("Missing Permissions " + SECRET_TEXT, true, null));
            h.guild.say(500L, Harness.PLAYER, "#K7M2-9QXA " + SECRET_TEXT);
            h.drain();
        } finally {
            root.detachAppender(captured);
            root.setLevel(before);
        }

        List<String> lines = captured.list.stream().map(e -> e.getFormattedMessage() + " " + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getClassName())).toList();
        assertFalse(lines.isEmpty(), "the flow logged something to look at");
        for (String line : lines) {
            assertFalse(line.contains("SECRET"), "no message content or name in: " + line);
            assertFalse(line.toUpperCase().contains("K7M2") || line.toUpperCase().contains("9QXA"), "no ban id in: " + line);
            assertFalse(line.contains(Long.toString(Harness.PLAYER)), "no player Discord id in: " + line);
        }
        assertEquals(0, lines.stream().filter(l -> l.contains(String.valueOf(Harness.STAFF_A))).count(), "no staff Discord id either");
        assertTrue(lines.size() > 0);
    }
}
