package net.onelitefeather.blackhole.backend.integration.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.Map;

/**
 * Wire contract of {@code POST /integration/detections}: a batch of detections in, one result per item out.
 *
 * <p>The request body is read as raw text by the service, so unknown fields, the size cap and every item rule are
 * applied there. {@link Request} and {@link Item} describe the same shape for OpenAPI; they are not bound from the
 * body.
 */
@Schema(description = "A batch of detections from a registered integration; the answer lists one result per item, in order")
@Serdeable
public sealed interface IntegrationDetectionDTO {

    @Schema(name = "IntegrationDetectionRequest", description = "Up to 50 detections and at most 64 KiB in all")
    @Serdeable
    @ReflectiveAccess
    record Request(@NonNull List<Item> detections) implements IntegrationDetectionDTO {
    }

    @Schema(name = "IntegrationDetectionItem", description = "One detection: the envelope and the payload of its kind")
    @Serdeable
    @ReflectiveAccess
    record Item(
            @NonNull String detectionId,
            @NonNull String kind,
            @NonNull Integer schemaVersion,
            @NonNull String occurredAt,
            @NonNull String server,
            @NonNull String owner,
            @Nullable String severity,
            @NonNull String summary,
            @NonNull Map<String, Object> payload,
            @Nullable String sourceSystem
    ) {
    }

    @Schema(description = "The answer to a batch: the results in request order")
    @Serdeable
    record Response(@NonNull List<ItemResult> results) implements IntegrationDetectionDTO {
    }

    @Schema(description = "The outcome of one item")
    @Serdeable
    record ItemResult(
            int index,
            @Nullable String detectionId,
            @NonNull DetectionStatus status,
            @Nullable DetectionReason reason
    ) {
    }

    @Serdeable
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements IntegrationDetectionDTO, ErrorResponse {
    }
}
