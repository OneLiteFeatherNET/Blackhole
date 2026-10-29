package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import net.onelitefeather.blackhole.backend.ipban.IpBanRecord;
import net.onelitefeather.blackhole.backend.ipban.IpBanSource;
import net.onelitefeather.blackhole.backend.ipban.IpBanStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedServiceTest {

    private static final long NOW = 1_780_000_000_000L;
    private static final String TOKEN = "feed-token-under-test";
    private static final InetAddress PEER = peer("203.0.113.9");

    @TempDir
    Path secrets;

    /** A store that answers the rows it holds, or fails on demand, so a rebuild error can be made to happen. */
    static final class ScriptedStore implements IpBanStore {

        final List<IpBanRecord> rows = new ArrayList<>();
        boolean failing;

        @Override
        public List<IpBanRecord> active(long now) {
            if (this.failing) {
                throw new IllegalStateException("database is down");
            }
            return this.rows.stream().filter(row -> row.isActive(now)).toList();
        }

        @Override
        public Marker marker() {
            return new Marker(this.rows.size(), 0, 0);
        }

        @Override
        public java.util.Optional<IpBanRecord> find(UUID id) {
            return java.util.Optional.empty();
        }

        @Override
        public List<IpBanRecord> newest(int limit) {
            return List.of();
        }

        @Override
        public boolean insert(IpBanRecord record) {
            return this.rows.add(record);
        }

        @Override
        public boolean lift(UUID id, UUID liftedBy, String reason, long at) {
            return false;
        }

        @Override
        public int liftExpired(String range, long now) {
            return 0;
        }

        @Override
        public int deleteClosedBefore(long cutoff) {
            return 0;
        }
    }

    private static InetAddress peer(String text) {
        try {
            return InetAddress.getByName(text);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static IpBanRecord ban(String cidr) {
        return new IpBanRecord(UUID.randomUUID(), IntelPrefix.parseCidr(cidr).orElseThrow(), "reason", null, IpBanSource.PANEL,
                null, null, null, NOW, null, null, null, null);
    }

    private FeedService service(ScriptedStore store, String allowed) throws Exception {
        Files.writeString(this.secrets.resolve("edge"), HexFormat.of().formatHex(FeedTokenRegistry.sha256(TOKEN)));
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FeedTokenRegistry tokens = new FeedTokenRegistry("edge", this.secrets, Set.of("test"));
        FeedCache cache = new FeedCache(store, new IpBanProtection(""), List.of(), clock, meters);
        return new FeedService(tokens, new FeedAllowList(allowed), cache, meters, clock);
    }

    @Test
    void anUnknownTokenAnswersNotFound() throws Exception {
        FeedService service = service(new ScriptedStore(), "");

        assertEquals(FeedService.Outcome.NOT_FOUND, service.serve("wrong-token", "ips", true, null, PEER).outcome(),
                "an unknown token is a plain 404");
    }

    @Test
    void aRefusedSourceAnswersTheSameNotFound() throws Exception {
        FeedService service = service(new ScriptedStore(), "198.51.100.0/24");

        FeedService.Answer answer = service.serve(TOKEN, "ips", true, null, PEER);

        assertEquals(FeedService.Outcome.NOT_FOUND, answer.outcome(), "a source outside the allow-list gets the same 404");
        assertNull(answer.body(), "and no body");
    }

    @Test
    void anUnknownFeedAnswersNotFound() throws Exception {
        FeedService service = service(new ScriptedStore(), "");

        assertEquals(FeedService.Outcome.NOT_FOUND, service.serve(TOKEN, "bogus", true, null, PEER).outcome(),
                "an unknown feed name is a 404");
    }

    @Test
    void aFeedBeforeTheFirstGoodBuildAnswersUnavailable() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.failing = true;
        FeedService service = service(store, "");

        assertEquals(FeedService.Outcome.UNAVAILABLE, service.serve(TOKEN, "ips", true, null, PEER).outcome(),
                "with no good build the firewall keeps its own table, so the answer is 503");
    }

    @Test
    void aFailedRebuildKeepsTheLastGoodFeed() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.insert(ban("203.0.113.7/32"));
        FeedService service = service(store, "");
        assertEquals(FeedService.Outcome.OK, service.serve(TOKEN, "ips", true, null, PEER).outcome(), "the first build succeeds");

        store.failing = true;
        FeedService.Answer answer = service.serve(TOKEN, "ips", true, null, PEER);

        assertEquals(FeedService.Outcome.OK, answer.outcome(), "a later failure still serves the last good feed");
        assertTrue(answer.body().contains("203.0.113.7"), "and that feed still holds the ban");
    }

    @Test
    void theSameFeedAnswersNotModifiedForItsEtag() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.insert(ban("203.0.113.7/32"));
        FeedService service = service(store, "");
        String etag = service.serve(TOKEN, "ips", true, null, PEER).etag();

        FeedService.Answer answer = service.serve(TOKEN, "ips", true, etag, PEER);

        assertEquals(FeedService.Outcome.NOT_MODIFIED, answer.outcome(), "a matching If-None-Match gets 304");
        assertNull(answer.body(), "and no body");
    }

    @Test
    void theCommentAndCommentFreeModesHaveDifferentEtags() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.insert(ban("203.0.113.7/32"));
        FeedService service = service(store, "");

        String withComments = service.serve(TOKEN, "ips", true, null, PEER).etag();
        String withoutComments = service.serve(TOKEN, "ips", false, null, PEER).etag();

        assertNotEquals(withComments, withoutComments, "each mode has its own ETag");
    }
}
