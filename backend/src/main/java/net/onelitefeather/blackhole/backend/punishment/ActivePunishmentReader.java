package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.util.List;
import java.util.UUID;

/**
 * The read side of the active punishment overview: every punishment that currently holds an active
 * slot and has not ended, across all players. Reads never write.
 */
public interface ActivePunishmentReader {

    /**
     * The filters, sort and page of one read; a {@code null} filter does not filter.
     *
     * @param permanent     true for permanent punishments only, false for temporary ones only
     * @param expiresBefore only temporary punishments that end before this time (epoch millis)
     * @param automatic     true for those the ELO system applied, false for staff-applied ones
     * @param actor         the staff member who applied it (the linked actor of an in-game punishment, else the signed-in writer)
     */
    record Query(
            @Nullable EnforcementKind kind, @Nullable String section, @Nullable ServerTargetKind targetKind,
            @Nullable Boolean permanent, @Nullable Long expiresBefore, @Nullable Boolean automatic, @Nullable UUID actor,
            ActivePunishmentSort sort, SortDirection direction, int page, int size
    ) {
    }

    /** One active punishment. {@code actorName} is null for an unverified, unknown or anonymized actor. */
    record Row(
            String owner, String punishmentIdentifier, String shortId, String section, @Nullable String sectionName,
            @Nullable EnforcementKind kind, @Nullable ServerTargetKind targetKind, @Nullable String templateKey,
            @Nullable String reason, boolean automatic, long createdAt, @Nullable Long expiresAt, boolean permanent,
            @Nullable String caseStatus, @Nullable String actorName
    ) {
    }

    /** One page of rows and how many rows match in all. */
    record Page(List<Row> rows, long total) {
    }

    /**
     * Reads one page.
     *
     * @param now the time (epoch millis) a punishment must still be running at to count as active
     */
    Page list(Query query, long now);
}
