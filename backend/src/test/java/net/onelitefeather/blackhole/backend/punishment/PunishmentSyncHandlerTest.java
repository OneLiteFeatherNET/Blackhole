package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.events.DomainEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentSyncHandlerTest {

    private record Set(String section, String enforcement, String owner, String id, String template, String reason, Long expiresAt, boolean automatic) {
    }

    private record Clear(String section, String enforcement, String owner, String id) {
    }

    private final List<Object> mirrorCalls = new ArrayList<>();
    private final PunishmentSyncHandler handler = new PunishmentSyncHandler(new PunishmentMirror() {
        @Override
        public void set(String section, String enforcement, String owner, String id, String template, String reason, Long expiresAt, boolean automatic) {
            mirrorCalls.add(new Set(section, enforcement, owner, id, template, reason, expiresAt, automatic));
        }

        @Override
        public void clear(String section, String enforcement, String owner, String id) {
            mirrorCalls.add(new Clear(section, enforcement, owner, id));
        }
    });

    private static DomainEvent event(String type, Map<String, Object> payload) {
        return new DomainEvent("id", type, 0L, 1, payload);
    }

    private static Map<String, Object> payload(String section, String enforcement) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner", "owner");
        payload.put("punishmentIdentifier", "p-1");
        payload.put("section", section);
        payload.put("enforcement", enforcement);
        return payload;
    }

    @Test
    void anAutomaticPunishmentIsMirroredAsAutomatic() {
        Map<String, Object> payload = payload("network", "BAN");
        payload.put("reason", "Permanently banned after repeated violations");
        payload.put("automatic", true);

        handler.handle(event("punishment.created", payload));

        assertEquals(List.of(new Set("network", "BAN", "owner", "p-1", null, "Permanently banned after repeated violations", null, true)),
                mirrorCalls);
    }

    @Test
    void aCreatedTemporaryPunishmentSetsTheStateOfItsSectionWithItsExpiry() {
        Map<String, Object> payload = payload("network", "BAN");
        payload.put("templateIdentifier", "t-1");
        payload.put("reason", "Griefing");
        payload.put("expiresAt", 1_900_000_000_000L);

        handler.handle(event("punishment.created", payload));

        assertEquals(List.of(new Set("network", "BAN", "owner", "p-1", "t-1", "Griefing", 1_900_000_000_000L, false)), mirrorCalls);
    }

    @Test
    void aCreatedPermanentPunishmentHasNoExpiry() {
        handler.handle(event("punishment.created", payload("chat", "MUTE")));

        assertEquals(List.of(new Set("chat", "MUTE", "owner", "p-1", null, null, null, false)), mirrorCalls);
    }

    @Test
    void anUpdatedPunishmentSetsTheStateAgainWithItsNewExpiry() {
        Map<String, Object> payload = payload("network", "BAN");
        payload.put("expiresAt", 5L);

        handler.handle(event("punishment.updated", payload));

        assertEquals(5L, ((Set) mirrorCalls.getFirst()).expiresAt());
    }

    @Test
    void anExpiredMuteClearsTheChatKeyOnly() {
        handler.handle(event("punishment.expired", payload("chat", "MUTE")));

        assertEquals(List.of(new Clear("chat", "MUTE", "owner", "p-1")), mirrorCalls);
    }

    @Test
    void aRevokedPunishmentClearsItsSection() {
        handler.handle(event("punishment.revoked", payload("network", "BAN")));

        assertEquals(List.of(new Clear("network", "BAN", "owner", "p-1")), mirrorCalls);
    }

    @Test
    void anAppealThatLiftsThePunishmentClearsItsSection() {
        Map<String, Object> payload = payload("network", "BAN");
        payload.put("decision", "GRANTED_FULL_LIFT");

        handler.handle(event("appeal.resolved", payload));

        assertEquals(List.of(new Clear("network", "BAN", "owner", "p-1")), mirrorCalls);
    }

    @Test
    void aDeniedAppealChangesNothing() {
        Map<String, Object> payload = payload("network", "BAN");
        payload.put("decision", "DENIED");

        handler.handle(event("appeal.resolved", payload));

        assertTrue(mirrorCalls.isEmpty());
    }

    @Test
    void anEventWithoutASectionIsIgnored() {
        Map<String, Object> payload = payload("network", "BAN");
        payload.remove("section");

        handler.handle(event("punishment.created", payload));

        assertTrue(mirrorCalls.isEmpty(), "legacy events cannot be mapped to a key");
    }

    @Test
    void anUnrelatedEventIsIgnored() {
        handler.handle(event("report.created", payload("network", "BAN")));

        assertTrue(mirrorCalls.isEmpty());
    }
}
