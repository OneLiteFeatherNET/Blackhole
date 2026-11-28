package net.onelitefeather.blackhole.backend.guard;

import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * The presence store on the shared Redis-protocol cache (Dragonfly in production), over the existing Lettuce connection.
 * The hash {@code guard:presence} holds one field per proxy, valued {@code "<count>:<epochMillis>"}, and the key
 * {@code guard:last-staff-seen} holds the last time any proxy reported staff.
 *
 * <p>Writes that fail throw, so the heartbeat can tell its proxy. Reads that fail report nothing, which the evaluator
 * reads as staff being absent: a broken cache fails toward the guard, as the design states.</p>
 */
@Singleton
public class RedisPresenceStore implements PresenceStore {

    static final String PRESENCE_KEY = "guard:presence";
    static final String LAST_SEEN_KEY = "guard:last-staff-seen";
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisPresenceStore.class);

    private final StatefulRedisConnection<String, String> connection;
    private final long timeoutMillis;

    public RedisPresenceStore(
            StatefulRedisConnection<String, String> connection,
            @Value("${blackhole.cache.timeout:PT0.2S}") Duration timeout
    ) {
        this.connection = connection;
        this.timeoutMillis = timeout.toMillis();
    }

    @Override
    public void report(String proxyId, int staffOnline, long reportedAt) {
        await(this.connection.async().hset(PRESENCE_KEY, proxyId, staffOnline + ":" + reportedAt), "report presence");
    }

    @Override
    public void markStaffSeen(long at) {
        await(this.connection.async().set(LAST_SEEN_KEY, Long.toString(at)), "mark staff seen");
    }

    @Override
    public List<PresenceEntry> entries() {
        try {
            Map<String, String> fields = this.connection.async().hgetall(PRESENCE_KEY).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            List<PresenceEntry> entries = new ArrayList<>();
            fields.forEach((proxyId, value) -> parse(proxyId, value).ifPresent(entries::add));
            return entries;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Reading guard presence was interrupted, treating every proxy as absent");
        } catch (Exception e) {
            LOGGER.warn("Reading guard presence failed, treating every proxy as absent: {}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public OptionalLong lastStaffSeen() {
        try {
            String value = this.connection.async().get(LAST_SEEN_KEY).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Reading the last staff sighting was interrupted, treating staff as long absent");
        } catch (Exception e) {
            LOGGER.warn("Reading the last staff sighting failed, treating staff as long absent: {}", e.getMessage());
        }
        return OptionalLong.empty();
    }

    private void await(java.util.concurrent.Future<?> write, String action) {
        try {
            write.get(this.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not " + action + ": interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not " + action + ": " + e.getMessage(), e);
        }
    }

    private static java.util.Optional<PresenceEntry> parse(String proxyId, String value) {
        int separator = value.indexOf(':');
        if (separator < 0) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new PresenceEntry(proxyId,
                    Integer.parseInt(value.substring(0, separator)), Long.parseLong(value.substring(separator + 1))));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
