package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Field;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardNotificationRenderTest {

    private static final GuardMessageRenderer RENDERER = new GuardMessageRenderer("https://panel.example.org");

    private static GuardEvent event(String json) {
        return GuardEvent.parse(json.getBytes(StandardCharsets.UTF_8))
                .orElseThrow(() -> new AssertionError("the fixture must be a guard event: " + json));
    }

    private static String rendered(MessageSpec spec) {
        StringBuilder text = new StringBuilder(spec.title()).append('\n').append(spec.footer());
        for (Field field : spec.fields()) {
            text.append('\n').append(field.name()).append(' ').append(field.value());
        }
        return text.toString();
    }

    @Test
    void aLevelChangeShowsThePreviousAndNewLevelAndItsSource() {
        String text = rendered(RENDERER.render(event(
                "{\"eventId\":\"e1\",\"eventType\":\"guard.level_changed\",\"occurredAt\":1,\"payload\":"
                        + "{\"level\":\"STRICT\",\"previous\":\"OFF\",\"source\":\"UNATTENDED\"}}")));

        assertTrue(text.contains("OFF → STRICT"), "the message names the previous and the new level: " + text);
        assertTrue(text.contains("Nobody from the team online"), "the message names the unattended source: " + text);
    }

    @Test
    void anAsnAreaBanShowsTheAsnAndItsExpiry() {
        String text = rendered(RENDERER.render(event(
                "{\"eventId\":\"e2\",\"eventType\":\"area_ban.created\",\"occurredAt\":1,\"payload\":"
                        + "{\"kind\":\"ASN\",\"value\":\"16276\",\"source\":\"AUTO_BURST\",\"createdAt\":1,\"expiresAt\":7201000}}")));

        assertTrue(text.contains("ASN 16276"), "the message names the ASN: " + text);
        assertTrue(text.contains("Automatic, burst rule"), "an automatic ban is marked as automatic: " + text);
        assertTrue(text.contains("<t:7201:f>"), "the message states the expiry: " + text);
    }

    @Test
    void aCountryAreaBanWithoutExpiryShowsTheCountryAndNeverExpires() {
        String text = rendered(RENDERER.render(event(
                "{\"eventId\":\"e3\",\"eventType\":\"area_ban.created\",\"occurredAt\":1,\"payload\":"
                        + "{\"kind\":\"COUNTRY\",\"value\":\"DE\",\"source\":\"MANUAL\",\"createdAt\":1}}")));

        assertTrue(text.contains("Country · Land DE"), "the message names the country: " + text);
        assertTrue(text.contains("Never · Nie"), "a ban without expiry says so: " + text);
    }

    @Test
    void aLiftedAreaBanShowsTheArea() {
        String text = rendered(RENDERER.render(event(
                "{\"eventId\":\"e4\",\"eventType\":\"area_ban.lifted\",\"occurredAt\":1,\"payload\":"
                        + "{\"kind\":\"ASN\",\"value\":\"16276\",\"liftedAt\":2}}")));

        assertTrue(text.contains("ASN 16276"), "the lifted area is named: " + text);
        assertTrue(text.contains("lifted"), "the title says the ban was lifted: " + text);
    }

    @Test
    void aDenialSummaryShowsTheTotalAndTheTopAsnsWithOrganisationAndCountry() {
        String text = rendered(RENDERER.render(event(
                "{\"eventId\":\"e5\",\"eventType\":\"guard.denials.summary\",\"occurredAt\":1,\"payload\":"
                        + "{\"windowStart\":1,\"windowEnd\":600001,\"total\":57,\"top\":["
                        + "{\"asn\":\"16276\",\"count\":42,\"organisation\":\"OVH SAS\",\"countryCode\":\"FR\"},"
                        + "{\"asn\":\"24940\",\"count\":15,\"organisation\":\"Hetzner\",\"countryCode\":\"DE\"}]}}")));

        assertTrue(text.contains("57"), "the total is shown: " + text);
        assertTrue(text.contains("AS16276") && text.contains("OVH SAS") && text.contains("FR") && text.contains("42"),
                "the first ASN shows its organisation, country and count: " + text);
        assertTrue(text.indexOf("AS16276") < text.indexOf("AS24940"), "the ASNs are listed in the order given: " + text);
    }

    @Test
    void noRenderedMessageCarriesAnAddressOwnerHashOrPlayerName() {
        String[] fixtures = {
                "{\"eventId\":\"p1\",\"eventType\":\"guard.level_changed\",\"payload\":{\"level\":\"LOCKDOWN\",\"previous\":\"OFF\","
                        + "\"source\":\"MANUAL\",\"ip\":\"203.0.113.7\",\"ownerHash\":\"owner-hash-abc\",\"playerName\":\"Steve\"}}",
                "{\"eventId\":\"p2\",\"eventType\":\"area_ban.created\",\"payload\":{\"kind\":\"ASN\",\"value\":\"16276\","
                        + "\"source\":\"MANUAL\",\"createdAt\":1,\"ip\":\"203.0.113.7\",\"ownerHash\":\"owner-hash-abc\",\"playerName\":\"Steve\"}}",
                "{\"eventId\":\"p3\",\"eventType\":\"area_ban.lifted\",\"payload\":{\"kind\":\"ASN\",\"value\":\"16276\",\"liftedAt\":2,"
                        + "\"ip\":\"203.0.113.7\",\"ownerHash\":\"owner-hash-abc\",\"playerName\":\"Steve\"}}",
                "{\"eventId\":\"p4\",\"eventType\":\"guard.denials.summary\",\"payload\":{\"windowStart\":1,\"windowEnd\":2,\"total\":1,"
                        + "\"ip\":\"203.0.113.7\",\"ownerHash\":\"owner-hash-abc\",\"playerName\":\"Steve\","
                        + "\"top\":[{\"asn\":\"16276\",\"count\":1,\"organisation\":\"OVH SAS\",\"countryCode\":\"FR\"}]}}",
        };
        for (String fixture : fixtures) {
            String text = rendered(RENDERER.render(event(fixture)));
            assertFalse(text.contains("203.0.113.7"), "no address in the message: " + text);
            assertFalse(text.contains("owner-hash-abc"), "no owner hash in the message: " + text);
            assertFalse(text.contains("Steve"), "no player name in the message: " + text);
        }
    }
}
