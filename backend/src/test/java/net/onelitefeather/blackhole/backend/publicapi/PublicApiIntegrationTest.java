package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public routes over HTTP, against the real application and database, with a real player session token. */
@Tag("integration")
class PublicApiIntegrationTest extends PublicApiTestBase {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Raw page) {
        return (List<Map<String, Object>>) page.json().get("entries");
    }

    // --- login and session ---

    @Test
    void aGoodBanIdLogsInWithTokenCsrfExpiryAndScope() {
        String punishment = punish("a1".repeat(64), "pub-login");

        Raw login = login(banIdOf(punishment));

        assertEquals(200, login.status(), login.body());
        assertEquals("no-store", login.header("Cache-Control"));
        assertEquals(Set.of("token", "csrf", "expiresAt", "scope"), login.json().keySet());
        assertEquals("FULL", login.json().get("scope"));
        assertTrue(((String) login.json().get("expiresAt")).endsWith("Z"), "an ISO instant");
    }

    @Test
    void everyFailedLoginIsByteIdentical() {
        String punishment = punish("a2".repeat(64), "pub-uniform");
        String good = banIdOf(punishment);
        Raw unknown = login("ZZZZZZZZ");
        Raw tooShort = login("ABC");
        Raw legacyHex = login(punishment.substring(0, 8));
        Raw nothing = call(HttpMethod.POST, "/public/session", null, Map.of());
        Raw brokenJson = call(HttpMethod.POST, "/public/session", null, "{\"banId\": \"" + good.substring(0, 4));
        Raw wrongType = call(HttpMethod.POST, "/public/session", null, Map.of("banId", 12345678));

        for (Raw failure : List.of(unknown, tooShort, legacyHex, nothing, brokenJson, wrongType)) {
            assertEquals(401, failure.status(), failure.body());
            assertEquals(unknown.body(), failure.body(), "the same body");
            assertEquals("no-store", failure.header("Cache-Control"));
        }
        assertEquals("INVALID_BAN_ID", unknown.json().get("code"));
        assertFalse(unknown.body().contains(good));
    }

    @Test
    void theSessionRouteAnswersCsrfExpiryAndScopeAndLogoutEndsIt() {
        String token = sessionFor(punish("a3".repeat(64), "pub-session"));

        Raw session = playerGet("/public/session", token);
        assertEquals(200, session.status(), session.body());
        assertEquals(Set.of("csrf", "expiresAt", "scope"), session.json().keySet());

        Raw loggedOut = call(HttpMethod.DELETE, "/public/session", token, null);
        assertEquals(204, loggedOut.status());
        assertEquals(401, playerGet("/public/session", token).status(), "the token no longer works");
    }

    @Test
    void everyRouteWithoutAGoodSessionIsA401() {
        sessionFor(punish("a4".repeat(64), "pub-401"));

        for (String path : List.of("/public/session", "/public/punishment", "/public/appeal", "/public/conversation")) {
            assertEquals(401, playerGet(path, null).status(), "no token: " + path);
            assertEquals(401, playerGet(path, "bhps_" + "A".repeat(43)).status(), "unknown token: " + path);
        }
        assertEquals(401, playerPost("/public/appeal", null, Map.of("statement", "x")).status());
        assertEquals(401, playerPost("/public/messages", "bhps_" + "A".repeat(43), Map.of("text", "x")).status());
        assertEquals(401, call(HttpMethod.DELETE, "/public/session", null, null).status());
    }

    // --- facts ---

    @Test
    void theFactsAreTheAllowListAndNothingElse() {
        String punishment = punish("a5".repeat(64), "pub-facts");
        String token = sessionFor(punishment);

        Raw facts = playerGet("/public/punishment", token);

        assertEquals(200, facts.status(), facts.body());
        assertEquals(Set.of("reference", "type", "reason", "section", "createdAt", "permanent", "expiresAt", "state", "evidenceCount", "appeal"),
                facts.json().keySet());
        assertEquals(punishment, facts.json().get("reference"));
        assertEquals("Spamming the chat", facts.json().get("reason"));
        assertEquals("ACTIVE", facts.json().get("state"));
        assertNotNull(facts.json().get("expiresAt"));
        assertEquals(0, ((Number) facts.json().get("evidenceCount")).intValue());
        assertEquals("AVAILABLE", appealState(facts));
        for (String forbidden : List.of("a5".repeat(64), "short", "owner", "source", "oid", "elo", "note")) {
            assertFalse(facts.body().toLowerCase().contains(forbidden.toLowerCase()), "no '" + forbidden + "' in the facts: " + facts.body());
        }
    }

    @SuppressWarnings("unchecked")
    private static String appealState(Raw facts) {
        return (String) ((Map<String, Object>) facts.json().get("appeal")).get("state");
    }

    @Test
    void aSessionOnlyEverSeesItsOwnPunishmentWhateverItAsksFor() {
        String mine = punish("a6".repeat(64), "pub-own-1");
        String yours = punish("a7".repeat(64), "pub-own-2");
        String token = sessionFor(mine);

        Raw facts = call(HttpMethod.GET, "/public/punishment?punishment=" + yours + "&appeal=" + yours + "&identifier=" + yours, token, null);

        assertEquals(mine, facts.json().get("reference"), "a parameter naming another punishment is ignored");
        int byPath = call(HttpMethod.GET, "/public/punishment/" + yours, token, null).status();
        assertTrue(byPath == 403 || byPath == 404, "no route takes an identifier, got " + byPath);
    }

    // --- appeal ---

    @Test
    void filingRecordsAnEligibleAppealAndTheSecondOneIsRefusedWhileItIsOpen() {
        String punishment = punish("b1".repeat(64), "pub-file");
        String token = sessionFor(punishment);

        Raw filed = playerPost("/public/appeal", token, Map.of("conditionsVersion", 1, "statement", "I was not the one writing in the chat."));
        Raw second = playerPost("/public/appeal", token, Map.of("conditionsVersion", 1, "statement", "Please look again."));

        assertEquals(200, filed.status(), filed.body());
        assertEquals("ELIGIBLE_PENDING_REVIEW", filed.json().get("status"));
        assertNull(filed.json().get("decision"));
        assertEquals(409, second.status());
        assertEquals("NOT_ELIGIBLE", second.json().get("code"));
        assertEquals("OPEN", appealState(playerGet("/public/punishment", token)));
        assertEquals(1, appealsOf(punishment), "exactly one appeal exists");
        assertEquals(1, events().eventsOfType("appeal.submitted").size());
    }

    @Test
    void theStatementIsCheckedBeforeAnythingIsRecorded() {
        String token = sessionFor(punish("b3".repeat(64), "pub-statement"));

        assertEquals(400, playerPost("/public/appeal", token, Map.of("statement", "")).status());
        assertEquals(400, playerPost("/public/appeal", token, Map.of("statement", "x".repeat(2001))).status());
        assertEquals(400, playerPost("/public/appeal", token, Map.of("statement", "bell\u0007")).status());
        assertEquals(400, playerPost("/public/appeal", token, Map.of()).status());
        assertEquals(422, playerPost("/public/appeal", token, Map.of("statement", "my address is 203.0.113.9")).status());
        assertEquals(422, playerPost("/public/appeal", token,
                Map.of("statement", "my id is 123e4567-e89b-12d3-a456-426614174000")).status());
        assertEquals(409, playerGet("/public/appeal", token).status(), "nothing was recorded");
        assertEquals("NO_APPEAL", playerGet("/public/appeal", token).json().get("code"));
    }

    @Test
    void theAppealReadsBackWithTheDecisionAndNeverTheInternalNote() {
        String punishment = punish("b4".repeat(64), "pub-decision");
        String token = sessionFor(punishment);
        fileAppeal(token);
        String appealId = appealIdOf(punishment);

        Reply review = post("/appeal/" + appealId + "/review", Map.of("decision", "DENIED", "decisionNote", "INTERNAL-NOTE-XYZ",
                "playerMessage", "We looked again and the ban stays."), named(this.jwt.moderator()));
        assertEquals(200, review.status(), review.body());

        Raw appeal = playerGet("/public/appeal", token);
        assertEquals(200, appeal.status(), appeal.body());
        assertEquals("DENIED", appeal.json().get("status"));
        assertEquals("DENIED", appeal.json().get("decision"));
        assertEquals(Set.of("status", "decision", "newExpiresAt", "filedAt"), appeal.json().keySet());
        assertFalse(appeal.body().contains("INTERNAL-NOTE-XYZ"));
        Raw conversation = playerGet("/public/conversation", token);
        assertFalse(conversation.body().contains("INTERNAL-NOTE-XYZ"));
        assertTrue(conversation.body().contains("We looked again and the ban stays."), "the team's message reaches the player");
        assertEquals("COOLDOWN", appealState(playerGet("/public/punishment", token)));
        Raw again = playerPost("/public/appeal", token, Map.of("conditionsVersion", 1, "statement", "Another try"));
        assertEquals(409, again.status());
        assertEquals("NOT_ELIGIBLE", again.json().get("code"));
        assertNotNull(again.json().get("availableFrom"), "the date the cooldown ends");
    }

    @SuppressWarnings("unchecked")
    private long appealsOf(String punishment) {
        List<Map<String, Object>> all = (List<Map<String, Object>>) get("/appeal?size=100").json().get("content");
        return all.stream().filter(a -> punishment.equals(a.get("punishmentIdentifier"))).count();
    }

    @SuppressWarnings("unchecked")
    private String appealIdOf(String punishment) {
        List<Map<String, Object>> all = (List<Map<String, Object>>) get("/appeal?size=100").json().get("content");
        return (String) all.stream().filter(a -> punishment.equals(a.get("punishmentIdentifier"))).findFirst().orElseThrow().get("identifier");
    }

    // --- messages and conversation ---

    @Test
    void aMessageNeedsAnOpenAppeal() {
        String token = sessionFor(punish("c1".repeat(64), "pub-noappeal"));

        Raw posted = playerPost("/public/messages", token, Map.of("text", "hello"));

        assertEquals(409, posted.status());
        assertEquals("NO_APPEAL", posted.json().get("code"));
    }

    @Test
    void aPostedMessageReadsBackOnceAndInOrderAfterTheTeamsReply() {
        String punishment = punish("c2".repeat(64), "pub-thread");
        String token = sessionFor(punishment);
        fileAppeal(token);
        assertEquals(200, post("/case/" + punishment + "/messages", Map.of("text", "Please tell us more."), named(this.jwt.moderator())).status());

        Raw posted = playerPost("/public/messages", token, Map.of("text", "  It was a joke between friends.  "), "k-1");
        Raw repeated = playerPost("/public/messages", token, Map.of("text", "  It was a joke between friends.  "), "k-1");

        assertEquals(200, posted.status(), posted.body());
        assertEquals(false, posted.json().get("repeated"));
        assertEquals(true, repeated.json().get("repeated"));
        assertEquals(posted.json().get("messageId"), repeated.json().get("messageId"));
        Raw page = playerGet("/public/conversation", token);
        List<Map<String, Object>> entries = entries(page);
        assertEquals(List.of("APPEAL_FILED", "MESSAGE", "MESSAGE"), entries.stream().map(e -> e.get("kind")).toList());
        assertEquals(List.of("SYSTEM", "STAFF", "PLAYER"), entries.stream().map(e -> e.get("author")).toList());
        assertEquals("Please tell us more.", entries.get(1).get("text"));
        assertEquals("It was a joke between friends.", entries.get(2).get("text"), "trimmed");
        assertEquals(Set.of("seq", "occurredAt", "kind", "author", "messageId", "text", "blocks", "removed", "attachments", "share", "staff"),
                entries.get(1).keySet());
        assertFalse(page.body().contains("Staff"), "no staff names");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMarkdownMessageComesBackAsItsSourceAndASanitisedTree() {
        String punishment = punish("c9".repeat(64), "pub-markdown");
        String token = sessionFor(punishment);
        fileAppeal(token);
        String source = "**bold** [home](https://appeal.onelitefeather.net/a) [bad](javascript:alert(1)) <script>alert(1)</script>";

        assertEquals(200, playerPost("/public/messages", token, Map.of("text", source), "k-md").status());

        List<Map<String, Object>> entries = entries(playerGet("/public/conversation", token));
        Map<String, Object> message = entries.get(entries.size() - 1);
        assertEquals(source, message.get("text"), "the source is returned as written");
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) message.get("blocks");
        assertEquals("paragraph", blocks.getFirst().get("type"));
        List<Map<String, Object>> inline = (List<Map<String, Object>>) blocks.getFirst().get("children");
        assertEquals("strong", inline.get(0).get("type"), "bold is a strong node");
        Map<String, Object> link = inline.get(2);
        assertEquals("link", link.get("type"));
        assertEquals("https://appeal.onelitefeather.net/a", link.get("href"));
        assertEquals(false, link.get("external"), "a subdomain of the trusted domain is not external");
        assertEquals(1, inline.stream().filter(node -> "link".equals(node.get("type"))).count(), "the javascript link is text, not a link");
        assertFalse(blocks.toString().contains("type=html"), "there is no html node: " + blocks);
    }

    @Test
    void theConversationPagesBySeqAndAnEmptyPollKeepsTheCursor() {
        String punishment = punish("c3".repeat(64), "pub-cursor");
        String token = sessionFor(punishment);
        fileAppeal(token);
        Raw first = playerGet("/public/conversation", token);
        Number cursor = (Number) first.json().get("cursor");
        assertNotNull(cursor);

        Raw nothingNew = playerGet("/public/conversation?cursor=" + cursor, token);
        playerPost("/public/messages", token, Map.of("text", "one more thing"));
        Raw news = playerGet("/public/conversation?cursor=" + cursor, token);

        assertTrue(entries(nothingNew).isEmpty());
        assertEquals(cursor.longValue(), ((Number) nothingNew.json().get("cursor")).longValue());
        assertEquals(1, entries(news).size());
        assertEquals("one more thing", entries(news).getFirst().get("text"));
    }

    @Test
    void staffOnlyEntriesNeverReachThePlayer() {
        String punishment = punish("c4".repeat(64), "pub-staffonly");
        String token = sessionFor(punishment);
        fileAppeal(token);
        assertEquals(200, post("/case/" + punishment + "/notes", Map.of("text", "SECRET-STAFF-NOTE"), named(this.jwt.moderator())).status());

        Raw page = playerGet("/public/conversation", token);

        assertFalse(page.body().contains("SECRET-STAFF-NOTE"));
        assertEquals(List.of("APPEAL_FILED"), entries(page).stream().map(e -> e.get("kind")).toList());
    }

    @Test
    void aMessageIsCheckedForLengthControlCharactersAndPersonalData() {
        String token = sessionFor(punish("c5".repeat(64), "pub-checks"));
        fileAppeal(token);

        assertEquals(400, playerPost("/public/messages", token, Map.of("text", "x".repeat(2001))).status());
        assertEquals(400, playerPost("/public/messages", token, Map.of("text", "  ")).status());
        assertEquals(400, playerPost("/public/messages", token, Map.of("text", "a\u0000b")).status());
        assertEquals(200, playerPost("/public/messages", token, Map.of("text", "line one\nline two")).status());
        assertEquals(422, playerPost("/public/messages", token, Map.of("text", "I play from 198.51.100.7")).status());
        assertEquals(422, playerPost("/public/messages", token, Map.of("text", "see 123e4567-e89b-12d3-a456-426614174000")).status());
        assertEquals(422, playerPost("/public/messages", token, Map.of("text", "my code is #ZZZZZZZZ")).status());
        assertEquals(400, playerPost("/public/messages", token, Map.of("text", "ok"), "bad key with spaces").status());
        assertEquals(2, entries(playerGet("/public/conversation", token)).size(), "the marker and the one good message");
    }

    // --- the leak rule ---

    @Test
    void aBanIdLeakedBeforeFilingForfeitsTheAppeal() {
        String punishment = punish("d1".repeat(64), "pub-forfeit");
        String banId = banIdOf(punishment);
        markLeaked(punishment);

        Raw login = login(banId);
        String token = (String) login.json().get("token");

        assertEquals("LIMITED", login.json().get("scope"));
        assertEquals("FORFEITED", appealState(playerGet("/public/punishment", token)));
        Raw filing = playerPost("/public/appeal", token, Map.of("conditionsVersion", 1, "statement", "I want to appeal"));
        assertEquals(409, filing.status());
        assertEquals("FORFEITED", filing.json().get("code"));
    }

    @Test
    void aBanIdLeakedAfterFilingFreezesTheTicketAndHidesTheConversation() {
        String punishment = punish("d2".repeat(64), "pub-frozen");
        String token = sessionFor(punishment);
        fileAppeal(token);
        playerPost("/public/messages", token, Map.of("text", "my private statement"));

        markLeaked(punishment);

        assertEquals(401, playerGet("/public/session", token).status(), "the old session was ended with the mark");
        String limited = (String) login(banIdOf(punishment)).json().get("token");
        assertEquals("FROZEN", appealState(playerGet("/public/punishment", limited)));
        Raw posted = playerPost("/public/messages", limited, Map.of("text", "hello"));
        assertEquals(409, posted.status());
        assertEquals("FROZEN", posted.json().get("code"));
        Raw conversation = playerGet("/public/conversation", limited);
        assertEquals(403, conversation.status());
        assertFalse(conversation.body().contains("my private statement"));
        assertEquals(403, playerGet("/public/appeal", limited).status());
        assertEquals(200, playerGet("/public/punishment", limited).status(), "the facts and the notice stay");
        assertEquals(200, post("/case/" + punishment + "/messages", Map.of("text", "the team can still write"), named(this.jwt.moderator())).status());
    }

    @Test
    void aReissueRestoresFullAccessWithTheNewBanIdOnly() {
        String punishment = punish("d3".repeat(64), "pub-reissue");
        String oldBanId = banIdOf(punishment);
        String token = sessionFor(punishment);
        fileAppeal(token);
        markLeaked(punishment);

        Reply reissued = post("/case/" + punishment + "/ban-id/reissue", Map.of("reason", "NOT_PLAYERS_FAULT"));
        assertEquals(200, reissued.status(), reissued.body());

        assertEquals(401, login(oldBanId).status(), "the old ID is dead");
        Raw fresh = login(banIdOf(punishment));
        assertEquals("FULL", fresh.json().get("scope"));
        String newToken = (String) fresh.json().get("token");
        assertEquals(200, playerPost("/public/messages", newToken, Map.of("text", "I am back")).status(), "the frozen ticket thaws");
    }
}
