package net.onelitefeather.blackhole.backend.kick;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentKind;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.phoca.metadata.Metadata;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A kick is a moment: never active, never enforced, but part of the history and of escalation. */
@Tag("integration")
class KickNotActiveIntegrationTest extends ApiIntegrationTest {

    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());

    /** One staff member for the whole test: the Idempotency-Key is scoped to the caller's oid. */
    private final String moderator = this.jwt.moderator();

    private Reply kick(String owner, String key, String scope, String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("owner", owner);
        body.put("reason", "AFK farming");
        if (scope != null) {
            body.put("scope", scope);
        }
        if (target != null) {
            body.put("target", target);
        }
        return post("/kick", body, moderator, Map.of("Idempotency-Key", key));
    }

    private PunishmentRepository punishments() {
        return this.context.getBean(PunishmentRepository.class);
    }

    private static String identifier(Reply accepted) {
        return IdGenerator.generateId(UUID.fromString((String) accepted.json().get("id")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void aKickIsAcceptedAsPendingWithADeadline() {
        Reply reply = kick("c1".repeat(64), "not-active-1", null, null);

        assertEquals(202, reply.status(), reply.body());
        assertEquals("PENDING", reply.json().get("status"));
        assertEquals("PLAYER", reply.json().get("kind"));
        assertEquals(false, reply.json().get("repeated"));
        assertEquals(List.of(), reply.json().getOrDefault("warnings", List.of()), "no warnings");
        assertTrue(((Number) reply.json().get("deadlineAt")).longValue() > 0, "a deadline is set");
    }

    @Test
    void aKickIsStoredAsAKickRowWithoutExpiryAndNeverLifted() {
        Reply reply = kick("c2".repeat(64), "not-active-2", "NETWORK", null);

        PunishmentEntity stored = punishments().findById(identifier(reply)).orElseThrow();

        assertEquals(PunishmentKind.KICK, stored.getKind());
        assertFalse(stored.isPermanent(), "a kick is not permanent");
        assertFalse(stored.isLifted(), "a kick is not lifted");
        assertFalse(stored.getMetaData().containsKey("expiration_date"), "a kick has no expiry");
        assertEquals("NETWORK", stored.getKickScope());
    }

    @Test
    void aKickIsNotInTheActiveProfileButIsInTheHistory() {
        String owner = "c3".repeat(64);
        Reply reply = kick(owner, "not-active-3", null, null);

        Map<String, Object> profile = get("/profile/" + owner).json();

        assertEquals(Map.of(), map(profile.getOrDefault("active", Map.of())), "nothing is active");
        List<?> history = (List<?>) profile.get("history");
        assertEquals(1, history.size(), "the kick sits in the history");
        assertEquals("KICK", map(history.getFirst()).get("kind"));
        assertEquals(identifier(reply), map(history.getFirst()).get("identifier"));
    }

    @Test
    void aKickIsNotInTheActivePunishmentList() {
        Reply reply = kick("c4".repeat(64), "not-active-4", null, null);

        Reply active = get("/punishment/active", this.jwt.viewer());

        assertEquals(200, active.status(), active.body());
        assertFalse(active.body().contains(identifier(reply)), "the active overview must not list a kick");
    }

    @Test
    void aKickIsNotEnforcedWhenTheProxyReadsTheProfileOnLogin() {
        String owner = "c5".repeat(64);
        kick(owner, "not-active-5", "SECTION", "chat");

        Map<String, Object> profile = get("/profile/" + owner, this.jwt.proxy()).json();

        assertEquals(Map.of(), map(profile.getOrDefault("active", Map.of())), "the proxy sees no active punishment to enforce");
    }

    @Test
    void aKickCountsAsAnEarlierStepForEscalation() {
        String owner = "c6".repeat(64);
        assertEquals(200, post("/template", Map.of("key", "int-kick-esc", "reason", "Spam", "section", "chat",
                "steps", List.of(Map.of("duration", "PT1H", "eloCost", 0), Map.of("duration", "P1D", "eloCost", 0)))).status());
        PunishmentEntity earlier = new PunishmentEntity(IdGenerator.generateId(), STAFF, owner, null, false,
                this.context.getBean(Clock.class).millis(), null, new HashMap<>(Map.of(Metadata.META_DATA_KEY_CREATION_DATE, 1L)));
        earlier.recordKick("NETWORK", null);
        earlier.recordEscalation("int-kick-esc", 0, 2, 0);
        punishments().save(earlier);

        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", "int-kick-esc"));

        assertEquals(200, applied.status(), applied.body());
        assertEquals(2, applied.json().get("step"), "the kick was step one: " + applied.body());
    }

    @Test
    void aRepeatedIdempotencyKeyAnswersTheSameKickAndWritesNothing() {
        String owner = "c7".repeat(64);
        Reply first = kick(owner, "not-active-7", null, null);

        Reply second = kick(owner, "not-active-7", null, null);

        assertEquals(202, second.status(), second.body());
        assertEquals(first.json().get("id"), second.json().get("id"), "the same kick");
        assertEquals(true, second.json().get("repeated"));
        List<?> history = (List<?>) get("/profile/" + owner).json().get("history");
        assertEquals(1, history.size(), "no second kick was written");
    }

    @Test
    void anIdempotencyKeyReusedForAnotherPlayerIsAConflict() {
        kick("c8".repeat(64), "not-active-8", null, null);

        Reply other = kick("c9".repeat(64), "not-active-8", null, null);

        assertEquals(409, other.status(), other.body());
    }

    @Test
    void aRequestWithoutIdempotencyKeyIsRefused() {
        Reply reply = post("/kick", Map.of("owner", "d1".repeat(64), "reason", "x"), this.jwt.moderator());

        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void aScopedKickWithoutTargetIs422() {
        Reply reply = kick("d2".repeat(64), "not-active-d2", "SERVER", null);

        assertEquals(422, reply.status(), reply.body());
        assertEquals("KICK_TARGET_INVALID", reply.json().get("kind"));
    }

    @Test
    void aKickOfASectionThatDoesNotExistIs422() {
        Reply reply = kick("d3".repeat(64), "not-active-d3", "SECTION", "no-such-section");

        assertEquals(422, reply.status(), reply.body());
    }

    @Test
    void aPlayerPermanentlyBannedInTheTargetSectionIsKickedWithAWarningAndTheBanStays() {
        String owner = "d4".repeat(64);
        post("/template", Map.of("key", "int-kick-ban", "reason", "Cheating", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 800))));
        Reply ban = post("/punishment/apply", Map.of("owner", owner, "templateKey", "int-kick-ban"));
        assertEquals(200, ban.status(), ban.body());
        assertTrue(ban.body().contains("\"escalation\""), "the cost pushed the player into a permanent ban: " + ban.body());

        Reply reply = kick(owner, "not-active-d4", "SECTION", "network");

        assertEquals(202, reply.status(), "a permanent ban does not refuse a kick: " + reply.body());
        assertEquals(1, ((List<?>) reply.json().getOrDefault("warnings", List.of())).size(), "the answer warns: " + reply.body());
        Map<String, Object> network = map(map(get("/profile/" + owner).json().get("active")).get("network"));
        assertEquals(true, network.get("permanent"), "the permanent ban is unchanged");
    }

    @Test
    void theProxyMayNotKick() {
        Reply reply = post("/kick", Map.of("owner", "d5".repeat(64), "reason", "x"), this.jwt.proxy(), Map.of("Idempotency-Key", "k"));

        assertEquals(403, reply.status(), "the proxy acknowledges kicks, it does not start them");
    }

    @Test
    void theStatusIsReadableByAViewerAndTheProxyAndNamesTheReason() {
        Reply accepted = kick("d6".repeat(64), "not-active-d6", null, null);
        String path = "/kick/" + accepted.json().get("id");

        Reply viewer = get(path, this.jwt.viewer());
        Reply proxy = get(path, this.jwt.proxy());

        assertEquals(200, viewer.status(), viewer.body());
        assertEquals("AFK farming", viewer.json().get("reason"));
        assertEquals("PENDING", viewer.json().get("status"));
        assertEquals(200, proxy.status(), "the proxy reads the reason before it shows the screen");
    }

    @Test
    void aDeliveryReportedByTheProxyMakesTheKickDelivered() {
        Reply accepted = kick("d7".repeat(64), "not-active-d7", null, null);
        String id = (String) accepted.json().get("id");

        Reply ack = post("/kick/" + id + "/deliveries", Map.of("proxyId", "Proxy-1", "found", true, "moved", true), this.jwt.proxy());

        assertEquals(204, ack.status(), ack.body());
        Map<String, Object> read = get("/kick/" + id).json();
        assertEquals("DELIVERED", read.get("status"));
        assertEquals(1, read.get("deliveredCount"));
    }

    @Test
    void onlyTheProxyMayReportADelivery() {
        Reply accepted = kick("d8".repeat(64), "not-active-d8", null, null);

        Reply ack = post("/kick/" + accepted.json().get("id") + "/deliveries", Map.of("proxyId", "Proxy-1", "found", true, "moved", true),
                this.jwt.admin());

        assertEquals(403, ack.status(), "an administrator may not forge a delivery");
    }

    @Test
    void anUnknownKickIs404() {
        assertEquals(404, get("/kick/" + UUID.randomUUID()).status());
    }
}
