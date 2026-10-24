package net.onelitefeather.blackhole.backend.publicapi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Ban ID opens the portal and appears nowhere else: not in a response other than the reveal, a URL, a log line, an event or a meter. */
@Tag("integration")
class PublicBanIdNoLeakIntegrationTest extends PublicApiTestBase {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @AfterEach
    void releaseLogs() {
        this.root.detachAppender(this.appender);
        if (this.previousLevel != null) {
            this.root.setLevel(this.previousLevel);
        }
    }

    private static void assertNoBanId(String banId, String where, String text) {
        assertFalse(text.contains(banId), "the Ban ID must not appear in " + where + ": " + text.replace(banId, "<ban id>"));
    }

    @Test
    void theWholeLoginFileAndMessageFlowKeepsTheBanIdOutOfEverythingButTheLoginBody() {
        String punishment = punish("aa".repeat(64), "pub-noleak");
        String banId = banIdOf(punishment);
        this.previousLevel = this.root.getLevel();
        this.root.setLevel(Level.ALL);
        this.appender.start();
        this.root.addAppender(this.appender);

        Raw login = login(banId);
        Raw failedWithBanIdInBrokenBody = call(HttpMethod.POST, "/public/session", null, "{\"banId\": \"" + banId + "\" oops");
        Raw typedWithNoise = login(" #" + banId.toLowerCase());
        String token = (String) login.json().get("token");
        Raw filed = playerPost("/public/appeal", token, Map.of("statement", "Please review my ban."));
        Raw rejected = playerPost("/public/messages", token, Map.of("text", "my ban id is #" + banId));
        Raw posted = playerPost("/public/messages", token, Map.of("text", "thank you"));
        Raw facts = playerGet("/public/punishment", token);
        Raw conversation = playerGet("/public/conversation", token);
        Raw appeal = playerGet("/public/appeal", token);
        Raw session = playerGet("/public/session", token);
        markLeaked(punishment);
        Raw limited = login(banId);

        assertEquals(200, login.status(), login.body());
        assertEquals(401, failedWithBanIdInBrokenBody.status());
        assertEquals(200, typedWithNoise.status());
        assertEquals(422, rejected.status(), "a message with a Ban ID is refused");
        assertEquals(200, posted.status());
        assertEquals("LIMITED", limited.json().get("scope"));
        for (Raw response : new Raw[]{login, failedWithBanIdInBrokenBody, filed, rejected, posted, facts, conversation, appeal, session, limited}) {
            assertNoBanId(banId, "a response", response.body());
        }
        assertFalse(this.appender.list.isEmpty(), "precondition: the flow logs something");
        for (ILoggingEvent line : this.appender.list) {
            assertNoBanId(banId, "a log line of " + line.getLoggerName(), line.getFormattedMessage());
            assertNoBanId(banId, "a log throwable", String.valueOf(line.getThrowableProxy()));
            assertNoBanId(banId, "a log argument", String.valueOf(java.util.Arrays.toString(line.getArgumentArray())));
        }
        assertTrue(events().events().size() > 0, "precondition: the flow published events");
        events().events().forEach(event -> assertNoBanId(banId, "event " + event.type(), event.type() + event.payload()));
        this.context.getBean(MeterRegistry.class).getMeters()
                .forEach(meter -> assertNoBanId(banId, "meter " + meter.getId().getName(), meter.getId().toString()));
        assertTrue(events().eventsOfType("appeal.submitted").size() == 1);
    }

    @Test
    void noPublicRouteTakesAnythingButTheSessionInThePath() {
        String punishment = punish("ab".repeat(64), "pub-noleak-url");
        String banId = banIdOf(punishment);
        String token = sessionFor(punishment);

        Raw asPath = call(HttpMethod.GET, "/public/session/" + banId, token, null);
        Raw asQuery = call(HttpMethod.GET, "/public/session?banId=" + banId, token, null);

        assertTrue(asPath.status() == 403 || asPath.status() == 404, "there is no such route, got " + asPath.status());
        assertEquals(200, asQuery.status(), "a stray query parameter is ignored, never echoed");
        assertNoBanId(banId, "an answer", asQuery.body());
    }
}
