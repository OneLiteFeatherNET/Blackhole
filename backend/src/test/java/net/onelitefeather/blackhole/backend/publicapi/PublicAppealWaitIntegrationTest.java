package net.onelitefeather.blackhole.backend.publicapi;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The default waiting period of the checklist (14 days for a manual punishment) as the player meets it. */
@Tag("integration")
class PublicAppealWaitIntegrationTest extends PublicApiTestBase {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("test.recording-events", true);
    }

    @SuppressWarnings("unchecked")
    private long appealsOf(String punishment) {
        List<Map<String, Object>> all = (List<Map<String, Object>>) get("/appeal?size=100").json().get("content");
        return all.stream().filter(a -> punishment.equals(a.get("punishmentIdentifier"))).count();
    }

    @Test
    void filingBeforeTheMinimumWaitIsRefusedWithTheDateAndRecordsNothing() {
        String punishment = punish("e1".repeat(64), "pub-early");
        String token = sessionFor(punishment);

        Raw facts = playerGet("/public/punishment", token);
        Raw filing = playerPost("/public/appeal", token, Map.of("statement", "I would like to appeal"));

        Map<?, ?> appeal = (Map<?, ?>) facts.json().get("appeal");
        assertEquals("NOT_YET", appeal.get("state"));
        assertEquals(409, filing.status(), filing.body());
        assertEquals("NOT_ELIGIBLE", filing.json().get("code"));
        assertNotNull(filing.json().get("availableFrom"));
        assertEquals(appeal.get("availableFrom"), filing.json().get("availableFrom"), "the facts and the refusal name the same date");
        Instant opens = Instant.parse((String) filing.json().get("availableFrom"));
        Instant created = Instant.parse((String) facts.json().get("createdAt"));
        assertEquals(Duration.ofDays(14), Duration.between(created, opens), "14 days after the punishment was made");
        assertEquals(0, appealsOf(punishment), "an attempt that is not eligible is not recorded");
        assertEquals(0, events().eventsOfType("appeal.submitted").size());
    }
}
