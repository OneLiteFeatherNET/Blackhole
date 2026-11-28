package net.onelitefeather.blackhole.backend.publicbans.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.Map;

/**
 * The answers of the public ban list. A {@link PublicBanEntry} carries exactly the ten fields the list allows: no raw UUID,
 * name, IP address, Ban ID, reference, staff or owner value, and no exact instant. {@link PublicBanError} is the expected
 * failure of a route; the controller maps it to a problem body.
 */
public sealed interface PublicBansDTO {

    /**
     * One listed sanction. {@code playerHash} is the lowercase SHA-256 of {@code blackhole-ban-list-v1:} plus the lowercase
     * dashed UUID, or {@code null} when the punishment was applied without a UUID; {@code hashScheme} names the scheme of
     * {@code playerHash} and is set on every entry.
     */
    @Schema(name = "PublicBanEntry", description = "One listed sanction of the public ban list")
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PublicBanEntry(String hash, String playerHash, String hashScheme, String reason, String kind, String status,
                          String issuedOn, boolean permanent, String expiresOn, String endedOn) implements PublicBansDTO {
    }

    /** A page of entries; {@code nextCursor} is {@code null} on the last page. */
    @Schema(name = "PublicBanPage", description = "One keyset page of the public ban list")
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PublicBanPage(List<PublicBanEntry> items, String nextCursor) implements PublicBansDTO {
    }

    /** The counts per public reason of the window; {@code windowDays} is the retention in days. */
    @Schema(name = "PublicBanSummary", description = "The counts of the public ban list per reason")
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PublicBanSummary(int windowDays, List<PublicBanSummaryRow> reasons) implements PublicBansDTO {
    }

    /** One reason and kind. Counts below the minimum are {@code null} with {@code suppressed} true. */
    @Schema(name = "PublicBanSummaryRow", description = "One public reason and kind with its counts")
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PublicBanSummaryRow(String reason, String kind, Long active, Long total, boolean suppressed) {
    }

    /**
     * An expected failure: the HTTP status, the machine code, a fixed sentence and, for validation, the fields that failed
     * (each as {@code field} and {@code message}). It never echoes the request. The controller sends it as an RFC 9457 problem.
     */
    @Schema(name = "PublicBanError", description = "An RFC 9457 problem of the public ban list, with its machine code")
    record PublicBanError(int status, String code, String detail, List<Map<String, String>> errors)
            implements PublicBansDTO, ErrorResponse {

        public PublicBanError {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        @Override
        public String errorMessage() {
            return this.detail;
        }
    }
}
