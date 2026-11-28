package net.onelitefeather.blackhole.backend.integration;

import net.onelitefeather.blackhole.backend.integration.DetectionValidator.Verdict;
import net.onelitefeather.blackhole.backend.integration.dto.DetectionReason;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.item;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.node;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.vulcan;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.with;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.withPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class DetectionValidatorTest {

    private final DetectionValidator validator = new DetectionValidator(new SchemaRegistry());
    private final Instant now = TestClock.START;

    private DetectionReason reasonOf(Map<String, Object> item) {
        return reasonOf(item, vulcan(true));
    }

    private DetectionReason reasonOf(Map<String, Object> item, IntegrationSpec spec) {
        Verdict verdict = this.validator.validate(node(item), spec, this.now);
        return verdict instanceof Verdict.Rejected rejected ? rejected.reason() : null;
    }

    @Test
    void aValidItemIsAccepted() {
        assertNull(reasonOf(item()));
    }

    @Test
    void theValidatedDetectionKeepsItsFieldsAndTheRawOwnerForTheService() {
        Map<String, Object> item = item();

        Verdict verdict = this.validator.validate(node(item), vulcan(true), this.now);

        Verdict.Valid valid = assertInstanceOf(Verdict.Valid.class, verdict);
        assertEquals("Lobby-2", valid.detection().server());
        assertEquals(UUID.fromString((String) item.get("owner")), valid.detection().owner());
    }

    @Test
    void aSourceSystemThatDiffersFromTheRegistrationIsSourceMismatch() {
        assertEquals(DetectionReason.SOURCE_MISMATCH, reasonOf(with(item(), "sourceSystem", "grim")));
    }

    @Test
    void aSourceSystemThatMatchesTheRegistrationIsAccepted() {
        assertNull(reasonOf(with(item(), "sourceSystem", "vulcan")));
    }

    @Test
    void anUnknownEnvelopeFieldIsSchemaInvalid() {
        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(with(item(), "world", "survival")));
    }

    @Test
    void aMissingRequiredEnvelopeFieldIsSchemaInvalid() {
        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(with(item(), "owner", null)));
    }

    @Test
    void anOccurrenceTimeTwentyFourHoursBackIsAcceptedAndOneSecondOlderIsOutOfRange() {
        assertNull(reasonOf(with(item(), "occurredAt", now.minusSeconds(24 * 3600).toString())), "the 24 hour edge is inside");
        assertEquals(DetectionReason.OCCURRED_AT_OUT_OF_RANGE,
                reasonOf(with(item(), "occurredAt", now.minusSeconds(24 * 3600 + 1).toString())));
    }

    @Test
    void anOccurrenceTimeFiveMinutesAheadIsAcceptedAndSixMinutesAheadIsOutOfRange() {
        assertNull(reasonOf(with(item(), "occurredAt", now.plusSeconds(300).toString())), "the 5 minute edge is inside");
        assertEquals(DetectionReason.OCCURRED_AT_OUT_OF_RANGE, reasonOf(with(item(), "occurredAt", now.plusSeconds(360).toString())));
    }

    @Test
    void anOccurrenceTimeThatIsNotAnInstantIsSchemaInvalid() {
        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(with(item(), "occurredAt", "yesterday")));
    }

    @Test
    void aServerLabelOfSixtyFiveCharactersIsSchemaInvalid() {
        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(with(item(), "server", "Lobby-" + "1".repeat(59))));
    }

    @Test
    void aServerOfAnotherTaskIsOutOfScope() {
        assertEquals(DetectionReason.SERVER_OUT_OF_SCOPE, reasonOf(with(item(), "server", "Survival-1")));
    }

    @Test
    void aPrefixLookalikeOfTheTaskIsOutOfScope() {
        assertEquals(DetectionReason.SERVER_OUT_OF_SCOPE, reasonOf(with(item(), "server", "Lobbyx-2")));
    }

    @Test
    void aStaticServerMatchesByExactNameOnly() {
        assertNull(reasonOf(with(item(), "server", "Creative")));
        assertEquals(DetectionReason.SERVER_OUT_OF_SCOPE, reasonOf(with(item(), "server", "Creative-2")));
    }

    @Test
    void aKindWithoutASchemaIsUnknownKind() {
        assertEquals(DetectionReason.UNKNOWN_KIND, reasonOf(with(item(), "kind", "CHAT")));
    }

    @Test
    void aSchemaVersionWithoutASchemaIsUnknownKind() {
        assertEquals(DetectionReason.UNKNOWN_KIND, reasonOf(with(item(), "schemaVersion", 2)));
    }

    @Test
    void aKindTheRegistrationDoesNotAllowIsUnknownKind() {
        IntegrationSpec noKinds = new IntegrationSpec("vulcan", "Vulcan", DetectionFixtures.CLIENT, true,
                List.of("CHAT:1"), List.of("Lobby"), List.of());

        assertEquals(DetectionReason.UNKNOWN_KIND, reasonOf(item(), noKinds));
    }

    @Test
    void aMalformedOwnerIsSchemaInvalid() {
        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(with(item(), "owner", "not-a-uuid")));
    }

    @Test
    void aSeverityOutsideTheEnumIsSchemaInvalidAndANullSeverityIsAccepted() {
        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(with(item(), "severity", "EXTREME")));
        assertNull(reasonOf(with(item(), "severity", null)), "severity is optional");
    }

    @Test
    void aUuidInThePayloadIsPiiInPayload() {
        Map<String, Object> item = withPayload(item(), "detail", "player " + UUID.randomUUID() + " flagged");

        assertEquals(DetectionReason.PII_IN_PAYLOAD, reasonOf(item));
    }

    @Test
    void anIpAddressInThePayloadIsPiiInPayload() {
        assertEquals(DetectionReason.PII_IN_PAYLOAD, reasonOf(withPayload(item(), "detail", "from 203.0.113.7")));
    }

    @Test
    void aUuidInTheSummaryIsPiiInPayload() {
        assertEquals(DetectionReason.PII_IN_PAYLOAD, reasonOf(with(item(), "summary", "flag for " + UUID.randomUUID())));
    }

    @Test
    void aControlCharacterInAPayloadStringIsSchemaInvalid() {
        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(withPayload(item(), "detail", "line\u0007bell")));
    }

    @Test
    void aPayloadOverFourKilobytesIsSchemaInvalid() {
        Map<String, Object> item = with(item(), "payload", Map.of("check", "KillAura", "padding", "x".repeat(5000)));

        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(item));
    }

    @Test
    void aPayloadNestedDeeperThanThreeLevelsIsSchemaInvalid() {
        Map<String, Object> item = with(item(), "payload",
                Map.of("check", "KillAura", "a", Map.of("b", Map.of("c", Map.of("d", "e")))));

        assertEquals(DetectionReason.SCHEMA_INVALID, reasonOf(item));
    }

    @Test
    void theDepthOfAPayloadCountsItsContainers() {
        assertEquals(0, DetectionValidator.depth(node(Map.of("k", "v")).get("k")), "a scalar has depth 0");
        assertEquals(1, DetectionValidator.depth(node(Map.of("check", "x"))));
        assertEquals(3, DetectionValidator.depth(node(Map.of("a", Map.of("b", Map.of("c", "d"))))));
        assertEquals(4, DetectionValidator.depth(node(Map.of("a", Map.of("b", Map.of("c", Map.of("d", "e")))))));
    }

    @Test
    void anUnparseableBodyItemIsSchemaInvalid() {
        assertEquals(DetectionReason.SCHEMA_INVALID,
                ((Verdict.Rejected) this.validator.validate(com.fasterxml.jackson.databind.node.TextNode.valueOf("x"), vulcan(true), now))
                        .reason());
    }
}
