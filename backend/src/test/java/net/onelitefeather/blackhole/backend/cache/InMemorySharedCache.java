package net.onelitefeather.blackhole.backend.cache;

import io.micronaut.core.type.Argument;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A {@link SharedCache} that keeps values in a map instead of Redis, for unit tests. TTLs are not
 * enforced; tests check which keys are present and evicted.
 */
public final class InMemorySharedCache extends SharedCache {

    private final Map<String, Object> entries = new HashMap<>();

    private boolean down;

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
    public <T> boolean tryPut(String key, Argument<T> type, T value, Duration ttl) {
        if (this.down) {
            return false;
        }
        this.entries.put(key, value);
        return true;
    }

    @Override
    public Optional<List<String>> keys(String pattern) {
        if (this.down) {
            return Optional.empty();
        }
        Pattern regex = Pattern.compile(Pattern.quote(pattern).replace("*", "\\E.*\\Q"));
        return Optional.of(new ArrayList<>(new TreeSet<>(this.entries.keySet().stream().filter(key -> regex.matcher(key).matches()).toList())));
    }

    /** Makes {@link #tryPut} and {@link #keys} fail like an unreachable server. */
    public void goDown() {
        this.down = true;
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
