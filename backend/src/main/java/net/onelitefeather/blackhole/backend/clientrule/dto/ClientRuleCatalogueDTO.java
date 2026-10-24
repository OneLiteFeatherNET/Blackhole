package net.onelitefeather.blackhole.backend.clientrule.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.time.Instant;
import java.util.List;

/**
 * Wire contract of {@code /client-rule/catalogue}: what the rule editor can suggest, reported by each
 * proxy and merged on read. It holds aggregates only: a brand or channel is listed when at least
 * three distinct players showed it, commands come from the proxy's command tree and never from what
 * players typed, and nothing links an entry to a player.
 */
@Schema(description = "Brands, channels, commands and protocol versions the proxies have seen, for the rule editor")
@Serdeable
public sealed interface ClientRuleCatalogueDTO {

    @Schema(name = "ClientRuleCatalogueEntry", description = "A brand or channel id with the number of distinct players that showed it")
    @Serdeable
    @ReflectiveAccess
    record Entry(@NonNull String name, int players) {
    }

    @Schema(name = "ClientRuleCatalogueProtocol")
    @Serdeable
    @ReflectiveAccess
    record ProtocolEntry(int version, @NonNull String name) {
    }

    @Schema(name = "ClientRuleCatalogueReportRequest", description = "A full snapshot, never a delta")
    @Serdeable
    @ReflectiveAccess
    record ReportRequest(
            @NonNull @NotBlank String proxyId,
            @Nullable List<Entry> brands,
            @Nullable List<Entry> channels,
            @Nullable List<String> commands,
            @Nullable List<ProtocolEntry> protocols
    ) implements ClientRuleCatalogueDTO {
    }

    @Schema(name = "ClientRuleCatalogueStored")
    @Serdeable
    @ReflectiveAccess
    record Stored(@NonNull String proxyId, @NonNull Instant reportedAt) implements ClientRuleCatalogueDTO {
    }

    @Schema(name = "ClientRuleCatalogueResponse", description = "The merged view of every live proxy snapshot; available is false when none exists")
    @Serdeable
    @ReflectiveAccess
    record Response(
            boolean available,
            @Nullable Instant reportedAt,
            @NonNull List<Entry> brands,
            @NonNull List<Entry> channels,
            @NonNull List<String> commands,
            @NonNull List<ProtocolEntry> protocols,
            @NonNull List<String> proxies
    ) implements ClientRuleCatalogueDTO {
    }

    @Schema(name = "ClientRuleCatalogueError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ClientRuleCatalogueDTO, ErrorResponse {
    }
}
