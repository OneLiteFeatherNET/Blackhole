package net.onelitefeather.blackhole.backend.evasion.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Serdeable
@ReflectiveAccess
public record EvasionRecordDTO(
        @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "owner must be a sha-512 hash") String owner,
        @NonNull @NotBlank String ip
) {
}
