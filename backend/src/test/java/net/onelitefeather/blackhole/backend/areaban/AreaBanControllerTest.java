package net.onelitefeather.blackhole.backend.areaban;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The /area-bans routes against the real application: a moderator may ban one ASN for a week, an admin may ban
 * anything, and a lift needs a reason. Every test bans its own random ASN, so the tests stay independent.
 */
@Tag("integration")
class AreaBanControllerTest extends ApiIntegrationTest {

    private static final long SEVEN_DAYS_SECONDS = 7L * 24 * 60 * 60;
    private static final long ONE_DAY_SECONDS = 24L * 60 * 60;

    /** A random ASN in the 32-bit range, so no test bans an ASN another test has banned. */
    private static String freshAsn() {
        return Long.toString(1 + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 4_000_000_000L));
    }

    /** A ban request; the duration is left out for a permanent ban. */
    private static Map<String, Object> banRequest(String kind, String value, Long durationSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("value", value);
        body.put("reason", "repeated join guard refusals");
        if (durationSeconds != null) {
            body.put("durationSeconds", durationSeconds);
        }
        return body;
    }

    @Test
    void aModeratorBansAnAsnForSevenDays() {
        Reply reply = post("/area-bans", banRequest("ASN", "AS" + freshAsn(), SEVEN_DAYS_SECONDS), jwt.moderator());

        assertEquals(200, reply.status(), reply.body());
        assertTrue((Boolean) reply.json().get("active"), "the ban applies from the moment it is created");
        assertEquals("MANUAL", reply.json().get("source"), "a staff ban is recorded as manual");
    }

    @Test
    void aModeratorCannotBanACountry() {
        Reply reply = post("/area-bans", banRequest("COUNTRY", "DE", ONE_DAY_SECONDS), jwt.moderator());

        assertEquals(403, reply.status(), reply.body());
        assertEquals("FORBIDDEN_KIND", reply.json().get("kind"), "only an admin may ban a country");
    }

    @Test
    void aModeratorCannotBanAnAsnPermanently() {
        Reply reply = post("/area-bans", banRequest("ASN", freshAsn(), null), jwt.moderator());

        assertEquals(403, reply.status(), reply.body());
        assertEquals("FORBIDDEN_DURATION", reply.json().get("kind"), "a moderator may not ban for good");
    }

    @Test
    void theSamePermanentAsnBanSucceedsForAnAdmin() {
        Reply reply = post("/area-bans", banRequest("ASN", freshAsn(), null), jwt.admin());

        assertEquals(200, reply.status(), reply.body());
        assertNull(reply.json().get("expiresAt"), "an admin's ASN ban without a duration never expires");
    }

    @Test
    void anAdminBansACountry() {
        Reply reply = post("/area-bans", banRequest("COUNTRY", "RU", ONE_DAY_SECONDS), jwt.admin());

        assertEquals(200, reply.status(), reply.body());
        assertEquals("RU", reply.json().get("value"), "the country code is stored in uppercase");
    }

    @Test
    void aLiftWithoutAReasonIsRefused() {
        String id = createAsnBan(jwt.admin());

        Reply reply = post("/area-bans/" + id + "/lift", Map.of("reason", ""), jwt.moderator());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("VALIDATION", reply.json().get("kind"), "a lift needs a reason");
    }

    @Test
    void aLiftAnswersWithTheBanAndItsLiftTimeAndNoLongerApplies() {
        String id = createAsnBan(jwt.admin());

        Reply reply = post("/area-bans/" + id + "/lift", Map.of("reason", "the provider stopped the abuse"), jwt.moderator());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(id, reply.json().get("id"), "the lift answers with the same ban");
        assertNotNull(reply.json().get("liftedAt"), "the answer shows when the ban was lifted");
        assertFalse((Boolean) reply.json().get("active"), "a lifted ban no longer applies");
    }

    @Test
    void aLiftedBanIsListedAsHistoryWhenInactiveBansAreRequested() {
        String id = createAsnBan(jwt.admin());
        post("/area-bans/" + id + "/lift", Map.of("reason", "the provider stopped the abuse"), jwt.moderator());

        Reply reply = get("/area-bans?includeInactive=true", jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertTrue(listedIds(reply).contains(id), "the lifted ban stays readable as history");
    }

    @Test
    void aLiftedBanIsNotListedAmongTheBansThatApplyNow() {
        String id = createAsnBan(jwt.admin());
        post("/area-bans/" + id + "/lift", Map.of("reason", "the provider stopped the abuse"), jwt.moderator());

        Reply reply = get("/area-bans", jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertFalse(listedIds(reply).contains(id), "a lifted ban does not apply, so the default list leaves it out");
    }

    @Test
    void aMalformedAsnIsRefusedWithAValidationError() {
        Reply reply = post("/area-bans", banRequest("ASN", "AS12x", ONE_DAY_SECONDS), jwt.moderator());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("VALIDATION", reply.json().get("kind"), "an ASN is a number, with or without the AS prefix");
    }

    @Test
    void aMalformedCountryCodeIsRefusedWithAValidationError() {
        Reply reply = post("/area-bans", banRequest("COUNTRY", "DEU", ONE_DAY_SECONDS), jwt.admin());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("VALIDATION", reply.json().get("kind"), "a country is an ISO 3166 alpha-2 code");
    }

    /** The ids of the bans in a list answer. */
    private static List<Object> listedIds(Reply reply) {
        return ((List<?>) reply.json().get("bans")).stream().map(ban -> ((Map<?, ?>) ban).get("id")).collect(Collectors.toList());
    }

    /** Creates an admin's permanent ASN ban and returns its id. */
    private String createAsnBan(String token) {
        Reply created = post("/area-bans", banRequest("ASN", freshAsn(), null), token);
        assertEquals(200, created.status(), "the test ban is created: " + created.body());
        return (String) created.json().get("id");
    }
}
