package net.onelitefeather.blackhole.backend.imports;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/**
 * Summary of a vanilla ban-list import (or, when {@code dryRun} is set, a preview of what an
 * import would do without writing anything).
 *
 * @param tenantId               the tenant the import was scoped to
 * @param dryRun                 whether this was a preview only
 * @param playersTotal           total entries found in banned-players.json
 * @param playersImported        entries that were (or, for a dry run, would be) imported
 * @param playersSkippedExisting entries skipped because the profile already has an active ban
 * @param playersInvalid         entries that could not be parsed (bad uuid/date)
 * @param invalidEntries         a short description of each invalid entry, for troubleshooting
 * @param ipsTotal               total entries found in banned-ips.json, if provided
 * @param ipsSkipped             entries skipped (always equal to ipsTotal; no first-class IP-ban support)
 */
@Serdeable
public record VanillaImportResultDTO(
        UUID tenantId,
        boolean dryRun,
        int playersTotal,
        int playersImported,
        int playersSkippedExisting,
        int playersInvalid,
        List<String> invalidEntries,
        int ipsTotal,
        int ipsSkipped
) {
}
