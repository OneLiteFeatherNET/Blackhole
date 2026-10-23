package net.onelitefeather.blackhole.backend.imports.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code POST /admin/import/vanilla}: the import summary, or why the import was refused.
 */
@Schema(description = "Importing vanilla ban lists")
@Serdeable
public sealed interface VanillaImportDTO {

    /**
     * Summary of a vanilla ban-list import (or, when {@code dryRun} is set, a preview of what an
     * import would do without writing anything).
     *
     * @param dryRun                 whether this was a preview only
     * @param playersTotal           total entries found in banned-players.json
     * @param playersImported        entries that were (or, for a dry run, would be) imported
     * @param playersSkippedExisting entries skipped because the profile already has an active ban
     * @param playersInvalid         entries that could not be parsed (bad uuid/date)
     * @param invalidEntries         a short description of each invalid entry, for troubleshooting
     * @param ipsTotal               total entries found in banned-ips.json, if provided
     * @param ipsSkipped             entries skipped (always equal to ipsTotal; no first-class IP-ban support)
     * @param reasonGroups           the imported entries grouped by their original reason, each with the
     *                               template it was (or would be) linked to
     */
    @Schema(name = "VanillaImportResult")
    @Serdeable
    @ReflectiveAccess
    record Result(
            boolean dryRun,
            int playersTotal,
            int playersImported,
            int playersSkippedExisting,
            int playersInvalid,
            @NonNull List<String> invalidEntries,
            int ipsTotal,
            int ipsSkipped,
            @NonNull List<ReasonGroup> reasonGroups
    ) implements VanillaImportDTO {
    }

    /**
     * Imported entries that share an original reason and so share a template.
     *
     * @param reason      the original vanilla reason (a blank one is shown as {@code Imported vanilla ban})
     * @param templateKey the key of the template they are linked to
     * @param count       how many entries
     */
    @Schema(name = "VanillaImportReasonGroup")
    @Serdeable
    @ReflectiveAccess
    record ReasonGroup(@NonNull String reason, @NonNull String templateKey, int count) {
    }

    /** The import was refused and nothing was written. */
    @Schema(name = "VanillaImportError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements VanillaImportDTO, ErrorResponse {
    }
}
