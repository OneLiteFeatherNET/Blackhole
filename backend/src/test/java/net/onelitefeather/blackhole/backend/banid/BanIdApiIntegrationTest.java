package net.onelitefeather.blackhole.backend.banid;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class BanIdApiIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    private String applyPunishment(String owner, String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "r", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", key, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    @Test
    void aNewPunishmentHasABanIdThatTheRevealReturnsAndNoOtherResponseHolds() {
        String identifier = applyPunishment("d1".repeat(64), "ban-id-reveal");

        Reply reveal = get("/punishment/" + identifier + "/ban-id");

        assertEquals(200, reveal.status(), reveal.body());
        String banId = (String) reveal.json().get("banId");
        assertTrue(BanIdCodec.normalize(banId).filter(banId::equals).isPresent(), "eight alphabet characters: " + banId.length());
        for (String path : List.of("/punishment", "/punishment/short/" + PunishmentShortId.keyOf(identifier).orElseThrow(), "/case/" + identifier,
                "/case/" + identifier + "/timeline", "/profile/" + "d1".repeat(64))) {
            assertFalse(get(path).body().contains(banId), "not in " + path);
        }
    }

    @Test
    void theRevealIsNotCacheable() {
        String identifier = applyPunishment("d2".repeat(64), "ban-id-nostore");

        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            HttpResponse<String> response = client.toBlocking().exchange(
                    HttpRequest.GET("/punishment/" + identifier + "/ban-id").bearerAuth(this.jwt.proxy()), String.class);

            assertEquals(200, response.code());
            assertEquals("no-store", response.header("Cache-Control"));
        }
    }

    @Test
    void moderatorAndProxyMayRevealButViewerAndOthersMayNot() {
        String identifier = applyPunishment("d3".repeat(64), "ban-id-roles");
        String path = "/punishment/" + identifier + "/ban-id";

        assertEquals(200, get(path, this.jwt.moderator()).status(), "moderator");
        assertEquals(200, get(path, this.jwt.proxy()).status(), "proxy");
        assertEquals(200, get(path, this.jwt.admin()).status(), "admin");
        assertEquals(403, get(path, this.jwt.viewer()).status(), "viewer");
        assertEquals(403, get(path, this.jwt.metrics()).status(), "metrics");
        assertEquals(401, get(path, null).status(), "no token");
    }

    @Test
    void anUnknownPunishmentIsNotFound() {
        assertEquals(404, get("/punishment/does-not-exist/ban-id").status());
    }

    @Test
    void staffFindThePunishmentByItsBanIdInTheBodyOfTheLookup() {
        String identifier = applyPunishment("d4".repeat(64), "ban-id-lookup");
        String banId = (String) get("/punishment/" + identifier + "/ban-id").json().get("banId");

        for (String typed : List.of(banId, "#" + banId.toLowerCase())) {
            Reply found = post("/punishment/lookup", Map.of("banId", typed), this.jwt.moderator());

            assertEquals(200, found.status(), found.body());
            assertEquals(identifier, ((Map<?, ?>) found.json().get("punishment")).get("identifier"));
            assertFalse(found.body().contains(banId), "the Ban ID is not echoed");
        }
    }

    @Test
    void theLookupAnswersEveryMissAsOneUniformNotFoundThatNeverEchoesTheInput() {
        String identifier = applyPunishment("d6".repeat(64), "ban-id-miss");
        String banId = (String) get("/punishment/" + identifier + "/ban-id").json().get("banId");
        String unknown = banId.equals("ZZZZZZZZ") ? "YYYYYYYY" : "ZZZZZZZZ";

        Reply unknownId = post("/punishment/lookup", Map.of("banId", unknown), this.jwt.proxy());
        Reply malformed = post("/punishment/lookup", Map.of("banId", "not-a-ban-id"), this.jwt.proxy());
        Reply empty = post("/punishment/lookup", Map.of(), this.jwt.proxy());

        for (Reply miss : List.of(unknownId, malformed, empty)) {
            assertEquals(404, miss.status(), miss.body());
            assertEquals(unknownId.body(), miss.body(), "one body for every miss");
        }
        assertFalse(unknownId.body().contains(unknown) || malformed.body().contains("not-a-ban-id"), "no echo");
    }

    @Test
    void theLookupIsNotCacheableAndNeedsModeratorOrProxy() {
        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            io.micronaut.http.client.exceptions.HttpClientResponseException miss = org.junit.jupiter.api.Assertions.assertThrows(
                    io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                    () -> client.toBlocking().exchange(HttpRequest.POST("/punishment/lookup", Map.of("banId", "ZZZZZZZZ"))
                            .bearerAuth(this.jwt.moderator()), String.class));

            assertEquals(404, miss.getStatus().getCode());
            assertEquals("no-store", miss.getResponse().header("Cache-Control"));
        }
        assertEquals(403, post("/punishment/lookup", Map.of("banId", "ZZZZZZZZ"), this.jwt.viewer()).status(), "viewer");
        assertEquals(401, post("/punishment/lookup", Map.of("banId", "ZZZZZZZZ"), null).status(), "no token");
    }

    @Test
    void theShortIdRouteDoesNotAcceptABanId() {
        String identifier = applyPunishment("d7".repeat(64), "ban-id-url");
        String banId = (String) get("/punishment/" + identifier + "/ban-id").json().get("banId");

        Reply found = get("/punishment/short/%23" + banId);

        assertFalse(found.body().contains(identifier), "no punishment is found by a Ban ID in the path: " + found.status());
    }

    @Test
    void aNoteWithTheBanIdIsRejected() {
        String identifier = applyPunishment("d5".repeat(64), "ban-id-note");
        String banId = (String) get("/punishment/" + identifier + "/ban-id").json().get("banId");

        Reply note = post("/case/" + identifier + "/notes", Map.of("text", "player quoted #" + banId), this.jwt.moderator());

        assertEquals(400, note.status(), note.body());
        assertNotNull(note.json().get("errorMessage"));
        assertFalse(note.body().contains(banId), "the error does not echo it");
    }
}
