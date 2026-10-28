package net.onelitefeather.blackhole.analytics.mapping;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentEventMapperTest {

    private static final String PUNISHMENT_ID = "3f2c9a52-0c2e-4f1e-9d7e-2b1a6c8d9e01";
    private static final String TEMPLATE_ID = "chat-spam";

    private final EventMapper mapper = new EventMapper();

    @Test
    void createdPunishmentMapsOwnerIdentifierTemplateAndPermanentToTypedColumns() {
        EventRow row = mapped(EventFixtures.envelope("punishment.created", createdPayload()));

        assertEquals(EventFixtures.OWNER_HASH, row.owner(), "the owner hash is the common owner column");
        assertEquals(PUNISHMENT_ID, row.typed().get("punishmentIdentifier"));
        assertEquals(TEMPLATE_ID, row.typed().get("templateIdentifier"));
        assertEquals(Boolean.FALSE, row.typed().get("permanent"));
        assertEquals("Spam in chat", row.typed().get("reason"));
        assertEquals("2026-10-01T10:00:00Z", row.typed().get("expiresAt"));
    }

    @Test
    void createdPunishmentKeepsRemainingPayloadKeysInExtra() {
        EventRow row = mapped(EventFixtures.envelope("punishment.created", createdPayload()));

        assertTrue(row.extra().contains("\"section\":\"chat\""), "section has no typed column and must stay in extra: " + row.extra());
        assertTrue(row.extra().contains("\"enforcement\":\"SOFT\""), "enforcement must stay in extra: " + row.extra());
    }

    @Test
    void punishmentKickMapsToThePunishmentTableWithItsKickIdAsTypedColumn() {
        String kickId = "5c0e8f2a-7d1b-4e3c-9a6f-1b2c3d4e5f60";
        EventRow row = mapped(EventFixtures.envelope("punishment.kick", Map.of(
                "eventId", "6d1f9a3b-8e2c-4f4d-8b7a-2c3d4e5f6a71",
                "kickId", kickId,
                "owner", EventFixtures.OWNER_HASH,
                "scope", "NETWORK",
                "occurredAt", EventFixtures.OCCURRED_AT_MILLIS)));

        assertEquals("punishment_events", row.table(), "a kick is a punishment event");
        assertEquals(EventFixtures.OWNER_HASH, row.owner());
        assertEquals(kickId, row.typed().get("kickId"), "the kick id is an entity id, not a player identity");
        assertTrue(row.extra().contains("\"scope\":\"NETWORK\""), "scope has no typed column and stays in extra: " + row.extra());
    }

    @Test
    void booleanColumnWithANonBooleanValueIsDeadLettered() {
        Map<String, Object> payload = Map.of(
                "owner", EventFixtures.OWNER_HASH,
                "punishmentIdentifier", PUNISHMENT_ID,
                "permanent", "yes");

        MappingResult result = mapper.map(EventFixtures.envelope("punishment.created", payload));

        DeadLetter deadLetter = assertInstanceOf(DeadLetter.class, result);
        assertEquals(DeadLetterReason.MALFORMED, deadLetter.reason(), "a string in a boolean column is malformed");
    }

    private EventRow mapped(EventEnvelope envelope) {
        MappingResult result = mapper.map(envelope);
        Mapped mapped = assertInstanceOf(Mapped.class, result, "the event should map, got " + result);
        assertEquals(Instant.ofEpochMilli(EventFixtures.OCCURRED_AT_MILLIS), mapped.row().occurredAt());
        assertFalse(mapped.row().table().isBlank());
        return mapped.row();
    }

    private static Map<String, Object> createdPayload() {
        return Map.of(
                "owner", EventFixtures.OWNER_HASH,
                "punishmentIdentifier", PUNISHMENT_ID,
                "section", "chat",
                "enforcement", "SOFT",
                "templateIdentifier", TEMPLATE_ID,
                "permanent", false,
                "reason", "Spam in chat",
                "expiresAt", "2026-10-01T10:00:00Z");
    }
}
