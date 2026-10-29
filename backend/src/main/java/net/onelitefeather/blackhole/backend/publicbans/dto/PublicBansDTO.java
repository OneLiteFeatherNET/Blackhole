package net.onelitefeather.blackhole.backend.publicbans.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * The answers of the public ban list. An {@link Entry} carries exactly the ten fields the list allows: no raw UUID, name, IP
 * address, Ban ID, reference, staff or owner value, and no exact instant. {@link Error} is the expected failure of a route;
 * the controller maps it to a problem body.
 */
@Schema(name = "PublicBans", description = "The public ban list for authenticated partner clients")
public sealed interface PublicBansDTO {

    /**
     * One listed sanction. {@code playerHash} is the lowercase SHA-256 of {@code blackhole-ban-list-v1:} plus the lowercase
     * dashed UUID, or {@code null} when the punishment was applied without a UUID; {@code hashScheme} names the scheme of
     * {@code playerHash} and is set on every entry.
     */
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Entry(String hash, String playerHash, String hashScheme, String reason, String kind, String status, String issuedOn,
                 boolean permanent, String expiresOn, String endedOn) implements PublicBansDTO {
    }

    /** A page of entries; {@code nextCursor} is {@code null} on the last page. */
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Page(List<Entry> items, String nextCursor) implements PublicBansDTO {
    }

    /** The counts per public reason of the window; {@code windowDays} is the retention in days. */
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Summary(int windowDays, List<SummaryRow> reasons) implements PublicBansDTO {
    }

    /** One reason and kind. Counts below the minimum are {@code null} with {@code suppressed} true. */
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record SummaryRow(String reason, String kind, Long active, Long total, boolean suppressed) {
    }

    /**
     * An expected failure: the HTTP status, the machine code, a fixed sentence and, for validation, the fields that failed
     * (each as {@code field} and {@code message}). It never echoes the request.
     */
    record Error(int status, String code, String detail, List<Map<String, String>> errors) implements PublicBansDTO {

        public Error {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
