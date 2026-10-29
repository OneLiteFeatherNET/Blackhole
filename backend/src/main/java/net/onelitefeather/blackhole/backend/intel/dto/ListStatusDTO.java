package net.onelitefeather.blackhole.backend.intel.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.intel.IntelClass;
import net.onelitefeather.blackhole.backend.intel.IntelSourceHealth;

import java.util.List;

/**
 * Wire contract of the list sources and their refresh status ({@code /intel/lists}). Read-only: the sources and the
 * hosting ASNs come from configuration.
 */
@Schema(description = "The configured VPN and hosting list sources with their refresh status")
@Serdeable
public sealed interface ListStatusDTO {

    /**
     * Every configured source and the hosting ASNs.
     *
     * @param sources     one entry per configured source, enabled or not
     * @param hostingAsns the configured hosting AS numbers, sorted; empty when none is configured
     */
    @Schema(name = "ListStatusListing")
    @Serdeable
    @ReflectiveAccess
    record Listing(@NonNull List<Source> sources, @NonNull List<Long> hostingAsns) implements ListStatusDTO {
    }

    /**
     * One list source. Times are epoch milliseconds, null when never. The error is shortened and holds no URL
     * credentials.
     *
     * @param status FRESH, STALE or FAILED; see the thresholds on the endpoint
     */
    @Schema(name = "ListStatusSource")
    @Serdeable
    @ReflectiveAccess
    record Source(@NonNull String key, @NonNull IntelClass kind, boolean enabled, @NonNull String attribution,
                  @NonNull String licence, @Nullable Long lastAttemptAt, @Nullable Long lastSuccessAt,
                  @Nullable String lastError, long entryCount, long invalidCount, @NonNull IntelSourceHealth status)
            implements ListStatusDTO {
    }
}
