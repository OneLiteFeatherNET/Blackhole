package net.onelitefeather.blackhole.backend.publicbans.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanListAttributionTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureLog() {
        this.logger = (Logger) LoggerFactory.getLogger(BanListAttribution.class);
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void releaseLog() {
        this.logger.detachAppender(this.log);
    }

    @Test
    void aSuccessfulAnswerLogsTheClientName() {
        new BanListAttribution(this.meters).answered("website", null, "list", 200);

        assertTrue(messages().stream().anyMatch(line -> line.contains("client=website") && line.contains("status=200")),
                "the access line names the client");
    }

    @Test
    void aSuccessfulAnswerCountsOneRequestForTheClient() {
        new BanListAttribution(this.meters).answered("website", null, "list", 200);

        assertEquals(1.0, this.meters.get("blackhole.ban.list.requests").tags("client", "website", "route", "list", "status", "200")
                .counter().count(), "the metric counts the request under the client label");
    }

    @Test
    void aFailedAttemptLogsUnauthenticatedWithTheReasonClassOnly() {
        new BanListAttribution(this.meters).answered(null, "unknown-client", "entry", 401);

        assertTrue(messages().stream().anyMatch(line -> line.contains("client=unauthenticated")
                        && line.contains("reason=unknown-client")),
                "a failure is attributed to no client, with its reason class");
    }

    @Test
    void noLogLineCarriesAnAuthorizationValue() {
        new BanListAttribution(this.meters).answered(null, "bad-password", "list", 401);

        assertFalse(messages().stream().anyMatch(line -> line.contains("Basic ") || line.contains("Authorization")),
                "the credential is never written to the log");
    }

    private java.util.List<String> messages() {
        return this.log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
