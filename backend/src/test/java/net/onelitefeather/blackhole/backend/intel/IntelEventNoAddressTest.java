package net.onelitefeather.blackhole.backend.intel;

import ch.qos.logback.classic.Logger;
import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class IntelEventNoAddressTest {

    @Test
    void theFlaggedNoticeCarriesTheHitIdAndTheClassOnly() {
        LoginCheckFixture fixture = new LoginCheckFixture();

        fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP));

        List<RecordingEventTransport.Published> published = fixture.transport.published();
        assertEquals(1, published.size(), "one flagged notice is published");
        assertEquals("intel.vpn.flagged", published.getFirst().eventType(), "the notice is the VPN flagged event");
        var payload = published.getFirst().event().payload();
        assertEquals(fixture.store.allHits().getFirst().id().toString(), payload.get("hitId"), "the notice names the hit");
        assertEquals("HOSTING", payload.get("classification"), "the notice names the class");
        assertEquals(Set.of("hitId", "classification"), payload.keySet(), "no other field, so no address and no owner hash");
    }

    @Test
    void aPublishFailureIsLoggedWithoutTheAddress() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.transport.throwOnPublish(new IllegalStateException("broker is down"));
        SnapshotLogAppender appender = new SnapshotLogAppender();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(IntelLoginCheckService.class);
        logger.addAppender(appender);
        try {
            var result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP));

            assertInstanceOf(LoginCheckDTO.Response.class, result,
                    "the login check still answers when the notice cannot be published");
            assertFalse(appender.snapshot().isEmpty(), "the publish failure is logged");
            String logged = appender.snapshot().stream().map(event -> event.getFormattedMessage())
                    .reduce("", (a, b) -> a + "\n" + b);
            assertFalse(logged.contains(LoginCheckFixture.HOSTING_IP), "the failure log does not carry the address");
            assertFalse(logged.contains(LoginCheckFixture.OWNER), "the failure log does not carry the owner hash");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aReconnectLoopWithinThirtyMinutesPublishesOneNotice() {
        LoginCheckFixture fixture = new LoginCheckFixture();

        for (int i = 0; i < 5; i++) {
            fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP));
            fixture.clock.advance(Duration.ofMinutes(4));
        }

        assertEquals(1, fixture.transport.published().size(), "five flags inside 30 minutes make one notice");
        assertEquals(5, fixture.store.allHits().size(), "every flag is still stored as a hit");
    }
}
