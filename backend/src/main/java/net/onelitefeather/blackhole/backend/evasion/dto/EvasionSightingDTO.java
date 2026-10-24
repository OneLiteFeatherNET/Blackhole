package net.onelitefeather.blackhole.backend.evasion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code GET /evasion/{owner}/sightings}: the other owners that shared a hashed
 * network address with one owner. It carries owner hashes only - never an IP, a token or a raw UUID.
 */
@Schema(description = "Owners that shared a hashed network address with the requested owner")
@Serdeable
public sealed interface EvasionSightingDTO {

    @Schema(name = "EvasionLinkedOwner", description = "One other owner seen on a shared hashed network address")
    @Serdeable
    @ReflectiveAccess
    record LinkedOwner(
            @NonNull @Schema(description = "Hashed owner; open the player detail with it") String owner,
            @NonNull @Schema(description = "Why the owners are linked: SHARED_IP when the IP is involved, else SHARED_CLIENT") String reason,
            @Schema(description = "How many distinct hashed addresses both owners were seen on; more is a stronger link")
            int sharedAddresses,
            @Schema(description = "Earliest sighting of the other owner on a shared address, epoch millis") long firstSeen,
            @Schema(description = "Latest sighting of the other owner on a shared address, epoch millis") long lastSeen,
            @NonNull @Schema(description = "Which signal links them: IP, CLIENT (same client setup only, a weak hint) or BOTH (the strong one)") Via via
    ) {
    }

    @Schema(name = "EvasionVia", description = "The signal behind a link between two owners")
    @Serdeable
    enum Via {
        /** A shared hashed network address. */
        IP,
        /** A shared client fingerprint only: the same client setup, which a shared modpack also explains. */
        CLIENT,
        /** Both. */
        BOTH
    }

    @Schema(name = "EvasionSightingsResponse")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Response(
            @Schema(description = "False when blackhole.evasion.ip-salt is unset: nothing is recorded, so an empty list means nothing")
            boolean detectionEnabled,
            @Schema(description = "Length of the detection window in days; only sightings inside it are correlated")
            int windowDays,
            @NonNull @Schema(description = "Linked owners, most recently seen first, at most 50") List<LinkedOwner> linkedOwners,
            @Schema(description = "True when more owners matched than are listed") boolean truncated,
            @Schema(description = "False when BLACKHOLE_FINGERPRINT_KEY is unset outside dev and test: no client fingerprint is recorded or compared")
            boolean clientFingerprintEnabled,
            @Schema(description = "Length of the client fingerprint window in days") int clientWindowDays
    ) implements EvasionSightingDTO {
    }

    @Schema(name = "EvasionSightingsError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements EvasionSightingDTO, ErrorResponse {
    }
}
