package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /health} is public, {@code /prometheus} needs METRICS, documentation is off by default. */
@Tag("integration")
class OperationalRoutesIntegrationTest extends ApiIntegrationTest {

    @Test
    void healthIsPublicAndShowsTheStatusOnly() {
        Reply reply = get("/health", null);

        assertEquals(200, reply.status(), reply.body());
        assertEquals("UP", reply.json().get("status"));
        assertFalse(reply.body().contains("details"), "no component details: " + reply.body());
    }

    @Test
    void prometheusWithoutATokenIsUnauthorized() {
        assertEquals(401, get("/prometheus", null).status(), "anonymous scrape");
    }

    @Test
    void prometheusForAViewerIsForbidden() {
        assertEquals(403, get("/prometheus", this.jwt.admin()).status(), "even an admin without the metrics role");
    }

    @Test
    void prometheusForAMachineMetricsTokenIsServed() {
        assertEquals(200, get("/prometheus", this.jwt.metrics()).status(), "scraper with Blackhole.Metrics");
    }

    @Test
    void prometheusForAMetricsGroupMemberIsServed() {
        String token = this.jwt.delegated().groups(TestJwtIssuer.METRICS_GROUP).sign();

        assertEquals(200, get("/prometheus", token).status(), "user in the metrics group");
    }

    @Test
    void otherManagementEndpointsAreNotServed() {
        for (String path : new String[] {"/beans", "/routes", "/threaddump", "/flyway", "/info", "/metrics"}) {
            int status = get(path, this.jwt.admin()).status();
            assertTrue(status == 404 || status == 403, path + " must not be served, got " + status);
        }
    }

    @Test
    void swaggerIsNotServedWhenDocumentationIsDisabled() {
        assertTrue(get("/swagger/blackhole-api-0.0.14.yml", this.jwt.admin()).status() >= 400, "documentation off by default");
        assertTrue(get("/swagger/blackhole-api-0.0.14.yml", null).status() >= 400, "and never anonymous");
    }
}
