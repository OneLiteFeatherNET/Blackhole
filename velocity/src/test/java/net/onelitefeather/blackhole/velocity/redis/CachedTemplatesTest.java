package net.onelitefeather.blackhole.velocity.redis;

import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The proxy's view of {@code blackhole:cache:v1:templates:all}, over an in-memory cache. */
class CachedTemplatesTest {

    /** The key and format the backend's {@code PunishmentTemplateCache.putAll} writes. */
    private static final String KEY = "blackhole:cache:v1:templates:all";

    /** A cache that answers from a map and remembers which keys were asked for. */
    private static final class FakeCache implements SharedCacheReader {

        private final Map<String, String> entries;
        private final List<String> asked = new ArrayList<>();
        private RuntimeException failure;

        FakeCache(Map<String, String> entries) {
            this.entries = entries;
        }

        @Override
        public Optional<String> readShared(String key) {
            this.asked.add(key);
            if (this.failure != null) {
                throw this.failure;
            }
            return Optional.ofNullable(this.entries.get(key));
        }
    }

    private static String list(String key, String section) {
        return "[{\"identifier\":\"" + UUID.randomUUID() + "\",\"key\":\"" + key + "\",\"reason\":\"r\",\"section\":\"" + section
                + "\",\"steps\":[{\"duration\":\"P1D\",\"eloCost\":100}]}]";
    }

    @Test
    void theKeyIsTheOneTheBackendWrites() {
        assertEquals(KEY, RedisTopology.TEMPLATES_ALL_KEY, "a different key would never find the backend's entry");
    }

    @Test
    void aStoredListIsReadFromThatKey() {
        FakeCache cache = new FakeCache(Map.of(KEY, list("hacking", "network")));

        List<PunishTemplateResponse> templates = new CachedTemplates(cache).read().orElseThrow();

        assertEquals(List.of(KEY), cache.asked, "exactly the shared template list is read");
        assertEquals(1, templates.size());
        assertEquals("hacking", templates.getFirst().getKey());
        assertEquals("network", templates.getFirst().getSection());
    }

    @Test
    void anEmptyStoredListIsAHitNotAMiss() {
        FakeCache cache = new FakeCache(Map.of(KEY, "[]"));

        assertEquals(Optional.of(List.of()), new CachedTemplates(cache).read(), "no templates is a valid answer");
    }

    @Test
    void aFieldTheProxyDoesNotKnowYetIsIgnored() {
        FakeCache cache = new FakeCache(Map.of(KEY,
                "[{\"identifier\":\"" + UUID.randomUUID() + "\",\"key\":\"spam\",\"reason\":\"r\",\"section\":\"chat\","
                        + "\"steps\":[],\"addedByAFutureBackend\":true}]"));

        assertEquals("spam", new CachedTemplates(cache).read().orElseThrow().getFirst().getKey(),
                "a newer backend must not break an older proxy");
    }

    @Test
    void aMissIsEmpty() {
        assertTrue(new CachedTemplates(new FakeCache(Map.of())).read().isEmpty(), "nothing cached yet");
    }

    @Test
    void textThatIsNotJsonIsEmpty() {
        assertTrue(new CachedTemplates(new FakeCache(Map.of(KEY, "not json"))).read().isEmpty(), "corrupt payload");
    }

    @Test
    void jsonThatIsNotATemplateListIsEmpty() {
        assertTrue(new CachedTemplates(new FakeCache(Map.of(KEY, "{\"content\":[]}"))).read().isEmpty(),
                "an object is not the list the backend caches");
    }

    @Test
    void aFailingCacheIsEmptyInsteadOfAnException() {
        FakeCache cache = new FakeCache(Map.of(KEY, list("hacking", "network")));
        cache.failure = new IllegalStateException("connection reset");

        assertTrue(new CachedTemplates(cache).read().isEmpty(), "the caller falls back to the API");
    }
}
