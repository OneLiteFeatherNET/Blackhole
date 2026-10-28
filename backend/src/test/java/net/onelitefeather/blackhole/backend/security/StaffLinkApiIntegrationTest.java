package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staff link over HTTP against the real database: self-signed tokens, the role of each route and
 * the whole flow of issuing a code in the panel and confirming it as the proxy. Each test uses its own
 * random oids and Minecraft UUIDs, so tests share nothing in the class-wide database.
 */
@Tag("integration")
class StaffLinkApiIntegrationTest extends ApiIntegrationTest {

    private String staff(UUID oid) {
        return this.jwt.delegated().claim("oid", oid.toString()).claim("name", "Staff " + oid.toString().substring(0, 8))
                .groups(TestJwtIssuer.VIEWER_GROUP).sign();
    }

    private String proxy() {
        return this.jwt.appOnly().azp(TestJwtIssuer.PROXY_CLIENT).claim("oid", UUID.randomUUID().toString()).roles(TestJwtIssuer.PROXY_ROLE).sign();
    }

    private String issueCode(String token) {
        Reply reply = post("/staff-link/code", null, token);
        assertEquals(200, reply.status(), reply.body());
        return (String) reply.json().get("code");
    }

    private Reply confirm(String code, UUID minecraft) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", code);
        body.put("onBehalfOf", minecraft == null ? null : minecraft.toString());
        return post("/staff-link/confirm", body, proxy());
    }

    @Test
    void aSignedInViewerIsIssuedAnEightCharacterCodeWithAnExpiry() {
        Reply reply = post("/staff-link/code", null, staff(UUID.randomUUID()));

        assertEquals(200, reply.status(), reply.body());
        assertEquals(9, ((String) reply.json().get("code")).length(), "ABCD-EFGH");
        assertNotNull(reply.json().get("expiresAt"), "the response carries an expiry");
    }

    @Test
    void anAppOnlyTokenCannotRequestACode() {
        assertEquals(403, post("/staff-link/code", null, proxy()).status(), "service principal");
    }

    @Test
    void theProxyConfirmsACodeAndTheStaffMemberSeesTheLink() {
        UUID oid = UUID.randomUUID();
        UUID minecraft = UUID.randomUUID();
        String token = staff(oid);

        Reply confirmed = confirm(issueCode(token), minecraft);

        assertEquals(200, confirmed.status(), confirmed.body());
        assertEquals(oid.toString(), confirmed.json().get("oid"), "bound to the issuing oid");
        Map<String, Object> status = get("/staff-link/me", token).json();
        assertEquals(true, status.get("linked"), "linked");
        assertEquals(minecraft.toString(), status.get("minecraftUuid"), "to the confirmed account");
    }

    @Test
    void theProxyCanLookUpWhoALinkedAccountBelongsTo() {
        UUID minecraft = UUID.randomUUID();
        confirm(issueCode(staff(UUID.randomUUID())), minecraft);

        Reply reply = get("/staff-link/by-minecraft/" + minecraft, proxy());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(true, reply.json().get("linked"), "linked");
        assertEquals(false, get("/staff-link/by-minecraft/" + UUID.randomUUID(), proxy()).json().get("linked"), "an unknown account");
    }

    @Test
    void aHumanTokenCannotConfirmACode() {
        String token = staff(UUID.randomUUID());

        Reply reply = post("/staff-link/confirm", Map.of("code", issueCode(token), "onBehalfOf", UUID.randomUUID().toString()), token);

        assertEquals(403, reply.status(), "only the proxy confirms");
    }

    @Test
    void confirmingWithoutOnBehalfOfIsABadRequest() {
        assertEquals(400, confirm(issueCode(staff(UUID.randomUUID())), null).status(), "onBehalfOf is required");
    }

    @Test
    void aWrongCodeIsRefusedWithInvalidCodeAndRevealsNothingMore() {
        Reply reply = confirm("ABCD-EFGH", UUID.randomUUID());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("INVALID_CODE", reply.json().get("code"), "the uniform error");
    }

    @Test
    void aUsedCodeIsRefusedTheSameWayAsAWrongOne() {
        String code = issueCode(staff(UUID.randomUUID()));
        confirm(code, UUID.randomUUID());

        Reply reply = confirm(code, UUID.randomUUID());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("INVALID_CODE", reply.json().get("code"), "the same error as for a wrong code");
    }

    @Test
    void confirmingAnotherCodeForTheSameAccountMovesTheLinkToTheNewStaffMember() {
        UUID minecraft = UUID.randomUUID();
        String first = staff(UUID.randomUUID());
        String second = staff(UUID.randomUUID());
        confirm(issueCode(first), minecraft);

        confirm(issueCode(second), minecraft);

        assertEquals(false, get("/staff-link/me", first).json().get("linked"), "the first lost the link");
        assertEquals(true, get("/staff-link/me", second).json().get("linked"), "the second has it");
    }

    @Test
    void aStaffMemberCanUnlinkThemselves() {
        String token = staff(UUID.randomUUID());
        confirm(issueCode(token), UUID.randomUUID());

        assertEquals(200, delete("/staff-link/me", token).status(), "unlinked");

        assertEquals(false, get("/staff-link/me", token).json().get("linked"), "no longer linked");
        assertEquals(404, delete("/staff-link/me", token).status(), "a second unlink finds nothing");
    }

    @Test
    void aModeratorCannotUnlinkAnotherPersonButAnAdminCan() {
        UUID oid = UUID.randomUUID();
        confirm(issueCode(staff(oid)), UUID.randomUUID());

        assertEquals(403, delete("/admin/staff-link/" + oid, this.jwt.moderator()).status(), "moderator");
        assertEquals(200, delete("/admin/staff-link/" + oid, this.jwt.admin()).status(), "admin");
        assertEquals(404, delete("/admin/staff-link/" + oid, this.jwt.admin()).status(), "already gone");
    }

    @Test
    void anAdminSeesTheLinkInTheListAndAModeratorDoesNot() {
        UUID oid = UUID.randomUUID();
        confirm(issueCode(staff(oid)), UUID.randomUUID());

        Reply list = get("/admin/staff-link", this.jwt.admin());

        assertEquals(200, list.status(), list.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.json().get("items");
        assertTrue(items.stream().anyMatch(item -> oid.toString().equals(item.get("oid"))), "the new link is listed");
        assertEquals(403, get("/admin/staff-link", this.jwt.moderator()).status(), "moderator");
    }

    @Test
    void anonymizingAStaffMemberRemovesTheirLink() {
        UUID oid = UUID.randomUUID();
        String token = staff(oid);
        confirm(issueCode(token), UUID.randomUUID());

        assertEquals(200, post("/admin/staff-actor/" + oid + "/anonymize", null, this.jwt.admin()).status(), "anonymized");

        assertFalse((Boolean) get("/staff-link/me", token).json().get("linked"), "the link is gone");
    }
}
