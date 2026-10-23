package net.onelitefeather.blackhole.backend.cloudnet.dto;

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
 * Wire contract of {@code /cloudnet/catalogue}: the tasks, groups and services CloudNet knows,
 * reported by each proxy and merged for the section editor.
 */
@Schema(description = "The CloudNet tasks, groups and services reported by the proxies")
@Serdeable
public sealed interface CatalogueDTO {

    @Schema(name = "CatalogueTask")
    @Serdeable
    @ReflectiveAccess
    record TaskEntry(@NonNull String name, @Nullable List<String> groups) {
    }

    @Schema(name = "CatalogueServiceReport")
    @Serdeable
    @ReflectiveAccess
    record ServiceEntry(@NonNull String name, @Nullable String task, @Nullable List<String> groups, boolean running) {
    }

    @Schema(name = "CatalogueService")
    @Serdeable
    @ReflectiveAccess
    record MergedService(@NonNull String name, @Nullable String task, boolean running) {
    }

    @Schema(name = "CatalogueReportRequest", description = "A full snapshot, never a delta")
    @Serdeable
    @ReflectiveAccess
    record ReportRequest(
            @NonNull @NotBlank String proxyId,
            @Nullable List<TaskEntry> tasks,
            @Nullable List<String> groups,
            @Nullable List<ServiceEntry> services
    ) implements CatalogueDTO {
    }

    @Schema(name = "CatalogueStored")
    @Serdeable
    @ReflectiveAccess
    record Stored(@NonNull String proxyId, @NonNull Instant reportedAt) implements CatalogueDTO {
    }

    @Schema(name = "CatalogueResponse", description = "The merged view of every live proxy snapshot; available is false when none exists")
    @Serdeable
    @ReflectiveAccess
    record Response(
            boolean available,
            @Nullable Instant reportedAt,
            @NonNull List<TaskEntry> tasks,
            @NonNull List<String> groups,
            @NonNull List<MergedService> services,
            @NonNull List<String> proxies
    ) implements CatalogueDTO {
    }

    @Schema(name = "CatalogueError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements CatalogueDTO, ErrorResponse {
    }
}
