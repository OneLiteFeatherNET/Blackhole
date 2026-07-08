package net.onelitefeather.blackhole.backend.appeal;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Serdeable
@ReflectiveAccess
public record AppealSubmissionDTO(
        @NonNull @NotBlank String punishmentIdentifier,
        @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "appellantHash must be a sha-512 hash") String appellantHash,
        @NonNull @NotBlank @Size(max = 2000) String statement
) {
}
