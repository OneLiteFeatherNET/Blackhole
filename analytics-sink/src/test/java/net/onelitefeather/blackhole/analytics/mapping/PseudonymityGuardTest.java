package net.onelitefeather.blackhole.analytics.mapping;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PseudonymityGuardTest {

    private static final String RAW_PLAYER_UUID = "61699b2e-d327-4a01-9f1f-1a9d5f1b7c3e";

    private final EventMapper mapper = new EventMapper();

    @Test
    void rawPlayerUuidUnderTheOwnerKeyIsDeadLetteredAsPseudonymityViolation() {
        MappingResult result = mapper.map(EventFixtures.envelope("profile.created", Map.of("owner", RAW_PLAYER_UUID)));

        DeadLetter deadLetter = assertInstanceOf(DeadLetter.class, result, "a raw player UUID must never be written");
        assertEquals(DeadLetterReason.PSEUDONYMITY_VIOLATION, deadLetter.reason());
        assertTrue(deadLetter.detail().contains("owner"), "the detail should name the offending key: " + deadLetter.detail());
    }

    @Test
    void rawPlayerUuidUnderThePlayerUuidKeyIsDeadLettered() {
        assertPseudonymityViolation(Map.of("playerUuid", RAW_PLAYER_UUID), "playerUuid");
    }

    @Test
    void rawPlayerUuidUnderTheUuidKeyIsDeadLettered() {
        assertPseudonymityViolation(Map.of("uuid", RAW_PLAYER_UUID), "uuid");
    }

    @Test
    void playerUuidUnderAnIdentifierLikeKeyIsStillDeadLettered() {
        assertPseudonymityViolation(Map.of("playerIdentifier", RAW_PLAYER_UUID), "playerIdentifier");
    }

    private void assertPseudonymityViolation(Map<String, Object> payload, String key) {
        MappingResult result = mapper.map(EventFixtures.envelope("profile.created", payload));

        DeadLetter deadLetter = assertInstanceOf(DeadLetter.class, result, "a raw player UUID under " + key + " must be refused");
        assertEquals(DeadLetterReason.PSEUDONYMITY_VIOLATION, deadLetter.reason());
        assertTrue(deadLetter.detail().contains(key), "the detail should name the key: " + deadLetter.detail());
    }

    @Test
    void ipAddressAnywhereInThePayloadIsDeadLettered() {
        MappingResult result = mapper.map(EventFixtures.envelope("evasion.detected", Map.of(
                "token", "t".repeat(64),
                "owners", java.util.List.of("203.0.113.7"))));

        DeadLetter deadLetter = assertInstanceOf(DeadLetter.class, result, "a clear-text IP must never be written to a pseudonymous table");
        assertEquals(DeadLetterReason.PSEUDONYMITY_VIOLATION, deadLetter.reason());
    }

    @Test
    void uuidUnderAnIdentifierKeyIsAllowedBecauseItIsNotAPlayer() {
        MappingResult result = mapper.map(EventFixtures.envelope("report.feedback", Map.of(
                "feedbackIdentifier", RAW_PLAYER_UUID,
                "reportIdentifier", "7a1e2c3d-4b5f-4e6a-9c8d-0f1e2d3c4b5a",
                "reporterHash", "a".repeat(128),
                "outcome", "UPHELD")));

        assertInstanceOf(Mapped.class, result, "entity identifiers are UUIDs by design and are not player identities");
    }

    @Test
    void staffOidIsStoredAsASha512HashNotAsAUuid() {
        String staffOid = "9e107d9d-372b-4bb6-9b2f-6d2f3b8c1a20";
        EventRow row = assertInstanceOf(Mapped.class, mapper.map(EventFixtures.envelope("report.resolved", Map.of(
                "reportIdentifier", "7a1e2c3d-4b5f-4e6a-9c8d-0f1e2d3c4b5a",
                "reportedHash", "b".repeat(128),
                "status", "UPHELD",
                "resolvedBy", staffOid,
                "recordedByOid", staffOid)))).row();

        String stored = row.extra();
        assertFalse(stored.contains(staffOid), "the staff OID must not appear in clear text: " + stored);
        assertTrue(Pattern.compile("\"recordedByOid\":\"[0-9a-f]{128}\"").matcher(stored).find(),
                "the staff OID must be stored as a SHA-512 hex digest: " + stored);
    }
}
