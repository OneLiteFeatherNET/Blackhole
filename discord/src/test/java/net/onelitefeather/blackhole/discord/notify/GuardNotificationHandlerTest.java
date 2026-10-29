package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageRef;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardNotificationHandlerTest {

    private static final long CHANNEL = 2L;
    private static final String PANEL = "https://panel.example.org";
    private static final String LEVEL_CHANGE = "{\"eventId\":\"g1\",\"eventType\":\"guard.level_changed\",\"payload\":"
            + "{\"level\":\"STRICT\",\"previous\":\"OFF\",\"source\":\"UNATTENDED\"}}";

    private static GuardEvent event() {
        return GuardEvent.parse(LEVEL_CHANGE.getBytes(StandardCharsets.UTF_8)).orElseThrow();
    }

    @Test
    void aGuardEventIsPostedOnceToTheTeamChannel() {
        FakeDiscord discord = new FakeDiscord();

        Outcome outcome = new GuardNotificationHandler(discord, new GuardMessageRenderer(PANEL), CHANNEL).handle(event());

        assertEquals(Outcome.DONE, outcome, "the event is handled");
        assertEquals(List.of("find", "post"), discord.calls, "the message is looked up, then posted");
        assertEquals(1, discord.messages.size(), "exactly one message is posted");
        assertEquals(CHANNEL, discord.messages.keySet().iterator().next().channelId(), "to the team channel");
    }

    @Test
    void aRedeliveredGuardEventIsNotPostedAgain() {
        FakeDiscord discord = new FakeDiscord();
        GuardNotificationHandler handler = new GuardNotificationHandler(discord, new GuardMessageRenderer(PANEL), CHANNEL);
        handler.handle(event());
        discord.calls.clear();

        Outcome outcome = handler.handle(event());

        assertEquals(Outcome.DONE, outcome, "the redelivery is acknowledged");
        assertEquals(List.of(), discord.calls, "without any Discord call");
        assertEquals(1, discord.messages.size(), "and still exactly one message");
    }

    @Test
    void aGuardEventPostedBeforeARestartIsNotPostedAgain() {
        FakeDiscord discord = new FakeDiscord();
        GuardMessageRenderer renderer = new GuardMessageRenderer(PANEL);
        discord.messages.put(new MessageRef(CHANNEL, 99L), renderer.render(event()));
        discord.calls.clear();

        Outcome outcome = new GuardNotificationHandler(discord, renderer, CHANNEL).handle(event());

        assertEquals(Outcome.DONE, outcome, "the event is acknowledged");
        assertEquals(List.of("find"), discord.calls, "the existing message is found and nothing is posted");
        assertEquals(1, discord.messages.size(), "no second message after a restart");
    }

    @Test
    void aPermanentDiscordFailureDropsTheGuardEvent() {
        FakeDiscord discord = new FakeDiscord();
        discord.failure = new GatewayException("the bot cannot post in the team channel", true, null);

        Outcome outcome = new GuardNotificationHandler(discord, new GuardMessageRenderer(PANEL), CHANNEL).handle(event());

        assertEquals(Outcome.DROP, outcome, "a post that can never succeed is dropped");
    }

    @Test
    void aTransientDiscordFailureRetriesTheGuardEventUntilItIsPosted() {
        FakeDiscord discord = new FakeDiscord();
        discord.failure = new GatewayException("Discord is unavailable", false, null);
        GuardNotificationHandler handler = new GuardNotificationHandler(discord, new GuardMessageRenderer(PANEL), CHANNEL);

        assertEquals(Outcome.RETRY, handler.handle(event()), "a transient failure is retried later");

        discord.failure = null;
        assertEquals(Outcome.DONE, handler.handle(event()), "the retry is posted");
        assertEquals(1, discord.messages.size(), "exactly one message after the retry");
    }
}
