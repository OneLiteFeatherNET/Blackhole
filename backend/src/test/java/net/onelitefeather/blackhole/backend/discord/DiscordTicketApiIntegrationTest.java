package net.onelitefeather.blackhole.backend.discord;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole Discord ticket path over HTTP against the real application, PostgreSQL and a local file store: open with a private Ban ID, the
 * channel, ingest, relay, the decision, close with the completeness proof and the protocol, the archive and delete gates, the leak check and
 * the participant erasure. Each test uses its own owner and Discord ids, so tests share nothing in the class-wide database.
 */
@Tag("integration")
class DiscordTicketApiIntegrationTest extends ApiIntegrationTest {

    private static final UUID MODERATOR = UUID.fromString("a11ce000-0000-4000-8000-000000000031");
    private static final UUID REVIEWER = UUID.fromString("b0b00000-0000-4000-8000-000000000032");
    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    @TempDir
    Path files;

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "blackhole.appeal.min-days-manual", 0,
                "blackhole.evidence.files.storage", "local",
                "blackhole.evidence.files.local.path", this.files.toString(),
                "blackhole.evidence.scan.enabled", false,
                "blackhole.protocol.reconcile-interval", "0"
        );
    }

    private static String snowflake() {
        return Long.toString(100_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(800_000_000_000_000_000L));
    }

    private static String owner() {
        return (UUID.randomUUID().toString() + UUID.randomUUID() + UUID.randomUUID() + UUID.randomUUID()).replace("-", "").substring(0, 128);
    }

    private String bot() {
        return this.jwt.appOnly().azp(TestJwtIssuer.DISCORD_CLIENT).claim("oid", UUID.randomUUID().toString()).roles(TestJwtIssuer.DISCORD_ROLE).sign();
    }

    private String moderator(UUID oid) {
        return named(this.jwt.delegated().groups(TestJwtIssuer.MODERATOR_GROUP).claim("oid", oid.toString()).claim("name", "Staff Alice").sign());
    }

    private String punishment(String owner, String key) {
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

    private Reply open(String banId, String discordUser, String statement) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("discordUserId", discordUser, "banId", banId, "displayName", "Player"));
        if (statement != null) {
            body.put("statement", statement);
        }
        return post("/discord/tickets", body, bot());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ticketOf(Reply opened) {
        assertEquals(200, opened.status(), opened.body());
        return (Map<String, Object>) opened.json().get("ticket");
    }

    private String channelReady(String ticket) {
        String channel = snowflake();
        Reply patched = patch("/discord/tickets/" + ticket, Map.of("channelId", channel, "guildId", "800000000000000001"), bot());
        assertEquals(200, patched.status(), patched.body());
        assertEquals("OPEN", patched.json().get("state"));
        return channel;
    }

    private Reply ingest(String ticket, Map<String, Object> body) {
        return post("/discord/tickets/" + ticket + "/messages", body, bot());
    }

    private static Map<String, Object> message(String id, String author, boolean staffRole, String text, long at) {
        return Map.of("discordMessageId", id, "authorDiscordUserId", author, "authorName", "Someone", "bot", false, "hasStaffRole", staffRole,
                "createdAt", at, "text", text);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> timeline(String punishment) {
        return (List<Map<String, Object>>) get("/case/" + punishment + "/timeline?limit=200").json().get("items");
    }

    private List<String> types(String punishment) {
        return timeline(punishment).stream().map(entry -> (String) entry.get("type")).toList();
    }

    private String appealOf(String ticket) {
        return (String) get("/discord/tickets/" + ticket, bot()).json().get("appealId");
    }

    private void decide(String appeal, String decision) {
        Reply decided = post("/appeal/" + appeal + "/review", Map.of("decision", decision, "decisionNote", "internal note",
                "playerMessage", "Thank you for waiting, the team decided."), moderator(REVIEWER));
        assertEquals(200, decided.status(), decided.body());
    }

    // ---- open ----

    @Test
    void aWrongBanIdAndABanIdBoundToAnotherUserGetTheSameReply() {
        String punishment = punishment(owner(), "dt-uniform");
        String banId = banId(punishment);
        String first = snowflake();
        ticketOf(open(banId, first, "I did nothing wrong"));

        Reply wrong = open("ZZZZZZZZ", snowflake(), "x");
        Reply bound = open(banId, snowflake(), "x");

        assertEquals(404, wrong.status());
        assertEquals(404, bound.status());
        assertEquals(wrong.body(), bound.body(), "no oracle: a bound Ban ID answers like an unknown one");
        assertEquals("NOT_ELIGIBLE", wrong.json().get("code"));
    }

    @Test
    void theSameUserOpeningAgainGetsTheSameTicket() {
        String punishment = punishment(owner(), "dt-again");
        String banId = banId(punishment);
        String user = snowflake();
        Map<String, Object> first = ticketOf(open(banId, user, "statement one"));

        Reply again = open(banId, user, null);

        assertEquals(first.get("id"), ticketOf(again).get("id"));
        assertEquals(false, again.json().get("created"));
    }

    @Test
    void aTicketIsOpenedWithAnAppealAFilledStatementAndATicketOpenedEvent() {
        String punishment = punishment(owner(), "dt-open");
        String banId = banId(punishment);
        String user = snowflake();

        Reply opened = open(banId, user, "I did nothing wrong");

        Map<String, Object> ticket = ticketOf(opened);
        assertEquals("PENDING_CHANNEL", ticket.get("state"));
        assertEquals(true, opened.json().get("appealFiled"));
        assertEquals(user, ticket.get("playerDiscordUserId"));
        assertTrue(types(punishment).contains("ticket.opened"));
        assertTrue(types(punishment).contains("appeal.filed"));
        assertFalse(get("/case/" + punishment + "/timeline?limit=200").body().contains(user), "no Discord id on the timeline");
        assertFalse(opened.body().contains(banId), "the Ban ID is never echoed");
    }

    @Test
    void withoutAnOpenAppealAStatementIsRequired() {
        String punishment = punishment(owner(), "dt-statement");

        Reply reply = open(banId(punishment), snowflake(), null);

        assertEquals(400, reply.status());
        assertEquals("STATEMENT_REQUIRED", reply.json().get("code"));
        assertFalse(types(punishment).contains("ticket.opened"));
    }

    @Test
    void afterADenialTheCooldownNamesTheDateAndCreatesNothing() {
        String punishment = punishment(owner(), "dt-cooldown");
        String banId = banId(punishment);
        String user = snowflake();
        String ticket = (String) ticketOf(open(banId, user, "first try")).get("id");
        channelReady(ticket);
        decide(appealOf(ticket), "DENIED");
        patch("/discord/tickets/" + ticket, Map.of("state", "CLOSED"), bot());

        Reply again = open(banId, user, "second try");

        assertEquals(409, again.status(), again.body());
        assertEquals("NOT_YET", again.json().get("code"));
        assertNotNull(again.json().get("availableFrom"));
        assertEquals(1, types(punishment).stream().filter("ticket.opened"::equals).count(), "no second ticket");
    }

    // ---- life of a ticket ----

    @Test
    @SuppressWarnings("unchecked")
    void aTicketLivesFromTheChannelToTheDeletionWithTheProtocolGatesOnTheWay() {
        String punishment = punishment(owner(), "dt-life");
        String banId = banId(punishment);
        String player = snowflake();
        String staff = snowflake();
        String ticket = (String) ticketOf(open(banId, player, "I did nothing wrong")).get("id");
        channelReady(ticket);

        // the team writes in the panel; it is in the outbox, and a Discord message is not
        assertEquals(200, post("/case/" + punishment + "/messages", Map.of("text", "Please tell us more."), moderator(MODERATOR)).status());
        assertEquals(200, ingest(ticket, Map.of("items", List.of(message("1000", player, false, "It was a misunderstanding", 5_000L)))).status());
        Map<String, Object> outbox = get("/discord/tickets/" + ticket + "/outbox", bot()).json();
        List<Map<String, Object>> items = (List<Map<String, Object>>) outbox.get("items");
        assertEquals(1, items.size());
        assertEquals("ADD", items.getFirst().get("kind"));
        assertEquals("Please tell us more.", items.getFirst().get("text"));
        assertEquals(200, post("/discord/tickets/" + ticket + "/links", Map.of("seq", ((Number) items.getFirst().get("seq")).longValue(),
                "messageId", items.getFirst().get("messageId"), "discordMessageId", "9001"), bot()).status());
        assertTrue(((List<?>) get("/discord/tickets/" + ticket + "/outbox", bot()).json().get("items")).isEmpty(), "nothing to relay twice");

        // an unlinked staff member with the role writes in the channel
        assertEquals(200, ingest(ticket, Map.of("items", List.of(message("1001", staff, true, "We are looking at it", 6_000L)))).status());

        // the archive is refused while the appeal is undecided and there is no protocol
        decide(appealOf(ticket), "DENIED");
        Reply early = patch("/discord/tickets/" + ticket, Map.of("state", "ARCHIVED"), bot());
        assertEquals(409, early.status());

        // the proof: wrong count first, then the right one
        Reply wrong = post("/discord/tickets/" + ticket + "/close", Map.of("count", 1, "lastMessageId", "1001"), bot());
        assertEquals(409, wrong.status());
        assertEquals("TRANSCRIPT_MISMATCH", wrong.json().get("code"));
        assertEquals(2, ((Number) wrong.json().get("backendCount")).intValue());
        assertFalse(types(punishment).contains("ticket.transcript_complete"));
        Reply closed = post("/discord/tickets/" + ticket + "/close", Map.of("count", 2, "lastMessageId", "1001"), bot());
        assertEquals(200, closed.status(), closed.body());
        assertEquals(1, ((Number) closed.json().get("protocolVersion")).intValue());
        assertEquals(1, ((Number) post("/discord/tickets/" + ticket + "/close", Map.of("count", 2, "lastMessageId", "1001"), bot())
                .json().get("protocolVersion")).intValue(), "closing again is idempotent");
        assertEquals(1, types(punishment).stream().filter("ticket.transcript_complete"::equals).count());

        // archive works now; a late edit in the archived channel makes the stored protocol outdated
        assertEquals("ARCHIVED", patch("/discord/tickets/" + ticket, Map.of("state", "ARCHIVED"), bot()).json().get("state"));
        assertEquals(200, ingest(ticket, Map.of("items", List.of(message("1001", staff, true, "We decided after a second look", 6_000L)))).status());
        Reply refused = patch("/discord/tickets/" + ticket, Map.of("state", "DELETED"), bot());
        assertEquals(409, refused.status(), refused.body());
        assertEquals("PROTOCOL_REQUIRED", refused.json().get("code"));
        Reply ensured = post("/discord/tickets/" + ticket + "/protocol/ensure", Map.of(), bot());
        assertEquals(200, ensured.status(), ensured.body());
        assertEquals(2, ((Number) ensured.json().get("protocolVersion")).intValue(), "the late edit made version two");
        assertEquals("DELETED", patch("/discord/tickets/" + ticket, Map.of("state", "DELETED"), bot()).json().get("state"));

        List<String> types = types(punishment);
        assertTrue(types.containsAll(List.of("ticket.opened", "ticket.transcript_complete", "ticket.archived", "ticket.channel_deleted")), types.toString());
        assertEquals(2, ((List<?>) get("/case/" + punishment + "/protocol", this.jwt.viewer()).json().get("items")).size(), "both versions are kept");
        assertFalse(get("/case/" + punishment + "/timeline?limit=200", this.jwt.viewer()).body().contains(player), "no Discord id on the timeline");
    }

    @Test
    void aChannelDeletedByAPersonBeforeTheCloseIsRecordedAsIncomplete() {
        String punishment = punishment(owner(), "dt-gone");
        String ticket = (String) ticketOf(open(banId(punishment), snowflake(), "please")).get("id");
        channelReady(ticket);

        Reply gone = patch("/discord/tickets/" + ticket, Map.of("channelGone", true), bot());

        assertEquals("DELETED", gone.json().get("state"));
        Map<String, Object> event = timeline(punishment).stream().filter(e -> e.get("type").equals("ticket.channel_deleted")).findFirst().orElseThrow();
        assertEquals(false, ((Map<?, ?>) event.get("payload")).get("transcriptComplete"));
    }

    @Test
    void ensureForAnUndecidedAppealIs409AndAUnknownTicketIs404() {
        String punishment = punishment(owner(), "dt-ensure");
        String ticket = (String) ticketOf(open(banId(punishment), snowflake(), "please")).get("id");
        channelReady(ticket);

        assertEquals(409, post("/discord/tickets/" + ticket + "/protocol/ensure", Map.of(), bot()).status());
        assertEquals(404, post("/discord/tickets/" + UUID.randomUUID() + "/protocol/ensure", Map.of(), bot()).status());
    }

    @Test
    void theListShowsTicketsByState() {
        String punishment = punishment(owner(), "dt-list");
        String ticket = (String) ticketOf(open(banId(punishment), snowflake(), "please")).get("id");

        Reply pending = get("/discord/tickets?state=PENDING_CHANNEL", bot());

        assertEquals(200, pending.status(), pending.body());
        assertTrue(pending.body().contains(ticket));
        assertFalse(get("/discord/tickets?state=ARCHIVED", bot()).body().contains(ticket));
    }

    // ---- leak check ----

    @Test
    void aBanIdPostedInAPublicChannelIsMarkedLeakedOnceAndALookAlikeLeavesNoTrace() {
        String punishment = punishment(owner(), "dt-leak");
        String banId = banId(punishment);
        String ticket = (String) ticketOf(open(banId, snowflake(), "please")).get("id");
        String poster = snowflake();

        Reply lookAlike = post("/discord/leak-check", Map.of("candidates", List.of("ZZZZZZZZ"), "channelKind", "PUBLIC", "posterDiscordUserId", poster), bot());
        Reply first = post("/discord/leak-check", Map.of("candidates", List.of(banId, "ZZZZZZZZ"), "channelKind", "PUBLIC", "posterDiscordUserId", poster), bot());
        Reply again = post("/discord/leak-check", Map.of("candidates", List.of(banId), "channelKind", "PUBLIC", "posterDiscordUserId", poster), bot());

        assertEquals(0, ((Number) lookAlike.json().get("matched")).intValue());
        assertEquals(1, ((Number) first.json().get("matched")).intValue());
        assertEquals(1, ((Number) first.json().get("marked")).intValue());
        assertEquals(1, ((Number) again.json().get("matched")).intValue(), "the message is deleted again");
        assertEquals(0, ((Number) again.json().get("marked")).intValue(), "but no second leak event");
        assertEquals(1, types(punishment).stream().filter("ban_id.leak_marked"::equals).count());
        Map<String, Object> event = timeline(punishment).stream().filter(e -> e.get("type").equals("ban_id.leak_marked")).findFirst().orElseThrow();
        Map<?, ?> payload = (Map<?, ?>) event.get("payload");
        assertEquals("DISCORD_BOT", payload.get("source"));
        assertEquals("POSTED_PUBLIC", payload.get("reason"));
        assertEquals(false, payload.get("posterIsOwner"));
        assertNotNull(payload.get("poster"));
        assertEquals(true, get("/discord/tickets/" + ticket, bot()).json().get("leaked"), "the bot freezes the channel from this flag");
        assertFalse(first.body().contains(banId) || get("/case/" + punishment + "/timeline?limit=200").body().contains(banId));
        assertFalse(get("/case/" + punishment + "/timeline?limit=200").body().contains(poster), "the poster is only an opaque participant");
    }

    @Test
    void whenTheBoundPlayerPostsTheirOwnBanIdTheEventSaysSo() {
        String punishment = punishment(owner(), "dt-leak-owner");
        String banId = banId(punishment);
        String player = snowflake();
        ticketOf(open(banId, player, "please"));

        post("/discord/leak-check", Map.of("candidates", List.of(banId), "channelKind", "PUBLIC", "posterDiscordUserId", player), bot());

        Map<String, Object> event = timeline(punishment).stream().filter(e -> e.get("type").equals("ban_id.leak_marked")).findFirst().orElseThrow();
        assertEquals(true, ((Map<?, ?>) event.get("payload")).get("posterIsOwner"));
    }

    // ---- erasure ----

    @Test
    void anAdminErasesTheBoundParticipantAndTheDiscordIdIsGone() {
        String punishment = punishment(owner(), "dt-erase");
        String user = snowflake();
        String ticket = (String) ticketOf(open(banId(punishment), user, "please")).get("id");
        channelReady(ticket);
        ingest(ticket, Map.of("items", List.of(message("1000", user, false, "hello", 5_000L))));
        String participant = participantIdOf(punishment);

        Reply erased = post("/case/" + punishment + "/participants/" + participant + "/erase", Map.of(), named(this.jwt.admin()));

        assertEquals(200, erased.status(), erased.body());
        assertEquals(true, erased.json().get("erased"));
        assertNull(get("/discord/tickets/" + ticket, bot()).json().get("playerDiscordUserId"));
        assertEquals(false, post("/case/" + punishment + "/participants/" + participant + "/erase", Map.of(), named(this.jwt.admin())).json().get("erased"));
        assertEquals(404, post("/case/" + punishment + "/participants/" + UUID.randomUUID() + "/erase", Map.of(), named(this.jwt.admin())).status());
        assertTrue(types(punishment).contains("message.added"), "the timeline keeps its structure");
    }

    @SuppressWarnings("unchecked")
    private String participantIdOf(String punishment) {
        Map<String, Object> added = timeline(punishment).stream().filter(e -> e.get("type").equals("message.added")).findFirst().orElseThrow();
        return (String) ((Map<String, Object>) ((Map<String, Object>) added.get("payload")).get("author")).get("participantId");
    }
}
