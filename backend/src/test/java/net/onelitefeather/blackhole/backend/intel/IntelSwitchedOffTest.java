package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With blackhole.intel.enabled false the proxy's reports get 404 and nothing is stored. */
@Tag("integration")
class IntelSwitchedOffTest extends ApiIntegrationTest {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.intel.enabled", false);
    }

    @Test
    void aSessionReportAnswersNotFound() {
        Reply reply = post("/intel/sessions", Map.of("sessionId", UUID.randomUUID().toString(), "owner", "d".repeat(128),
                "playerUuid", UUID.randomUUID().toString(), "playerName", "Steve", "ip", "192.0.2.10", "outcome", "ALLOWED"),
                jwt.proxy());

        assertEquals(404, reply.status());
    }
}
