package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardRoutingKeyTest {

    private static final String PANEL = "https://panel.example.org";

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static NotificationDispatcher dispatcher(ScriptedBackend backend, FakeDiscord discord) {
        return new NotificationDispatcher(
                new NotificationHandler(backend, discord, new EmbedRenderer(PANEL, 1L), 2L),
                new GuardNotificationHandler(discord, new GuardMessageRenderer(PANEL), 2L));
    }

    @Test
    void theRoutingKeysIncludeTheFourGuardEvents() {
        List<String> keys = List.of(NotificationEvent.ROUTING_KEYS);

        assertTrue(keys.containsAll(List.of("guard.level_changed", "area_ban.created", "area_ban.lifted", "guard.denials.summary")),
                "the guard events are subscribed: " + keys);
    }

    @Test
    void theReportAndAppealKeysAreStillSubscribed() {
        List<String> keys = List.of(NotificationEvent.ROUTING_KEYS);

        assertTrue(keys.containsAll(List.of("report.created", "report.resolved", "appeal.submitted", "appeal.resolved")),
                "the existing subscriptions remain: " + keys);
    }

    @Test
    void anUnknownEventTypeIsNotParsedAsAGuardEvent() {
        assertFalse(GuardEvent.parse(json("{\"eventId\":\"e\",\"eventType\":\"punishment.created\",\"payload\":{}}")).isPresent(),
                "a punishment event is not a guard event");
        assertFalse(GuardEvent.parse(json("{\"eventId\":\"e\",\"eventType\":\"guard.unknown\",\"payload\":{}}")).isPresent(),
                "an unknown guard event is not parsed");
    }

    @Test
    void anUnknownEventTypeIsDroppedByTheDispatcher() {
        FakeDiscord discord = new FakeDiscord();

        Outcome outcome = dispatcher(new ScriptedBackend(), discord)
                .onMessage(json("{\"eventId\":\"e\",\"eventType\":\"punishment.created\",\"payload\":{}}"));

        assertEquals(Outcome.DROP, outcome, "an event the bot does not handle is dropped");
        assertTrue(discord.messages.isEmpty(), "nothing is posted for it");
    }

    @Test
    void aGuardEventReachesTheGuardHandlerWithoutABackendRead() {
        FakeDiscord discord = new FakeDiscord();

        Outcome outcome = dispatcher(new ScriptedBackend(), discord).onMessage(json(
                "{\"eventId\":\"g1\",\"eventType\":\"guard.level_changed\",\"payload\":"
                        + "{\"level\":\"STRICT\",\"previous\":\"OFF\",\"source\":\"UNATTENDED\"}}"));

        assertEquals(Outcome.DONE, outcome, "a guard event is handled");
        assertEquals(1, discord.messages.size(), "and posted to the team channel");
        assertEquals(List.of("find", "post"), discord.calls, "with the Discord marker lookup only, and no backend read of a report or appeal");
    }
}
