package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class ActivePunishmentApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void anEmptyNetworkAnswersAnEmptyPage() {
        Reply reply = get("/punishment/active", this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> body = reply.json();
        assertTrue(((List<?>) body.get("items")).isEmpty(), reply.body());
        assertEquals(0, ((Number) body.get("totalSize")).intValue());
    }

    @Test
    void everyFilterAndSortIsAccepted() {
        Reply reply = get("/punishment/active?kind=BAN&section=network&targetKind=PATTERN&permanent=false&expiresBefore=9999999999999"
                + "&automatic=true&actor=" + UUID.randomUUID() + "&sort=EXPIRES&direction=ASC&page=0&size=10", this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
    }

    @Test
    void aNegativeExpiryBoundIsABadRequest() {
        assertEquals(400, get("/punishment/active?expiresBefore=-5", this.jwt.viewer()).status());
    }

    @Test
    void theProxyIsDeniedAndAnAnonymousCallIsUnauthorized() {
        assertEquals(403, get("/punishment/active", this.jwt.proxy()).status());
        assertEquals(401, get("/punishment/active", null).status());
    }
}
