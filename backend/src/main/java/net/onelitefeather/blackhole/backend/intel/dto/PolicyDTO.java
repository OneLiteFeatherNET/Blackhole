package net.onelitefeather.blackhole.backend.intel.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.intel.IntelClass;
import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of the login policy per classification ({@code /intel/policy}).
 */
@Schema(description = "The login policy per VPN, hosting and proxy class")
@Serdeable
public sealed interface PolicyDTO {

    /**
     * Sets the policy of one class. Only an admin may set {@code DENY}.
     *
     * @param classification the class: VPN, HOSTING or PROXY
     * @param policy         ALLOW, FLAG or DENY
     */
    @Schema(name = "PolicyUpdateRequest")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(@NonNull IntelClass classification, @NonNull IntelPolicy policy) implements PolicyDTO {
    }

    /**
     * One stored policy.
     *
     * @param previous the value before this change; null when the policy was only read
     */
    @Schema(name = "PolicyResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull IntelClass classification, @NonNull IntelPolicy policy, @Nullable IntelPolicy previous)
            implements PolicyDTO {
    }

    /**
     * Every policy, one per class.
     */
    @Schema(name = "PolicyListing")
    @Serdeable
    @ReflectiveAccess
    record Listing(@NonNull List<Response> policies) implements PolicyDTO {
    }

    @Schema(name = "PolicyError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements PolicyDTO, ErrorResponse {
    }
}
