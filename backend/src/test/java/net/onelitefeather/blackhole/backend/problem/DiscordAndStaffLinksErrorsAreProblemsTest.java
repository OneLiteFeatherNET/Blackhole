package net.onelitefeather.blackhole.backend.problem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The error responses of the Discord and staff link routes are RFC 9457 problems: the content type, the five standard
 * members, the machine {@code code} where the route had one, and the status the route always answered.
 */
class DiscordAndStaffLinksErrorsAreProblemsTest extends ApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROBLEMS = "https://blackhole.onelitefeather.net/problems/";
    private static final String DISCORD_USER = "800000000000000001";

    @Test
    void aWrongBanIdOnOpeningATicketIsANotEligibleProblem() throws Exception {
        RawHttp.Reply reply = send("POST", "/discord/tickets",
                "{\"discordUserId\":\"" + DISCORD_USER + "\",\"banId\":\"ZZZZZZZZ\",\"statement\":\"x\"}", this.jwt.discord());

        assertProblem(reply, 404, "not-eligible", "Not eligible", "the Ban ID cannot be used to open a ticket",
                "/discord/tickets", "NOT_ELIGIBLE");
    }

    @Test
    void aWrongLinkCodeOnTheDiscordFacadeIsAnInvalidCodeProblem() throws Exception {
        RawHttp.Reply reply = send("POST", "/discord/link/confirm",
                "{\"code\":\"ABCD-EFGH\",\"discordUserId\":\"" + DISCORD_USER + "\"}", this.jwt.discord());

        assertProblem(reply, 400, "invalid-code", "Invalid code", "the link code is invalid or expired",
                "/discord/link/confirm", "INVALID_CODE");
    }

    @Test
    void aMalformedOwnerOnAPlayerCodeIsAValidationProblemWithoutCode() throws Exception {
        RawHttp.Reply reply = send("POST", "/player-discord-link/code",
                "{\"owner\":\"" + UUID.randomUUID() + "\"}", this.jwt.proxy());

        assertProblem(reply, 400, "validation", "Bad Request", "owner must be a sha-512 hash",
                "/player-discord-link/code", null);
    }

    @Test
    void aDiscordAccountThatIsNotLinkedIsANotFoundProblem() throws Exception {
        RawHttp.Reply reply = send("DELETE", "/discord/link/" + DISCORD_USER, null, this.jwt.discord());

        assertProblem(reply, 404, "not-found", "Not Found", "this Discord account is not linked",
                "/discord/link/" + DISCORD_USER, null);
    }

    @Test
    void aMissingReportIsANotFoundProblemThatDoesNotEchoTheIdentifier() throws Exception {
        String identifier = UUID.randomUUID().toString();
        RawHttp.Reply reply = send("GET", "/discord/notifications/report/" + identifier, null, this.jwt.discord());

        assertProblem(reply, 404, "not-found", "Not Found", "there is no such report",
                "/discord/notifications/report/" + identifier, null);
    }

    @Test
    void aMissingStaffActorIsANotFoundProblemThatDoesNotEchoTheIdentifier() throws Exception {
        String oid = UUID.randomUUID().toString();
        RawHttp.Reply reply = send("POST", "/admin/staff-actor/" + oid + "/anonymize", null, this.jwt.admin());

        assertProblem(reply, 404, "not-found", "Not Found", "there is no such staff actor",
                "/admin/staff-actor/" + oid + "/anonymize", null);
    }

    @Test
    void aWrongStaffLinkCodeIsAnInvalidCodeProblem() throws Exception {
        RawHttp.Reply reply = send("POST", "/staff-link/confirm",
                "{\"code\":\"ABCD-EFGH\",\"onBehalfOf\":\"" + UUID.randomUUID() + "\"}", this.jwt.proxy());

        assertProblem(reply, 400, "invalid-code", "Invalid code", "the link code is invalid or expired",
                "/staff-link/confirm", "INVALID_CODE");
    }

    @Test
    void aMissingStaffLinkIsANotFoundProblemThatDoesNotEchoTheIdentifier() throws Exception {
        String oid = UUID.randomUUID().toString();
        RawHttp.Reply reply = send("DELETE", "/admin/staff-link/" + oid, null, this.jwt.admin());

        assertProblem(reply, 404, "not-found", "Not Found", "there is no such link",
                "/admin/staff-link/" + oid, null);
    }

    private RawHttp.Reply send(String method, String path, String body, String token) {
        return RawHttp.send(this.server.getURL(), method, path, body, token);
    }

    /**
     * Asserts one problem response. {@code code} is the machine code the route answers with, {@code null} when it has none; the
     * legacy members {@code kind} and {@code errorMessage} must be gone.
     */
    private static void assertProblem(RawHttp.Reply reply, int status, String slug, String title, String detail, String instance,
                                      String code) throws JsonProcessingException {
        assertEquals(status, reply.status(), "the status the route always answered: " + reply.body());
        assertEquals("application/problem+json", reply.contentType(), "the error is a problem: " + reply.body());
        Map<String, Object> body = JSON.readValue(reply.body(), new TypeReference<Map<String, Object>>() {
        });
        assertEquals(PROBLEMS + slug, body.get("type"), "the type is the kind or the code, in kebab case");
        assertEquals(title, body.get("title"), "the title is fixed per type");
        assertEquals(status, body.get("status"), "the body repeats the status");
        assertEquals(detail, body.get("detail"), "the detail is the fixed sentence");
        assertEquals(instance, body.get("instance"), "the instance is the request path");
        assertEquals(code, body.get("code"), "the machine code is the code member");
        assertFalse(body.containsKey("kind"), "the legacy kind member is gone: " + reply.body());
        assertFalse(body.containsKey("errorMessage"), "the legacy errorMessage member is gone: " + reply.body());
    }
}
