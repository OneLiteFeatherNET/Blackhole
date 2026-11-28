package net.onelitefeather.blackhole.backend.firewall;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedLoggingTest {

    private static final String TOKEN = "feed-token-under-test";
    private static final String WRONG = "not-the-token-but-still-secret-text";

    @TempDir
    Path secrets;

    @Test
    void aFeedRequestLogsTheRouteTemplateAndNeverTheToken() throws Exception {
        Files.writeString(this.secrets.resolve("edge"), HexFormat.of().formatHex(FeedTokenRegistry.sha256(TOKEN)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Clock clock = Clock.systemUTC();
        FeedService service = new FeedService(new FeedTokenRegistry("edge", this.secrets, Set.of("test")),
                new FeedAllowList(""),
                new FeedCache(new FeedTestStore(), new IpBanProtection(""), List.of(), clock, meters),
                meters, clock);
        Logger logger = (Logger) LoggerFactory.getLogger(FeedService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            InetAddress peer = InetAddress.getByName("203.0.113.9");
            service.serve(TOKEN, "ips", false, null, peer);
            service.serve(WRONG, "networks", true, null, peer);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertFalse(lines.isEmpty(), "the request is logged at debug");
        for (String line : lines) {
            assertFalse(line.contains(TOKEN), "the valid token is never logged: " + line);
            assertFalse(line.contains(WRONG), "a presented wrong token is never logged either: " + line);
        }
        assertTrue(lines.stream().anyMatch(line -> line.contains("/firewall/feed/{token}/{feed}")),
                "the route template is what appears in the log");
    }

    @Test
    void theMetricTagsNameTheFeedClientAndOutcomeButNoToken() throws Exception {
        Files.writeString(this.secrets.resolve("edge"), HexFormat.of().formatHex(FeedTokenRegistry.sha256(TOKEN)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Clock clock = Clock.systemUTC();
        FeedService service = new FeedService(new FeedTokenRegistry("edge", this.secrets, Set.of("test")),
                new FeedAllowList(""),
                new FeedCache(new FeedTestStore(), new IpBanProtection(""), List.of(), clock, meters),
                meters, clock);

        service.serve(TOKEN, "ips", false, null, InetAddress.getByName("203.0.113.9"));

        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertFalse(tag.getValue().contains(TOKEN), "no metric tag carries the token: " + tag);
            });
        }
        assertTrue(meters.get("blackhole.ipban.feed.requests").tags("client", "edge").counter() != null,
                "the request is counted by client");
    }
}
