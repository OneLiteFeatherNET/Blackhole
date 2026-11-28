package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.dto.ListStatusDTO;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reports the configured list sources and their last refresh, for the panel. Read-only: the sources and the hosting
 * ASNs are configuration. The status is derived from the stored refresh row:
 *
 * <ul>
 *   <li>{@link IntelSourceHealth#FAILED}: the last refresh returned an error; the previous list is still in use.</li>
 *   <li>{@link IntelSourceHealth#FRESH}: no error, and the last success is at most two refresh intervals old
 *       (12 hours at the default PT6H, so one missed run is still fresh).</li>
 *   <li>{@link IntelSourceHealth#STALE}: anything else, including a source that was never refreshed.</li>
 * </ul>
 */
@Singleton
public class IntelListStatusService {

    static final int ERROR_MAX = 120;
    /** The user information of a URL, such as {@code user:secret@} after {@code https://}. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(://)[^/\\s@]*@");

    private final IntelRecordStore store;
    private final List<IntelListSourceConfig> sources;
    private final Clock clock;
    private final Duration refreshInterval;
    private final List<Long> hostingAsns;

    public IntelListStatusService(
            IntelRecordStore store,
            List<IntelListSourceConfig> sources,
            Clock clock,
            @Value("${blackhole.intel.lists.refresh-interval:PT6H}") Duration refreshInterval,
            @Value("${blackhole.intel.hosting-asns:}") String hostingAsns
    ) {
        this.store = store;
        this.sources = sources;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
        this.hostingAsns = List.copyOf(new TreeSet<>(ListReloader.parseAsns(hostingAsns)));
    }

    /** @return every configured source, enabled or not, and the configured hosting ASNs */
    public ListStatusDTO.Listing listing() {
        long now = this.clock.millis();
        Map<String, IntelSourceStatus> statuses = new HashMap<>();
        for (IntelSourceStatus status : this.store.sourceStatuses()) {
            statuses.put(status.sourceKey(), status);
        }
        List<ListStatusDTO.Source> rows = new ArrayList<>();
        for (IntelListSourceConfig source : this.sources) {
            rows.add(row(source, statuses.get(source.getKey()), now));
        }
        return new ListStatusDTO.Listing(rows, this.hostingAsns);
    }

    /** @return the health of one source at {@code now}; see the class comment for the thresholds */
    IntelSourceHealth health(@Nullable IntelSourceStatus status, long now) {
        if (status != null && status.lastError() != null) {
            return IntelSourceHealth.FAILED;
        }
        if (status == null || status.lastSuccessAt() == null) {
            return IntelSourceHealth.STALE;
        }
        return now - status.lastSuccessAt() <= this.refreshInterval.toMillis() * 2 ? IntelSourceHealth.FRESH : IntelSourceHealth.STALE;
    }

    /** Shortens an error and removes the user information of any URL in it; null stays null. */
    static String shortError(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String withoutCredentials = URL_CREDENTIALS.matcher(raw).replaceAll("$1");
        return withoutCredentials.length() > ERROR_MAX ? withoutCredentials.substring(0, ERROR_MAX) : withoutCredentials;
    }

    private ListStatusDTO.Source row(IntelListSourceConfig source, @Nullable IntelSourceStatus status, long now) {
        return new ListStatusDTO.Source(source.getKey(), source.getKind(), source.isEnabled(), source.getAttribution(),
                source.getLicence(), status == null ? null : status.lastAttemptAt(),
                status == null ? null : status.lastSuccessAt(), status == null ? null : shortError(status.lastError()),
                status == null ? 0L : status.entryCount(), status == null ? 0L : status.invalidCount(),
                health(status, now));
    }
}
