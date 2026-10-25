package net.onelitefeather.blackhole.velocity.nats;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.enforcement.KickMessage;
import net.onelitefeather.blackhole.velocity.enforcement.MassKickMessage;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.active;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kick events are subscribed to, reach the kick reactions, and never touch the punishment cache: a
 * kick is not a punishment. Driven by calling the listener; no NATS client runs.
 */
class KickSubscriptionCacheTest {

    private static final String KICK_ID = "6f0c5d0e-0000-4000-8000-000000000001";

    private final PunishmentMirror mirror = new PunishmentMirror();
    private final List<PunishmentSyncMessage> punishments = new ArrayList<>();
    private final List<String> evicted = new ArrayList<>();
    private final List<KickMessage> kicks = new ArrayList<>();
    private final List<MassKickMessage> massKicks = new ArrayList<>();

    private final NatsSyncService service = new NatsSyncService(mirror, new NatsSyncService.Reactions(
            punishments::add, evicted::add, () -> { }, report -> { }, owner -> { }, kicks::add, massKicks::add), Runnable::run);

    private void publish(String subject, String payload) {
        String eventType = subject.substring("blackhole.events.".length());
        String envelope = "{\"eventId\":\"e1\",\"eventType\":\"" + eventType + "\",\"occurredAt\":1,\"payloadVersion\":1,\"payload\":" + payload + "}";
        service.message(subject, envelope.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aKickEventDoesNotChangeThePunishmentCache() {
        mirror.load(OWNER, List.of(active("network", EnforcementKind.BAN, "p1", null)));

        publish(Subjects.PUNISHMENT_KICK, "{\"eventId\":\"e1\",\"kickId\":\"" + KICK_ID + "\",\"owner\":\"" + OWNER
                + "\",\"scope\":\"NETWORK\",\"occurredAt\":1}");

        assertTrue(mirror.isLoaded(OWNER), "the owner stays cached");
        assertEquals(1, mirror.active(OWNER).size(), "the cached punishments are unchanged");
        assertTrue(punishments.isEmpty(), "no punishment reaction ran");
        assertTrue(evicted.isEmpty(), "nothing was evicted");
    }

    @Test
    void aKickEventReachesTheKickReaction() {
        publish(Subjects.PUNISHMENT_KICK, "{\"eventId\":\"e1\",\"kickId\":\"" + KICK_ID + "\",\"owner\":\"" + OWNER
                + "\",\"scope\":\"SERVER\",\"target\":\"bedwars-1\",\"occurredAt\":1}");

        assertEquals(List.of(new KickMessage(KICK_ID, OWNER, "SERVER", "bedwars-1")), kicks);
    }

    @Test
    void aNetworkKickHasNoTarget() {
        publish(Subjects.PUNISHMENT_KICK, "{\"kickId\":\"" + KICK_ID + "\",\"owner\":\"" + OWNER + "\",\"scope\":\"NETWORK\"}");

        assertNull(kicks.getFirst().target());
    }

    @Test
    void aMassKickEventReachesTheMassReactionAndLeavesTheCacheAlone() {
        mirror.load(OWNER, List.of(active("network", EnforcementKind.BAN, "p1", null)));

        publish(Subjects.KICK_MASS, "{\"eventId\":\"e1\",\"actionId\":\"" + KICK_ID + "\",\"scope\":\"SECTION\",\"target\":\"bedwars\",\"occurredAt\":1}");

        assertEquals(List.of(new MassKickMessage(KICK_ID, "SECTION", "bedwars")), massKicks);
        assertTrue(mirror.isLoaded(OWNER));
        assertTrue(punishments.isEmpty() && evicted.isEmpty());
    }

    @Test
    void incompleteKickEventsAreIgnored() {
        publish(Subjects.PUNISHMENT_KICK, "{\"owner\":\"" + OWNER + "\",\"scope\":\"NETWORK\"}");
        publish(Subjects.PUNISHMENT_KICK, "{\"kickId\":\"" + KICK_ID + "\",\"scope\":\"NETWORK\"}");
        publish(Subjects.KICK_MASS, "{\"actionId\":\"" + KICK_ID + "\"}");

        assertTrue(kicks.isEmpty() && massKicks.isEmpty());
    }

    @Test
    void theProxySubscribesToTheMassKickSubjectAndTheKickRidesTheWildcard() {
        assertTrue(Subjects.SUBSCRIPTIONS.contains("blackhole.events.punishment.*"), "punishment.kick matches the wildcard");
        assertTrue(Subjects.SUBSCRIPTIONS.contains("blackhole.events.kick.mass"));
        assertEquals("blackhole.events.punishment.kick", Subjects.PUNISHMENT_KICK);
    }
}
