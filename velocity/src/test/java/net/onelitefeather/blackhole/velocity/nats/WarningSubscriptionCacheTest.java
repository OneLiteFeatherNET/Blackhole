package net.onelitefeather.blackhole.velocity.nats;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.enforcement.WarningMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.active;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A warning event reaches the warning reaction and never touches the punishment cache: a warning is not
 * a punishment and is never enforced from the mirror. Driven by calling the listener; no NATS client runs.
 */
class WarningSubscriptionCacheTest {

    private static final String WARNING_ID = "7K3M9QXA";

    private final PunishmentMirror mirror = new PunishmentMirror();
    private final List<PunishmentSyncMessage> punishments = new ArrayList<>();
    private final List<String> evicted = new ArrayList<>();
    private final List<WarningMessage> warnings = new ArrayList<>();

    private final NatsSyncService service = new NatsSyncService(mirror, new NatsSyncService.Reactions(
            punishments::add, evicted::add, () -> { }, report -> { }, owner -> { }, kick -> { }, mass -> { },
            warnings::add), Runnable::run);

    private void publish(String payload) {
        String envelope = "{\"eventId\":\"e1\",\"eventType\":\"punishment.warned\",\"occurredAt\":1,\"payloadVersion\":1,\"payload\":"
                + payload + "}";
        this.service.message(Subjects.PUNISHMENT_WARNED, envelope.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aWarningEventReachesTheWarningReaction() {
        publish("{\"eventId\":\"e1\",\"warningId\":\"" + WARNING_ID + "\",\"owner\":\"" + OWNER + "\",\"occurredAt\":1}");

        assertEquals(List.of(new WarningMessage(WARNING_ID, OWNER)), this.warnings);
    }

    @Test
    void aWarningEventLeavesThePunishmentCacheAndItsReactionsAlone() {
        this.mirror.load(OWNER, List.of(active("network", EnforcementKind.BAN, "p1", null)));

        publish("{\"eventId\":\"e1\",\"warningId\":\"" + WARNING_ID + "\",\"owner\":\"" + OWNER + "\",\"occurredAt\":1}");

        assertTrue(this.mirror.isLoaded(OWNER), "the owner stays cached");
        assertEquals(1, this.mirror.active(OWNER).size(), "the cached punishments are unchanged");
        assertTrue(this.punishments.isEmpty(), "no punishment reaction ran");
        assertTrue(this.evicted.isEmpty(), "nothing was evicted");
    }

    @Test
    void aWarningEventWithoutItsOwnerIsIgnored() {
        publish("{\"eventId\":\"e1\",\"warningId\":\"" + WARNING_ID + "\",\"occurredAt\":1}");

        assertTrue(this.warnings.isEmpty(), "a warning nobody can be told about is not handled");
    }
}
