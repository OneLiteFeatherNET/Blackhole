package net.onelitefeather.blackhole.analytics.mapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventFamilyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "punishment.created", "punishment.updated", "punishment.expired", "punishment.revoked", "punishment.kick",
            "profile.created", "profile.updated", "profile.deleted",
            "appeal.submitted", "appeal.resolved",
            "report.created", "report.resolved", "report.feedback",
            "elo.threshold_crossed", "section.escalated",
            "client-rule.hit",
            "evasion.detected", "evasion.client_match",
            "section.changed"
    })
    void everyPublishedEventTypeMapsToAFamily(String eventType) {
        assertTrue(EventFamily.of(eventType).isPresent(), "event type " + eventType + " should belong to a family");
    }

    @ParameterizedTest
    @ValueSource(strings = {"punishment.created", "section.escalated", "evasion.client_match", "section.changed"})
    void eachEventTypeBelongsToExactlyOneFamily(String eventType) {
        long families = Arrays.stream(EventFamily.values())
                .filter(family -> family.eventTypes().contains(eventType))
                .count();
        assertEquals(1, families, "event type " + eventType + " should be listed by exactly one family");
    }

    @Test
    void unknownEventTypeMapsToNoFamily() {
        Optional<EventFamily> family = EventFamily.of("punishment.unknown-type");

        assertFalse(family.isPresent(), "an event type outside the table must not be mapped to any family");
    }

    @Test
    void eloFamilyCollectsSectionEscalationsNextToThresholdCrossings() {
        List<String> types = EventFamily.of("section.escalated").orElseThrow().eventTypes();

        assertTrue(types.contains("elo.threshold_crossed"), "elo_events should hold the threshold crossings");
    }
}
