package net.onelitefeather.blackhole.backend.publicapi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micronaut.http.HttpMethod;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Runs a full sign-in and a full filing with every log line captured, then checks that no line carries the solved
 * payload, the derived key or the nonce. The challenge is a credential of one use; a log line must never replay it.
 */
class PublicChallengeLogIntegrationTest extends PublicApiTestBase {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final SnapshotLogAppender appender = new SnapshotLogAppender();
    private Level previousLevel;

    @Override
    protected Map<String, Object> extraProperties() {
        Map<String, Object> properties = new HashMap<>(super.extraProperties());
        properties.put("blackhole.public.challenge.enabled", true);
        properties.put("blackhole.public.challenge.cost", 10);
        return properties;
    }

    @BeforeEach
    void captureLogs() {
        this.previousLevel = this.root.getLevel();
        this.root.setLevel(Level.ALL);
        this.appender.start();
        this.root.addAppender(this.appender);
    }

    @AfterEach
    void releaseLogs() {
        this.root.detachAppender(this.appender);
        this.root.setLevel(this.previousLevel);
    }

    @Test
    void aSignInAndAFilingLeaveTheChallengeOutOfTheLogs() throws Exception {
        String punishment = punish("e1".repeat(64), "chal-logs");
        Raw signInChallenge = call(HttpMethod.GET, "/public/challenge?purpose=sign-in", null, null);
        String signInPayload = ChallengeSolver.solve(signInChallenge.body());
        String signInNonce = ChallengeSolver.nonceOf(signInChallenge.body());
        Map<String, Object> login = new HashMap<>();
        login.put("banId", banIdOf(punishment));
        login.put("altcha", signInPayload);
        Raw session = call(HttpMethod.POST, "/public/session", null, login);
        assertEquals(200, session.status(), session.body());
        String token = (String) session.json().get("token");

        Raw appealChallenge = call(HttpMethod.GET, "/public/challenge?purpose=appeal", null, null);
        String appealPayload = ChallengeSolver.solve(appealChallenge.body());
        String appealNonce = ChallengeSolver.nonceOf(appealChallenge.body());
        Map<String, Object> filing = new HashMap<>();
        filing.put("conditionsVersion", 1);
        filing.put("statement", "I was banned by mistake.");
        filing.put("altcha", appealPayload);
        Raw filed = playerPost("/public/appeal", token, filing);
        assertEquals(200, filed.status(), filed.body());

        String lines = this.appender.snapshot().stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
        assertFalse(lines.contains(signInPayload), "no log line carries the sign-in payload");
        assertFalse(lines.contains(appealPayload), "no log line carries the filing payload");
        assertFalse(lines.contains(signInNonce), "no log line carries the sign-in nonce");
        assertFalse(lines.contains(appealNonce), "no log line carries the filing nonce");
        assertFalse(this.appender.snapshot().isEmpty(), "the capture recorded lines, so the checks above had something to read");
    }
}
