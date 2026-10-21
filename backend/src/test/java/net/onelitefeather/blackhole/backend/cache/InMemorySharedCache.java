package net.onelitefeather.blackhole.backend.cache;

import io.micronaut.core.type.Argument;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link SharedCache} that keeps values in a map instead of Redis, for unit tests. TTLs are not
 * enforced; tests check which keys are present and evicted.
 */
public final class InMemorySharedCache extends SharedCache {

    private final Map<String, Object> entries = new HashMap<>();

    public InMemorySharedCache() {
        super(null, null, Duration.ofMillis(200));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Argument<T> type) {
        return Optional.ofNullable((T) this.entries.get(key));
    }

    @Override
    public <T> void put(String key, Argument<T> type, T value, Duration ttl) {
        this.entries.put(key, value);
    }

    @Override
    public void evict(String... keys) {
        for (String key : keys) {
            this.entries.remove(key);
        }
    }

    public boolean contains(String key) {
        return this.entries.containsKey(key);
    }
}
