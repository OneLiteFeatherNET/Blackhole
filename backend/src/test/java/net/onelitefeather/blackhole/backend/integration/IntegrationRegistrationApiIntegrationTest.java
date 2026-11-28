package net.onelitefeather.blackhole.backend.integration;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The integration registry routes against real PostgreSQL. Source systems and client ids are unique per test
 * because the database lives for the whole class.
 */
@Tag("integration")
class IntegrationRegistrationApiIntegrationTest extends ApiIntegrationTest {

    /** A client id no other test uses: the database is shared by the whole class. */
    private static String clientId() {
        return UUID.randomUUID().toString();
    }

    /** A source system no other test uses. */
    private static String sourceSystem(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> registration(String sourceSystem, String clientId, List<String> tasks, List<String> statics) {
        Map<String, Object> body = new HashMap<>();
        body.put("sourceSystem", sourceSystem);
        body.put("displayName", "Bridge " + sourceSystem);
        body.put("entraClientId", clientId);
        body.put("allowedKinds", List.of("DETECTION:1"));
        body.put("allowedTasks", tasks);
        body.put("allowedStaticServers", statics);
        return body;
    }

    @Test
    void anAdministratorRegistersAnIntegrationAndReadsItBack() {
        String source = sourceSystem("api-vulcan");
        Reply created = post("/integration/registrations", registration(source, clientId(), List.of("Lobby"), List.of()));

        assertEquals(200, created.status(), created.body());
        Map<String, Object> read = created.json();
        assertEquals(source, read.get("sourceSystem"));
        assertEquals(true, read.get("enabled"));
        assertEquals(List.of("Lobby"), read.get("allowedTasks"));
        assertTrue(!created.body().contains("secret"), "no secret is part of the registration");
    }

    @Test
    void aDuplicateSourceSystemOrClientIdIsAConflict() {
        String source = sourceSystem("api-dup");
        String client = clientId();
        post("/integration/registrations", registration(source, client, List.of("Lobby"), List.of()));

        assertEquals(409, post("/integration/registrations", registration(source, clientId(), List.of("Lobby"), List.of())).status(),
                "the same source system with another client id");
        assertEquals(409, post("/integration/registrations", registration(sourceSystem("api-dup2"), client, List.of("Lobby"), List.of())).status(),
                "the same client id with another source system");
    }

    @Test
    void anEmptyScopeIsRejectedAndNothingIsStored() {
        assertEquals(400, post("/integration/registrations", registration(sourceSystem("api-empty"), clientId(), List.of(), List.of())).status());
        assertTrue(!get("/integration/registrations").body().contains("api-empty"), "a rejected registration is not listed");
    }

    @Test
    void disablingARegistrationIsAPatchThatKeepsItsScope() {
        long id = ((Number) post("/integration/registrations", registration(sourceSystem("api-patch"), clientId(), List.of("Lobby"), List.of())).json()
                .get("id")).longValue();
        Map<String, Object> update = new HashMap<>();
        update.put("displayName", "Bridge api-patch");
        update.put("enabled", false);
        update.put("allowedKinds", List.of("DETECTION:1"));
        update.put("allowedTasks", List.of("Lobby"));
        update.put("allowedStaticServers", List.of());

        Reply patched = patch("/integration/registrations/" + id, update, this.jwt.admin());

        assertEquals(200, patched.status(), patched.body());
        assertEquals(false, patched.json().get("enabled"));
        assertEquals(List.of("Lobby"), patched.json().get("allowedTasks"));
    }

    @Test
    void aMissingRegistrationIsNotFound() {
        assertEquals(404, get("/integration/registrations/987654").status());
    }

    @Test
    void aModeratorCannotRegisterAnIntegration() {
        assertEquals(403, post("/integration/registrations", registration(sourceSystem("api-mod"), clientId(), List.of("Lobby"), List.of()),
                this.jwt.moderator()).status());
    }

    @Test
    void anIntegrationTokenCannotReadOrWriteTheRegistry() {
        String integration = this.jwt.integration(TestJwtIssuer.INTEGRATION_CLIENT);

        assertEquals(403, post("/integration/registrations", registration(sourceSystem("api-self"), clientId(), List.of("Lobby"), List.of()),
                integration).status());
        assertEquals(403, get("/integration/registrations", integration).status());
    }

    @Test
    void aCallWithoutAnyTokenIsUnauthorised() {
        assertEquals(401, post("/integration/registrations", registration(sourceSystem("api-anon"), clientId(), List.of("Lobby"), List.of()), null)
                .status());
        assertEquals(401, get("/integration/registrations", null).status());
    }
}
