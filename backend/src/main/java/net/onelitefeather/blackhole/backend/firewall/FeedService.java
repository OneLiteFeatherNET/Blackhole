package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers a feed request. The token, the feed name and the source address are checked here, in one place: an unknown
 * token, an unknown feed and a refused source all answer {@link Outcome#NOT_FOUND}, so the answers do not reveal which check
 * failed. A feed that is not built yet answers {@link Outcome#UNAVAILABLE}.
 */
@Singleton
public class FeedService {

    private static final Logger LOG = LoggerFactory.getLogger(FeedService.class);
    private static final List<String> FEEDS = List.of("ips", "networks");
    private static final String UNKNOWN_CLIENT = "unknown";
    /** The only form of the path that may appear in a log line. */
    static final String ROUTE = "/firewall/feed/{token}/{feed}";

    /** The status an answer maps to. */
    public enum Outcome {
        OK(200), NOT_MODIFIED(304), NOT_FOUND(404), UNAVAILABLE(503);

        private final int status;

        Outcome(int status) {
            this.status = status;
        }

        public int status() {
            return this.status;
        }
    }

    /**
     * @param outcome the status
     * @param body    the feed text, present only for {@link Outcome#OK}
     * @param etag    the strong ETag of the answered body, present for {@link Outcome#OK} and {@link Outcome#NOT_MODIFIED}
     */
    public record Answer(Outcome outcome, String body, String etag) {
    }

    private final FeedTokenRegistry tokens;
    private final FeedAllowList allowList;
    private final FeedCache cache;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, Long> lastFetch = new ConcurrentHashMap<>();

    public FeedService(FeedTokenRegistry tokens, FeedAllowList allowList, FeedCache cache, MeterRegistry meters, Clock clock) {
        this.tokens = tokens;
        this.allowList = allowList;
        this.cache = cache;
        this.meters = meters;
        this.clock = clock;
        long started = clock.millis();
        for (String client : tokens.clientNames()) {
            this.lastFetch.put(client, started);
            meters.gauge("blackhole.ipban.feed.last_fetch_seconds", Tags.of("client", client), this,
                    service -> (service.clock.millis() - service.lastFetch.getOrDefault(client, started)) / 1000.0);
        }
    }

    /**
     * @param token       the token from the path
     * @param feed        the feed name from the path: {@code ips} or {@code networks}
     * @param comments    true to include the comment lines
     * @param ifNoneMatch the If-None-Match header, or null
     * @param peer        the TCP peer address, or null when unknown
     */
    public Answer serve(String token, String feed, boolean comments, String ifNoneMatch, InetAddress peer) {
        if (!FEEDS.contains(feed)) {
            return answer(Outcome.NOT_FOUND, UNKNOWN_CLIENT, feed, null, null);
        }
        Optional<String> client = this.tokens.clientOf(token);
        if (client.isEmpty() || !this.allowList.allows(peer)) {
            return answer(Outcome.NOT_FOUND, UNKNOWN_CLIENT, feed, null, null);
        }
        String name = client.get();
        Optional<FeedCache.Built> built = this.cache.current();
        if (built.isEmpty()) {
            return answer(Outcome.UNAVAILABLE, name, feed, null, null);
        }
        FeedCache.Variant variant = built.get().feed(feed).variant(comments);
        this.lastFetch.put(name, this.clock.millis());
        if (matches(ifNoneMatch, variant.etag())) {
            return answer(Outcome.NOT_MODIFIED, name, feed, variant.etag(), null);
        }
        return answer(Outcome.OK, name, feed, variant.etag(), new String(variant.body(), StandardCharsets.US_ASCII));
    }

    /**
     * Counts the answer by feed, client and status. The log names the route template and the client, never the token or the
     * raw path.
     */
    private Answer answer(Outcome outcome, String client, String feed, String etag, String body) {
        this.meters.counter("blackhole.ipban.feed.requests", "feed", feed, "client", client, "outcome",
                String.valueOf(outcome.status())).increment();
        LOG.debug("route={} client={} outcome={}", ROUTE, client, outcome.status());
        return new Answer(outcome, body, etag);
    }

    /** RFC 9110 If-None-Match: a list of entity tags, or {@code *}. A weak tag matches its strong form. */
    static boolean matches(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        return Arrays.stream(header.split(","))
                .map(String::strip)
                .anyMatch(tag -> tag.equals("*") || tag.equals(etag) || tag.equals("W/" + etag));
    }
}
