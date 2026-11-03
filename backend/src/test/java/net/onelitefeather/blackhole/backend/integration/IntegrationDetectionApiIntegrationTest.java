package net.onelitefeather.blackhole.backend.integration;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.item;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.with;
import static net.onelitefeather.blackhole.backend.integration.DetectionFixtures.withPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detection route against real PostgreSQL. The schema registry is keyed by source system and only vulcan has a schema,
 * so one vulcan registration (client {@link TestJwtIssuer#INTEGRATION_CLIENT}) serves the whole class; it is created by the
 * first test that needs it. Detection ids are random per item, so tests do not see each other's rows.
 * The application clock is the system clock, so occurrence times are taken from the wall clock here (inside the window).
 */
@Tag("integration")
class IntegrationDetectionApiIntegrationTest extends ApiIntegrationTest {

    private static final String PATH = "/integration/detections";
    private static final String CLIENT = TestJwtIssuer.INTEGRATION_CLIENT;

    /** The item with a time inside the window of the application clock. */
    private static Map<String, Object> fresh(Map<String, Object> item) {
        return with(item, "occurredAt", Instant.now().minusSeconds(60).toString());
    }

    /** Creates the vulcan registration (scope: Lobby tasks, Creative static) unless the class already has it. */
    private void ensureVulcanRegistered() {
        boolean present = get("/integration/registrations").jsonList().stream()
                .anyMatch(row -> "vulcan".equals(row.get("sourceSystem")));
        if (present) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("sourceSystem", "vulcan");
        body.put("displayName", "Vulcan bridge");
        body.put("entraClientId", CLIENT);
        body.put("allowedKinds", List.of("DETECTION:1"));
        body.put("allowedTasks", List.of("Lobby"));
        body.put("allowedStaticServers", List.of("Creative"));
        Reply created = post("/integration/registrations", body);
        assertEquals(200, created.status(), created.body());
    }

    private Reply submit(List<Map<String, Object>> items) {
        ensureVulcanRegistered();
        return post(PATH, Map.of("detections", items), this.jwt.integration(CLIENT));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Reply reply) {
        assertEquals(200, reply.status(), reply.body());
        return (List<Map<String, Object>>) reply.json().get("results");
    }

    @Test
    void aValidDetectionIsAcceptedAndARetryIsDuplicate() {
        Map<String, Object> detection = fresh(item());

        assertEquals("ACCEPTED", results(submit(List.of(detection))).get(0).get("status"));
        assertEquals("DUPLICATE", results(submit(List.of(detection))).get(0).get("status"));
    }

    @Test
    void aModeratorTokenIsForbidden() {
        ensureVulcanRegistered();

        assertEquals(403, post(PATH, Map.of("detections", List.of(fresh(item()))), this.jwt.moderator()).status());
    }

    @Test
    void anIntegrationTokenWithoutARegistrationIsForbidden() {
        String unregistered = this.jwt.integration("00000000-0000-4000-8000-0000000000e1");

        assertEquals(403, post(PATH, Map.of("detections", List.of(fresh(item()))), unregistered).status());
    }

    @Test
    void anIntegrationTokenCannotReadStaffRoutes() {
        assertEquals(403, get("/case", this.jwt.integration(CLIENT)).status());
    }

    @Test
    void aBatchOfFiftyOneIsRefusedWith413() {
        ensureVulcanRegistered();
        List<Map<String, Object>> items = java.util.stream.IntStream.range(0, 51).mapToObj(i -> fresh(item())).toList();

        Reply reply = post(PATH, Map.of("detections", items), this.jwt.integration(CLIENT));

        assertEquals(413, reply.status(), reply.body());
    }

    @Test
    void aPartialBatchAnswersEachItemInRequestOrder() {
        Map<String, Object> valid = fresh(item());
        Map<String, Object> invalid = withPayload(fresh(item()), "check", null);

        List<Map<String, Object>> answers = results(submit(List.of(valid, invalid, valid)));

        assertEquals(List.of("ACCEPTED", "REJECTED", "DUPLICATE"), answers.stream().map(a -> a.get("status")).toList());
        assertEquals("SCHEMA_INVALID", answers.get(1).get("reason"));
        assertNull(answers.get(0).get("reason"));
    }

    @Test
    void aSourceSystemInTheBodyThatDiffersIsSourceMismatch() {
        Map<String, Object> other = with(fresh(item()), "sourceSystem", "grim");

        Map<String, Object> answer = results(submit(List.of(other))).get(0);

        assertEquals("REJECTED", answer.get("status"));
        assertEquals("SOURCE_MISMATCH", answer.get("reason"));
    }

    @Test
    void theServerLabelMustBeInScopeOfTheRegistration() {
        Map<String, Object> lobby = fresh(item());
        Map<String, Object> survival = with(fresh(item()), "server", "Survival-1");
        Map<String, Object> lookalike = with(fresh(item()), "server", "Lobbyx-2");

        List<Map<String, Object>> answers = results(submit(List.of(lobby, survival, lookalike)));

        assertEquals("ACCEPTED", answers.get(0).get("status"), "Lobby-2 is a service of task Lobby");
        assertEquals("SERVER_OUT_OF_SCOPE", answers.get(1).get("reason"));
        assertEquals("SERVER_OUT_OF_SCOPE", answers.get(2).get("reason"));
    }

    @Test
    void aPlayerUuidInThePayloadIsPiiInPayload() {
        Map<String, Object> detection = withPayload(fresh(item()), "detail", "player " + UUID.randomUUID() + " flagged");

        Map<String, Object> answer = results(submit(List.of(detection))).get(0);

        assertEquals("PII_IN_PAYLOAD", answer.get("reason"));
    }

    @Test
    void optionalFieldsSentAsNullAreAccepted() {
        Map<String, Object> detection = fresh(item());
        detection = withPayload(detection, "checkType", null);
        detection = withPayload(detection, "violationLevel", null);
        detection = withPayload(detection, "ping", null);
        detection = withPayload(detection, "tps", null);
        detection = withPayload(detection, "experimental", null);
        detection = withPayload(detection, "detail", null);

        Map<String, Object> answer = results(submit(List.of(detection))).get(0);

        assertTrue("ACCEPTED".equals(answer.get("status")), "a payload with only check and nulls is accepted: " + answer);
    }
}
