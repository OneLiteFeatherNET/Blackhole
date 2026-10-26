package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversation, leak and export routes over HTTP with self-signed tokens, against the real application and database. */
@Tag("integration")
class CaseConversationApiIntegrationTest extends ApiIntegrationTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000011");
    private static final UUID BOB = UUID.fromString("b0b00000-0000-4000-8000-000000000012");
    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    private String moderator(UUID oid) {
        return named(this.jwt.delegated().groups(TestJwtIssuer.MODERATOR_GROUP).claim("oid", oid.toString()).claim("name", "Staff " + oid.toString().substring(0, 4)).sign());
    }

    private String admin(UUID oid) {
        return named(this.jwt.delegated().groups(TestJwtIssuer.ADMIN_GROUP).claim("oid", oid.toString()).sign());
    }

    private String applyPunishment(String owner, String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "r", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", key, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    private String banId(String punishment) {
        return (String) get("/punishment/" + punishment + "/ban-id", named(this.jwt.moderator())).json().get("banId");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> timeline(String punishment) {
        return (List<Map<String, Object>>) get("/case/" + punishment + "/timeline").json().get("items");
    }

    private String say(String punishment, String text, String token) {
        Reply reply = post("/case/" + punishment + "/messages", Map.of("text", text), token);
        assertEquals(200, reply.status(), reply.body());
        return (String) reply.json().get("messageId");
    }

    @Test
    void aModeratorRepliesAndTheTimelineShowsAPlayerVisibleStaffMessageFromTheVerifiedActor() {
        String id = applyPunishment("e1".repeat(64), "conv-reply");

        say(id, "Please describe what happened.", moderator(ALICE));

        Map<String, Object> item = timeline(id).stream().filter(entry -> entry.get("type").equals("message.added")).findFirst().orElseThrow();
        assertEquals("PLAYER", item.get("visibility"));
        @SuppressWarnings("unchecked") Map<String, Object> actor = (Map<String, Object>) item.get("actor");
        assertEquals("VERIFIED", actor.get("trust"));
        assertEquals(ALICE.toString(), actor.get("oid"));
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) item.get("payload");
        assertEquals("PANEL", payload.get("channel"));
        assertEquals("Please describe what happened.", payload.get("text"));
    }

    @Test
    void aBodyThatNamesAVisibilityAnAuthorOrAChannelCannotChooseThem() {
        String id = applyPunishment("e2".repeat(64), "conv-visibility");

        Reply reply = post("/case/" + id + "/messages",
                Map.of("text", "hello", "visibility", "STAFF", "channel", "DISCORD", "author", Map.of("kind", "PLAYER")), moderator(ALICE));

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> item = timeline(id).stream().filter(entry -> entry.get("type").equals("message.added")).findFirst().orElseThrow();
        assertEquals("PLAYER", item.get("visibility"));
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) item.get("payload");
        assertEquals("PANEL", payload.get("channel"));
        assertEquals("STAFF", ((Map<?, ?>) payload.get("author")).get("kind"));
    }

    @Test
    void theSameIdempotencyKeyPostsOnceOverHttp() {
        String id = applyPunishment("e3".repeat(64), "conv-idem");
        String token = moderator(ALICE);

        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            for (int i = 0; i < 2; i++) {
                HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.POST("/case/" + id + "/messages", Map.of("text", "once"))
                        .bearerAuth(token).header("Idempotency-Key", "retry-1"), String.class);
                assertEquals(200, response.code());
            }
        }

        assertEquals(1, timeline(id).stream().filter(entry -> entry.get("type").equals("message.added")).count());
    }

    @Test
    void aMessageWithAnIpIs400() {
        String id = applyPunishment("e4".repeat(64), "conv-ip");

        assertEquals(400, post("/case/" + id + "/messages", Map.of("text", "see 10.0.0.9"), moderator(ALICE)).status());
    }

    @Test
    void aMessageWithTheBanIdIs400() {
        String id = applyPunishment("e5".repeat(64), "conv-banid-text");
        String banId = banId(id);

        assertEquals(400, post("/case/" + id + "/messages", Map.of("text", "your id is #" + banId), moderator(ALICE)).status());
    }

    @Test
    void theAuthorEditsAndDeletesAndAnotherModeratorCannot() {
        String id = applyPunishment("e6".repeat(64), "conv-edit");
        String messageId = say(id, "first", moderator(ALICE));

        assertEquals(403, put("/case/" + id + "/messages/" + messageId, Map.of("text", "hijack"), moderator(BOB)).status());
        assertEquals(200, put("/case/" + id + "/messages/" + messageId, Map.of("text", "second"), moderator(ALICE)).status());
        assertEquals(200, delete("/case/" + id + "/messages/" + messageId, moderator(ALICE)).status());
        assertEquals(409, put("/case/" + id + "/messages/" + messageId, Map.of("text", "third"), moderator(ALICE)).status());

        assertEquals(List.of("message.added", "message.edited", "message.deleted"),
                timeline(id).stream().map(entry -> (String) entry.get("type")).filter(type -> type.startsWith("message.")).toList());
    }

    @Test
    void anAdminRedactsAMessageAndTheTimelineKeepsOnlyTheStructure() {
        String id = applyPunishment("e7".repeat(64), "conv-redact");
        String messageId = say(id, "very private words", moderator(ALICE));

        assertEquals(403, post("/case/" + id + "/messages/" + messageId + "/redact", Map.of("reason", "PERSONAL_DATA"), moderator(ALICE)).status());
        Reply redacted = post("/case/" + id + "/messages/" + messageId + "/redact", Map.of("reason", "PERSONAL_DATA"), admin(BOB));

        assertEquals(200, redacted.status(), redacted.body());
        assertFalse(get("/case/" + id + "/timeline").body().contains("very private"), "no text in the timeline");
        Map<String, Object> added = timeline(id).stream().filter(entry -> entry.get("type").equals("message.added")).findFirst().orElseThrow();
        assertTrue(added.get("redactedAt") != null);
        assertEquals("PLAYER", added.get("visibility"));
        assertTrue(timeline(id).stream().anyMatch(entry -> entry.get("type").equals("message.redacted")));
    }

    @Test
    void erasingThePlayerSideNeedsAnAdmin() {
        String id = applyPunishment("e8".repeat(64), "conv-erase");

        assertEquals(403, post("/case/" + id + "/transcript/erase-player", Map.of("reason", "ERASURE_REQUEST"), moderator(ALICE)).status());
        Reply erased = post("/case/" + id + "/transcript/erase-player", Map.of("reason", "ERASURE_REQUEST"), admin(BOB));

        assertEquals(200, erased.status(), erased.body());
        assertEquals(0, ((Number) erased.json().get("redactedMessages")).intValue());
    }

    @Test
    void markingALeakShowsOnTheCaseAndAViewerCannot() {
        String id = applyPunishment("e9".repeat(64), "conv-leak");

        assertEquals(403, post("/case/" + id + "/ban-id/leak", Map.of("reason", "SHARED"), this.jwt.viewer()).status());
        Reply first = post("/case/" + id + "/ban-id/leak", Map.of("reason", "POSTED_PUBLIC"), moderator(ALICE));
        Reply second = post("/case/" + id + "/ban-id/leak", Map.of("reason", "POSTED_PUBLIC"), moderator(ALICE));

        assertEquals(true, first.json().get("changed"));
        assertEquals(false, second.json().get("changed"));
        Map<String, Object> view = get("/case/" + id).json();
        assertTrue(view.get("banIdLeakedAt") != null);
        assertEquals("POSTED_PUBLIC", view.get("banIdLeakReason"));
        assertEquals(1, timeline(id).stream().filter(entry -> entry.get("type").equals("ban_id.leak_marked")).count());
    }

    @Test
    void aReissueNeedsAnAdminKillsTheOldBanIdClearsTheLeakAndRevealsTheNewOne() {
        String id = applyPunishment("f1".repeat(64), "conv-reissue");
        String oldBanId = banId(id);
        post("/case/" + id + "/ban-id/leak", Map.of("reason", "SHARED"), moderator(ALICE));

        assertEquals(403, post("/case/" + id + "/ban-id/reissue", Map.of("reason", "OTHER"), moderator(ALICE)).status());
        Reply reissued = post("/case/" + id + "/ban-id/reissue", Map.of("reason", "NOT_PLAYERS_FAULT"), admin(BOB));

        assertEquals(200, reissued.status(), reissued.body());
        assertEquals(2, ((Number) reissued.json().get("generation")).intValue());
        assertFalse(reissued.body().contains(oldBanId));
        String newBanId = banId(id);
        assertNotEquals(oldBanId, newBanId);
        assertEquals(404, post("/punishment/lookup", Map.of("banId", oldBanId), named(this.jwt.moderator())).status(), "the old Ban ID is dead");
        assertEquals(200, post("/punishment/lookup", Map.of("banId", newBanId), named(this.jwt.moderator())).status());
        assertTrue(get("/case/" + id).json().get("banIdLeakedAt") == null, "the leak is cleared");
        assertFalse(get("/case/" + id + "/timeline").body().contains(newBanId));
        assertFalse(get("/case/" + id + "/timeline").body().contains(oldBanId));
    }

    @Test
    void anAdminExportsAnAttachmentThatIsNeverCachedAndTheExportIsAudited() {
        String id = applyPunishment("f2".repeat(64), "conv-export");
        say(id, "exported words", moderator(ALICE));

        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            HttpResponse<String> response = client.toBlocking().exchange(
                    HttpRequest.GET("/case/" + id + "/transcript?format=json").bearerAuth(admin(BOB)), String.class);

            assertEquals(200, response.code());
            assertEquals("no-store", response.header("Cache-Control"));
            assertTrue(response.header("Content-Disposition").startsWith("attachment; filename=\"transcript-"), response.header("Content-Disposition"));
            assertTrue(response.body().contains("exported words"));
            assertTrue(response.header("Content-Type").startsWith("application/json"));
        }
        assertEquals(1, timeline(id).stream().filter(entry -> entry.get("type").equals("transcript.exported")).count());
        Map<String, Object> audit = timeline(id).stream().filter(entry -> entry.get("type").equals("transcript.exported")).findFirst().orElseThrow();
        assertFalse(audit.get("payload").toString().contains("exported words"), "the audit event holds no content");
        assertEquals(2, ((Number) ((Map<?, ?>) audit.get("payload")).get("entries")).intValue(), "the applied marker and the message");
    }

    @Test
    void theTextExportIsAnAttachmentToo() {
        String id = applyPunishment("f3".repeat(64), "conv-export-text");
        say(id, "plain words", moderator(ALICE));

        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            HttpResponse<String> response = client.toBlocking().exchange(
                    HttpRequest.GET("/case/" + id + "/transcript?format=text").bearerAuth(admin(BOB)), String.class);

            assertTrue(response.header("Content-Type").startsWith("text/plain"));
            assertTrue(response.header("Content-Disposition").endsWith(".txt\""));
            assertTrue(response.body().contains("plain words"));
        }
    }

    @Test
    void aModeratorCannotExportAndTheProxyCannotDoAnything() {
        String id = applyPunishment("f4".repeat(64), "conv-denied");

        assertEquals(403, get("/case/" + id + "/transcript", moderator(ALICE)).status());
        assertEquals(403, post("/case/" + id + "/messages", Map.of("text", "x"), this.jwt.proxy()).status());
        assertEquals(403, post("/case/" + id + "/ban-id/leak", Map.of("reason", "OTHER"), this.jwt.proxy()).status());
        assertEquals(403, get("/case/" + id + "/transcript", this.jwt.proxy()).status());
    }

    @Test
    void anUnknownExportFormatIs400() {
        String id = applyPunishment("f5".repeat(64), "conv-format");

        assertEquals(400, get("/case/" + id + "/transcript?format=pdf").status());
    }

    @Test
    void aViewerCannotPostEditDeleteOrRedact() {
        String id = applyPunishment("f6".repeat(64), "conv-viewer");
        String messageId = say(id, "mine", moderator(ALICE));
        String viewer = this.jwt.viewer();

        assertEquals(403, post("/case/" + id + "/messages", Map.of("text", "x"), viewer).status());
        assertEquals(403, put("/case/" + id + "/messages/" + messageId, Map.of("text", "x"), viewer).status());
        assertEquals(403, delete("/case/" + id + "/messages/" + messageId, viewer).status());
        assertEquals(403, post("/case/" + id + "/messages/" + messageId + "/redact", Map.of("reason", "OTHER"), viewer).status());
    }
}
