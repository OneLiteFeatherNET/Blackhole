package net.onelitefeather.blackhole.backend.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.SpanData;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate for the span privacy rule: three requests that carry identifiers and message content (a player UUID in
 * the path, a report with hashed players, a chat message) must produce spans without any of them. Spans are read
 * from an in-memory exporter behind the production allow-list; nothing leaves the process.
 */
class SpanPrivacyScanTest extends ApiIntegrationTest {

    private static final String CHAT_TEXT = "a private chat sentence number 4711";
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern IPV4_SHAPE = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern IPV6_SHAPE = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}\\b");
    private static final Pattern BAN_ID_SHAPE = Pattern.compile("\\b[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}\\b");
    private static final Pattern SNOWFLAKE_SHAPE = Pattern.compile("\\b\\d{17,20}\\b");
    private static final Pattern HASH_SHAPE = Pattern.compile("[0-9a-fA-F]{128}");

    @Test
    void noSpanValueHoldsAPlayerUuidFromThePath() throws InterruptedException {
        runScenario();

        assertNoMatch(UUID_SHAPE, "a UUID");
    }

    @Test
    void noSpanValueHoldsAnIpAddress() throws InterruptedException {
        runScenario();

        assertNoMatch(IPV4_SHAPE, "an IPv4 address");
        assertNoMatch(IPV6_SHAPE, "an IPv6 address");
    }

    @Test
    void noSpanValueHoldsABanIdShape() throws InterruptedException {
        runScenario();

        assertNoMatch(BAN_ID_SHAPE, "a Ban ID");
    }

    @Test
    void noSpanValueHoldsADiscordSnowflake() throws InterruptedException {
        runScenario();

        assertNoMatch(SNOWFLAKE_SHAPE, "a Discord snowflake");
    }

    @Test
    void noSpanValueHoldsAPlayerOrReporterHash() throws InterruptedException {
        runScenario();

        assertNoMatch(HASH_SHAPE, "a sha-512 player hash");
    }

    @Test
    void noSpanCarriesTheTextOfAChatMessage() throws InterruptedException {
        runScenario();

        for (SpanData span : spans()) {
            assertFalse(allText(span).contains(CHAT_TEXT), "span " + span.getName() + " carries chat message content");
        }
    }

    @Test
    void everySpanAttributeKeyIsOnTheAllowList() throws InterruptedException {
        runScenario();

        for (SpanData span : spans()) {
            for (String key : keys(span.getAttributes())) {
                assertTrue(AttributeAllowListExporter.ALLOWED_ATTRIBUTE_KEYS.contains(key),
                        "span " + span.getName() + " exports attribute " + key + " which is not on the allow-list");
            }
        }
    }

    /** One request per privacy risk: a UUID in the path, a report with two hashed players, a chat message. */
    private void runScenario() throws InterruptedException {
        String token = this.jwt.admin();
        get("/punishment/" + UUID.randomUUID() + "/evidence/count", token);
        post("/report", Map.of(
                "reporterHash", "c1".repeat(64),
                "reportedHash", "c2".repeat(64),
                "category", "CHEATING",
                "metaData", Map.of()), token);
        post("/elo/chat", Map.of("owner", "d1".repeat(64), "message", CHAT_TEXT), token);
        context.getBean(TraceCollectingCustomizer.class).awaitServerSpans(3);
    }

    private List<SpanData> spans() {
        return this.context.getBean(TraceCollectingCustomizer.class).exporter().getFinishedSpanItems();
    }

    private void assertNoMatch(Pattern shape, String what) {
        List<SpanData> spans = spans();
        assertTrue(!spans.isEmpty(), "the scenario must produce spans to scan");
        for (SpanData span : spans) {
            String text = allText(span);
            assertFalse(shape.matcher(text).find(),
                    "span " + span.getName() + " holds " + what + ": " + text);
        }
    }

    /** The span name and every attribute key and value, joined; the text the scan looks through. */
    private static String allText(SpanData span) {
        StringBuilder text = new StringBuilder(span.getName());
        span.getAttributes().forEach((key, value) -> text.append(' ').append(key.getKey()).append(' ').append(value));
        return text.toString();
    }

    private static List<String> keys(Attributes attributes) {
        return attributes.asMap().keySet().stream().map(key -> key.getKey()).toList();
    }
}
