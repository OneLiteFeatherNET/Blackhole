package net.onelitefeather.blackhole.backend.guard;

import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The refusal counters on the shared Redis-protocol cache (Dragonfly in production). The hash {@code guard:summary}
 * counts refusals per ASN, {@code guard:summary-meta} holds the organisation and country of each ASN, and the sorted
 * set {@code guard:burst:<asn>} holds the times of recent refusals.
 *
 * <p>Errors are thrown to the caller, which decides; the login check logs them and still refuses the login.</p>
 */
@Singleton
public class RedisGuardRefusalStore implements GuardRefusalStore {

    static final String SUMMARY_KEY = "guard:summary";
    static final String SUMMARY_META_KEY = "guard:summary-meta";
    static final String BURST_KEY_PREFIX = "guard:burst:";
    private static final char META_SEPARATOR = '\t';

    private final StatefulRedisConnection<String, String> connection;
    private final long timeoutMillis;

    public RedisGuardRefusalStore(
            StatefulRedisConnection<String, String> connection,
            @io.micronaut.context.annotation.Value("${blackhole.cache.timeout:PT0.2S}") Duration timeout
    ) {
        this.connection = connection;
        this.timeoutMillis = timeout.toMillis();
    }

    @Override
    public void recordRefusal(long asn, String organisation, String countryCode) {
        RedisAsyncCommands<String, String> commands = this.connection.async();
        await(commands.hincrby(SUMMARY_KEY, Long.toString(asn), 1));
        await(commands.hset(SUMMARY_META_KEY, Long.toString(asn), meta(organisation, countryCode)));
    }

    @Override
    public long addBurstRefusal(long asn, long nowMillis, long windowMillis) {
        RedisAsyncCommands<String, String> commands = this.connection.async();
        String key = BURST_KEY_PREFIX + asn;
        await(commands.zadd(key, nowMillis, nowMillis + ":" + UUID.randomUUID()));
        await(commands.zremrangebyscore(key, Range.create(Double.NEGATIVE_INFINITY, (double) (nowMillis - windowMillis))));
        return await(commands.zcount(key, Range.create(nowMillis - windowMillis, nowMillis)));
    }

    @Override
    public List<SummaryRow> drainSummary() {
        RedisAsyncCommands<String, String> commands = this.connection.async();
        try {
            commands.multi();
            RedisFuture<Map<String, String>> counts = commands.hgetall(SUMMARY_KEY);
            RedisFuture<Map<String, String>> metas = commands.hgetall(SUMMARY_META_KEY);
            commands.del(SUMMARY_KEY, SUMMARY_META_KEY);
            RedisFuture<TransactionResult> executed = commands.exec();
            executed.get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            return rows(counts.get(), metas.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The refusal summary could not be read: interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("The refusal summary could not be read: " + e.getMessage(), e);
        }
    }

    private static List<SummaryRow> rows(Map<String, String> counts, Map<String, String> metas) {
        List<SummaryRow> rows = new ArrayList<>();
        counts.forEach((asn, count) -> {
            String[] meta = metas.getOrDefault(asn, META_SEPARATOR + "").split(String.valueOf(META_SEPARATOR), -1);
            rows.add(new SummaryRow(Long.parseLong(asn), Long.parseLong(count), meta[0].isEmpty() ? null : meta[0],
                    meta.length > 1 && !meta[1].isEmpty() ? meta[1] : null));
        });
        return rows;
    }

    private static String meta(String organisation, String countryCode) {
        return (organisation == null ? "" : organisation.replace(META_SEPARATOR, ' ')) + META_SEPARATOR
                + (countryCode == null ? "" : countryCode);
    }

    private <T> T await(RedisFuture<T> future) {
        try {
            return future.get(this.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Refusal counter write interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Refusal counter failed: " + e.getMessage(), e);
        }
    }
}
