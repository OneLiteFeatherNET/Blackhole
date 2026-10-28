package net.onelitefeather.blackhole.analytics.mapping;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One mapping behaviour per event family: typed columns and what stays in extra. */
class FamilyMappingTest {

    private static final String PUNISHMENT_ID = "3f2c9a52-0c2e-4f1e-9d7e-2b1a6c8d9e01";

    private final EventMapper mapper = new EventMapper();

    @Test
    void profileEventHasOnlyTheOwnerColumn() {
        EventRow row = mapped("profile.created", Map.of("owner", EventFixtures.OWNER_HASH));

        assertEquals("profile_events", row.table());
        assertEquals(EventFixtures.OWNER_HASH, row.owner());
        assertTrue(row.typed().isEmpty(), "a profile event has no typed columns beyond the owner");
        assertEquals("{}", row.extra());
    }

    @Test
    void appealResolvedMapsDecisionAndEligibilityToTypedColumns() {
        EventRow row = mapped("appeal.resolved", Map.of(
                "appealIdentifier", "0b9d6a1e-3c4f-4a7b-8e2d-5f6a7b8c9d0e",
                "owner", EventFixtures.OWNER_HASH,
                "punishmentIdentifier", PUNISHMENT_ID,
                "section", "chat",
                "decision", "GRANTED_DURATION_REDUCTION",
                "eligible", true));

        assertEquals("appeal_events", row.table());
        assertEquals("GRANTED_DURATION_REDUCTION", row.typed().get("decision"));
        assertEquals(Boolean.TRUE, row.typed().get("eligible"));
        assertTrue(row.extra().contains("\"section\":\"chat\""), "section is not a typed appeal column: " + row.extra());
    }

    @Test
    void reportCreatedMapsReporterAndReportedHashesToTypedColumns() {
        EventRow row = mapped("report.created", Map.of(
                "reportIdentifier", "7a1e2c3d-4b5f-4e6a-9c8d-0f1e2d3c4b5a",
                "reporterHash", "a".repeat(128),
                "reportedHash", "b".repeat(128),
                "category", "HARASSMENT"));

        assertEquals("report_events", row.table());
        assertEquals("a".repeat(128), row.typed().get("reporterHash"));
        assertEquals("b".repeat(128), row.typed().get("reportedHash"));
        assertEquals("HARASSMENT", row.typed().get("category"));
    }

    @Test
    void eloThresholdCrossingMapsTierAndResultingScoreToTypedColumns() {
        EventRow row = mapped("elo.threshold_crossed", Map.of(
                "owner", EventFixtures.OWNER_HASH,
                "section", "chat",
                "tier", "PERMANENT",
                "resultingScore", 120L));

        assertEquals("elo_events", row.table());
        assertEquals("PERMANENT", row.typed().get("tier"));
        assertEquals(120L, row.typed().get("resultingScore"));
    }

    @Test
    void clientRuleHitMapsRuleKindAndActionToTypedColumns() {
        EventRow row = mapped("client-rule.hit", Map.of(
                "rule", "forbidden-mod",
                "owner", EventFixtures.OWNER_HASH,
                "kind", "MOD",
                "action", "KICK"));

        assertEquals("client_rule_hits", row.table());
        assertEquals("forbidden-mod", row.typed().get("rule"));
        assertEquals("KICK", row.typed().get("action"));
    }

    @Test
    void evasionClientMatchKeepsTheOwnerListInExtra() {
        EventRow row = mapped("evasion.client_match", Map.of(
                "owners", java.util.List.of(EventFixtures.OWNER_HASH, "c".repeat(128)),
                "at", 1_790_000_000_000L));

        assertEquals("evasion_events", row.table());
        assertTrue(row.extra().contains("\"owners\""), "the owner list has no typed column and stays in extra: " + row.extra());
        assertTrue(row.extra().contains("\"at\":1790000000000"));
    }

    @Test
    void sectionChangedKeepsTheChangeInExtra() {
        EventRow row = mapped("section.changed", Map.of(
                "section", "chat",
                "change", Map.of("field", "softDuration", "to", "PT1H")));

        assertEquals("section_events", row.table());
        assertEquals("chat", row.typed().get("section"));
        assertTrue(row.extra().contains("\"change\""), "the change payload stays in extra: " + row.extra());
    }

    private EventRow mapped(String eventType, Map<String, Object> payload) {
        MappingResult result = mapper.map(EventFixtures.envelope(eventType, payload));
        return assertInstanceOf(Mapped.class, result, "the " + eventType + " event should map, got " + result).row();
    }
}
