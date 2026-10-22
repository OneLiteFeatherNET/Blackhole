package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.service.EvidenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Evidence first, ban second: the punishment claims the pending evidence of its owner and correlation id. */
@Tag("integration")
class EvidenceClaimIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    private void givenTemplate(String key) {
        Reply created = post("/template", Map.of("key", key, "reason", "Reason", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10))));
        assertEquals(200, created.status(), created.body());
    }

    private void givenPending(String owner, String correlation) {
        EvidenceDTO result = this.context.getBean(EvidenceService.class).attach(new EvidenceDTO.CreateRequest(
                null, owner, correlation, EvidenceKind.LINK, "it", null, "https://example.org/replay", null, UUID.fromString(STAFF)));
        assertInstanceOf(EvidenceDTO.Response.class, result, String.valueOf(result));
    }

    private Reply apply(String owner, String template, String correlation) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("owner", owner, "templateKey", template, "source", STAFF));
        if (correlation != null) {
            body.put("evidenceCorrelationId", correlation);
        }
        return post("/punishment/apply", body);
    }

    private long listed(String punishment) {
        EvidenceDTO.PageResponse page = assertInstanceOf(EvidenceDTO.PageResponse.class,
                this.context.getBean(EvidenceService.class).list(punishment, false, Pageable.from(0, 20), null));
        return page.totalSize();
    }

    @SuppressWarnings("unchecked")
    private static String punishmentOf(Reply applied) {
        return (String) ((Map<String, Object>) applied.json().get("punishment")).get("identifier");
    }

    @Test
    void evidenceSentBeforeTheBanIsListedOnTheBan() {
        String owner = "a1".repeat(64);
        givenTemplate("claim-ok");
        givenPending(owner, "corr-1");

        Reply applied = apply(owner, "claim-ok", "corr-1");

        assertEquals(200, applied.status(), applied.body());
        assertEquals(1, listed(punishmentOf(applied)), "the pending evidence belongs to the ban now");
    }

    @Test
    void evidenceOfAnotherOwnerIsNotClaimed() {
        String owner = "a2".repeat(64);
        givenTemplate("claim-owner");
        givenPending("b2".repeat(64), "corr-2");

        Reply applied = apply(owner, "claim-owner", "corr-2");

        assertEquals(0, listed(punishmentOf(applied)), "a different owner's evidence stays pending");
    }

    @Test
    void evidenceWithAnotherCorrelationIdIsNotClaimed() {
        String owner = "a3".repeat(64);
        givenTemplate("claim-id");
        givenPending(owner, "corr-3");

        Reply applied = apply(owner, "claim-id", "other-corr");

        assertEquals(0, listed(punishmentOf(applied)));
    }

    @Test
    void aBanWithoutACorrelationIdClaimsNothing() {
        String owner = "a4".repeat(64);
        givenTemplate("claim-none");
        givenPending(owner, "corr-4");

        Reply applied = apply(owner, "claim-none", null);

        assertEquals(0, listed(punishmentOf(applied)));
    }
}
