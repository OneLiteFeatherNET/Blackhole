package net.onelitefeather.blackhole.backend.publicbans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.publicbans.auth.Argon2ClientHashVerifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three ban-list routes over HTTP, with the Basic chain, the HTTPS rule, the cache headers and the entry fields.
 * Listed rows are written straight into the punishment table, because the apply path is covered by its own tests.
 */
@Tag("integration")
class PublicBansApiIntegrationTest extends ApiIntegrationTest {

    private static final String CLIENT = "website";
    private static final String PASSWORD = "a-long-random-password-for-the-test-client";
    private static final UUID VECTOR = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String VECTOR_HASH = "9efc1073aa30a316b4d4930dbf73bf852be666299041e930c9763de9f0c03029";
    private static final String ENTRY_FIELDS = "hash,playerHash,hashScheme,reason,kind,status,issuedOn,permanent,expiresOn,endedOn";
    private static final String CHALLENGE = "Basic realm=\"blackhole-ban-list\", charset=\"UTF-8\"";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected Map<String, Object> extraProperties() {
        try {
            Path secrets = Files.createTempDirectory("ban-list-secrets");
            Files.writeString(secrets.resolve(CLIENT), Argon2ClientHashVerifier.encode(PASSWORD, "test-salt-sixteen".getBytes(StandardCharsets.US_ASCII)));
            return Map.of(
                    "blackhole.ban-list.clients", CLIENT,
                    "blackhole.ban-list.secrets-dir", secrets.toString(),
                    "blackhole.ban-list.trusted-ingress", "127.0.0.1,::1",
                    "blackhole.public-bans.key", Base64.getEncoder().encodeToString(new byte[32]));
        } catch (IOException e) {
            throw new IllegalStateException("the secrets directory of the test", e);
        }
    }

    @Test
    void aRequestWithoutCredentialsIsUnauthorizedWithTheBasicChallenge() throws Exception {
        Answer answer = ask(HttpRequest.GET("/public/bans").header("X-Forwarded-Proto", "https"));

        assertEquals(401, answer.status(), "no credentials, no list");
        assertEquals(CHALLENGE, answer.header("WWW-Authenticate"), "the challenge names the ban-list realm");
        assertEquals("UNAUTHORIZED", answer.json().get("code").asText(), "the problem names the code");
    }

    @Test
    void aBearerTokenIsNotAcceptedOnTheBanList() throws Exception {
        Answer answer = ask(HttpRequest.GET("/public/bans").bearerAuth(this.jwt.admin()).header("X-Forwarded-Proto", "https"));

        assertEquals(401, answer.status(), "a staff token does not open the list");
        assertEquals(CHALLENGE, answer.header("WWW-Authenticate"), "the answer carries the Basic challenge");
    }

    @Test
    void anUnknownHashWithoutCredentialsIsUnauthorizedNotNotFound() throws Exception {
        Answer answer = ask(HttpRequest.GET("/public/bans/zzzzzzzzzzzzzzzzzzzzzz").header("X-Forwarded-Proto", "https"));

        assertEquals(401, answer.status(), "authentication comes before the lookup, so no hash can be probed");
    }

    @Test
    void aUnknownClientAndAWrongPasswordGiveTheSameAnswer() throws Exception {
        Answer unknownName = ask(HttpRequest.GET("/public/bans").basicAuth("nobody", PASSWORD));
        Answer wrongPassword = ask(HttpRequest.GET("/public/bans").basicAuth(CLIENT, "wrong"));

        assertEquals(unknownName.status(), wrongPassword.status(), "the same status");
        assertEquals(unknownName.body(), wrongPassword.body(), "the same body");
        assertEquals(unknownName.header("WWW-Authenticate"), wrongPassword.header("WWW-Authenticate"), "the same challenge");
    }

    @Test
    void aValidClientOverPlainHttpIsRefusedWithHttpsRequired() throws Exception {
        Answer answer = ask(HttpRequest.GET("/public/bans").basicAuth(CLIENT, PASSWORD));

        assertEquals(403, answer.status(), "credentials are not evaluated over plain HTTP");
        assertEquals("HTTPS_REQUIRED", answer.json().get("code").asText(), "the code says why");
        assertNull(answer.header("WWW-Authenticate"), "no challenge is sent for a refused scheme");
    }

    @Test
    void aValidClientBehindTheIngressGetsTheListWithExactlyTheAllowedFields() throws Exception {
        String reason = "Reason " + UUID.randomUUID();
        seedListed(VECTOR, reason);

        Answer answer = ask(HttpRequest.GET("/public/bans").basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));

        assertEquals(200, answer.status(), "the authenticated client reads the list over HTTPS");
        JsonNode entry = entryWithReason(answer.json().get("items"), reason);
        assertEquals(ENTRY_FIELDS, fieldNames(entry), "an entry has exactly the allowed fields");
        assertEquals(VECTOR_HASH, entry.get("playerHash").asText(), "the player hash of the test vector");
        assertEquals("blackhole-ban-list-v1", entry.get("hashScheme").asText(), "the scheme is named");
    }

    @Test
    void everyAnswerIsNotStoredAndCarriesNoEtag() throws Exception {
        Answer ok = ask(HttpRequest.GET("/public/bans").basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));
        Answer unauthorized = ask(HttpRequest.GET("/public/bans/zzzzzzzzzzzzzzzzzzzzzz").header("X-Forwarded-Proto", "https"));

        assertEquals("private, no-store", ok.header("Cache-Control"), "a list answer is not stored");
        assertEquals("private, no-store", unauthorized.header("Cache-Control"), "an error answer is not stored either");
        assertNull(ok.header("ETag"), "there is no ETag to revalidate");
    }

    @Test
    void noRawIdentifierOfThePlayerOrTheSanctionReachesTheAnswer() throws Exception {
        String reason = "Reason " + UUID.randomUUID();
        String identifier = seedListed(VECTOR, reason);

        Answer answer = ask(HttpRequest.GET("/public/bans").basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));

        assertFalse(answer.body().contains(VECTOR.toString()), "the raw UUID is never published");
        assertFalse(answer.body().contains(identifier), "the punishment identifier is never published");
        assertFalse(answer.body().contains("ab".repeat(64)), "the stored owner value is never published");
    }

    @Test
    void thePlayerHashFilterReturnsTheOneEntryOfThatPlayer() throws Exception {
        String reason = "Reason " + UUID.randomUUID();
        seedListed(VECTOR, reason);

        Answer answer = ask(HttpRequest.GET("/public/bans?playerHash=" + VECTOR_HASH.toUpperCase())
                .basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));

        assertEquals(200, answer.status(), "the filter is a valid request");
        JsonNode items = answer.json().get("items");
        assertEquals(reason, entryWithReason(items, reason).get("reason").asText(), "the seeded entry of that player is listed");
        assertTrue(playerHashesOf(items).stream().allMatch(VECTOR_HASH::equals),
                "every entry that comes back belongs to that player only");
    }

    @Test
    void versionOneAndTheDefaultVersionGiveTheSameEntry() throws Exception {
        String reason = "Reason " + UUID.randomUUID();
        seedListed(VECTOR, reason);
        String hash = hashOfEntryWith(reason);

        Answer none = ask(HttpRequest.GET("/public/bans/" + hash).basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));
        Answer one = ask(HttpRequest.GET("/public/bans/" + hash).basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https")
                .header("X-API-VERSION", "1"));

        assertEquals(none.body(), one.body(), "no version and version 1 answer the same");
        assertEquals("blackhole-ban-list-v1", one.json().get("hashScheme").asText(), "version 1 names its scheme");
    }

    @Test
    void aVersionWithoutARouteNeverAnswersWithTheV1Hash() throws Exception {
        String reason = "Reason " + UUID.randomUUID();
        seedListed(VECTOR, reason);
        String hash = hashOfEntryWith(reason);

        Answer answer = ask(HttpRequest.GET("/public/bans/" + hash).basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https")
                .header("X-API-VERSION", "2"));

        assertNotEquals(200, answer.status(), "version 2 has no route yet, so nothing is answered with the v1 hash");
    }

    @Test
    void theSummaryNamesTheReasonWithItsTotalAndActiveCount() throws Exception {
        String reason = "Summary " + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            seedListed(null, reason);
        }

        Answer answer = ask(HttpRequest.GET("/public/bans/summary").basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));

        assertEquals(200, answer.status(), "the summary is read like the list");
        JsonNode row = summaryRow(answer.json().get("reasons"), reason);
        assertEquals(5, row.get("total").asInt(), "five entries of the reason are at the minimum, so they are counted");
        assertEquals(5, row.get("active").asInt(), "all five are active");
        assertFalse(row.get("suppressed").asBoolean(), "the row is not suppressed");
    }

    @Test
    void aProblemNamesTheRouteTemplateAndNotTheSubmittedHash() throws Exception {
        String submitted = "zzzzzzzzzzzzzzzzzzzzzz";

        Answer notFound = ask(HttpRequest.GET("/public/bans/" + submitted).basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));
        Answer unauthorized = ask(HttpRequest.GET("/public/bans/" + submitted).header("X-Forwarded-Proto", "https"));

        assertEquals(404, notFound.status(), "an authenticated unknown hash is not found");
        assertEquals("/public/bans/{hash}", notFound.json().get("instance").asText(), "the instance is the template, not the path");
        assertEquals("/public/bans/{hash}", unauthorized.json().get("instance").asText(), "the 401 names the template too");
        assertFalse(notFound.body().contains(submitted), "the submitted hash is not echoed");
    }

    @Test
    void anUnauthenticatedRequestToAnUnknownPathGetsTheChallengeBeforeAnyNotFound() throws Exception {
        Answer answer = ask(HttpRequest.GET("/public/bans/a/b/c").header("X-Forwarded-Proto", "https"));

        assertEquals(401, answer.status(), "credentials are checked before routing answers 404");
        assertEquals(CHALLENGE, answer.header("WWW-Authenticate"), "the challenge is sent");
        assertEquals("private, no-store", answer.header("Cache-Control"), "the refusal is not stored");
    }

    @Test
    void aFrameworkNotFoundUnderTheBanListIsNotStored() throws Exception {
        Answer answer = ask(HttpRequest.GET("/public/bans/a/b/c").basicAuth(CLIENT, PASSWORD).header("X-Forwarded-Proto", "https"));

        assertEquals(404, answer.status(), "an unrouted path under the prefix is not found");
        assertEquals("private, no-store", answer.header("Cache-Control"), "the framework's 404 is not stored either");
    }

    private Answer ask(HttpRequest<?> request) throws IOException {
        try (HttpClient client = HttpClient.create(this.server.getURL())) {
            HttpResponse<String> response = client.toBlocking().exchange(request, String.class);
            return new Answer(response.getStatus().getCode(), response.getHeaders(), response.getBody(String.class).orElse(""));
        } catch (HttpClientResponseException e) {
            HttpResponse<?> response = e.getResponse();
            return new Answer(response.getStatus().getCode(), response.getHeaders(), response.getBody(String.class).orElse(""));
        }
    }

    /** Writes a listed punishment straight into the table; a null player leaves the player hash null. */
    private String seedListed(UUID player, String reason) throws SQLException {
        String identifier = "bl-" + UUID.randomUUID();
        long now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO punishments (identifier, short_id, owner, section_key, permanent, lifted, kind, player_uuid,
                       public_reason, public_kind, public_hash, public_issued_day, public_expires_at)
                     VALUES (?, ?, ?, 'chat', true, false, 'PUNISHMENT', CAST(? AS uuid), ?, 'NETWORK_BAN', ?, ?, NULL)
                     """)) {
            statement.setString(1, identifier);
            statement.setString(2, UUID.randomUUID().toString().substring(0, 8));
            statement.setString(3, "ab".repeat(64));
            statement.setString(4, player == null ? null : player.toString());
            statement.setString(5, reason);
            statement.setString(6, "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 21));
            statement.setLong(7, now / Duration.ofDays(1).toMillis());
            statement.executeUpdate();
        }
        return identifier;
    }

    private String hashOfEntryWith(String reason) throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("SELECT public_hash FROM punishments WHERE public_reason = ?")) {
            statement.setString(1, reason);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static JsonNode entryWithReason(JsonNode items, String reason) {
        for (JsonNode item : items) {
            if (reason.equals(item.get("reason").asText())) {
                return item;
            }
        }
        throw new AssertionError("no entry carries the reason " + reason);
    }

    private static JsonNode summaryRow(JsonNode rows, String reason) {
        for (JsonNode row : rows) {
            if (reason.equals(row.get("reason").asText())) {
                return row;
            }
        }
        throw new AssertionError("no summary row carries the reason " + reason);
    }

    private static List<String> playerHashesOf(JsonNode items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false).map(item -> item.get("playerHash").asText()).toList();
    }

    private static String fieldNames(JsonNode object) {
        Iterator<String> names = object.fieldNames();
        java.util.List<String> fields = new java.util.ArrayList<>();
        names.forEachRemaining(fields::add);
        return String.join(",", fields);
    }

    /** A response reduced to what these tests read. */
    private record Answer(int status, io.micronaut.http.HttpHeaders headers, String body) {

        String header(String name) {
            return this.headers.get(name);
        }

        JsonNode json() {
            try {
                return JSON.readTree(this.body);
            } catch (IOException e) {
                throw new IllegalStateException("not JSON: " + this.body, e);
            }
        }
    }
}
