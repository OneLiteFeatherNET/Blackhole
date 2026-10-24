package net.onelitefeather.blackhole.backend.evasion.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code POST /evasion/fingerprint}. The proxy sends the traits, over the authenticated
 * channel, exactly as it sends the raw IP for the IP signal; the backend hashes them with its own key and
 * never persists or logs the raw values, and nothing here ever returns them.
 */
@Schema(description = "The client traits a proxy saw, to be hashed into a fingerprint")
@Serdeable
public sealed interface ClientFingerprintDTO {

    @Schema(name = "ClientFingerprintCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "owner must be a sha-512 hash") String owner,
            @Nullable @Schema(description = "The minecraft:brand string, empty or absent if unknown") String brand,
            @Nullable @Schema(description = "The registered plugin channel ids, without the minecraft: namespace; at most 256") List<String> channels,
            @Nullable @Schema(description = "The legacy Forge mods as id@version; at most 256") List<String> mods
    ) implements ClientFingerprintDTO {

        @Override
        public String toString() {
            return "CreateRequest[owner=" + this.owner + ", traits=***]";
        }
    }

    @Schema(name = "ClientFingerprintResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @Schema(description = "False when the traits are generic (plain vanilla, fabric, forge or neoforge) and nothing was stored")
            boolean stored
    ) implements ClientFingerprintDTO {
    }

    @Schema(name = "ClientFingerprintError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ClientFingerprintDTO, ErrorResponse {
    }
}
