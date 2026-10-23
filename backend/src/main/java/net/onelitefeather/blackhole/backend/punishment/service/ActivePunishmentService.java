package net.onelitefeather.blackhole.backend.punishment.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentReader;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentSort;
import net.onelitefeather.blackhole.backend.punishment.SortDirection;
import net.onelitefeather.blackhole.backend.punishment.dto.ActivePunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The overview of currently punished players. Expected failures come back as
 * {@link ActivePunishmentDTO.Error}, never as exceptions. Reads only; the time comes from the injected clock.
 */
@Singleton
public class ActivePunishmentService {

    static final int DEFAULT_SIZE = 25;
    static final int MAX_SIZE = 100;

    private final ActivePunishmentReader reader;
    private final Clock clock;

    public ActivePunishmentService(ActivePunishmentReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * Lists the active punishments of all players.
     *
     * @return a {@link ActivePunishmentDTO.ListPage}, or a 400 error for a negative {@code expiresBefore} or {@code page}
     */
    public ActivePunishmentDTO list(
            @Nullable EnforcementKind kind, @Nullable String section, @Nullable ServerTargetKind targetKind,
            @Nullable Boolean permanent, @Nullable Long expiresBefore, @Nullable Boolean automatic, @Nullable UUID actor,
            @Nullable ActivePunishmentSort sort, @Nullable SortDirection direction, @Nullable Integer page, @Nullable Integer size
    ) {
        if (expiresBefore != null && expiresBefore < 0) {
            return new ActivePunishmentDTO.Error("expiresBefore is a time in epoch milliseconds", ErrorKind.VALIDATION);
        }
        if (page != null && page < 0) {
            return new ActivePunishmentDTO.Error("page starts at 0", ErrorKind.VALIDATION);
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        String normalizedSection = section == null || section.isBlank() ? null : section.strip();
        ActivePunishmentReader.Page result = this.reader.list(new ActivePunishmentReader.Query(
                kind, normalizedSection, targetKind, permanent, expiresBefore, automatic, actor,
                sort == null ? ActivePunishmentSort.CREATED : sort, direction == null ? SortDirection.DESC : direction,
                pageNumber, pageSize), this.clock.millis());
        List<ActivePunishmentDTO.Item> items = result.rows().stream().map(row -> new ActivePunishmentDTO.Item(
                row.owner(), row.punishmentIdentifier(), row.shortId(), row.section(), row.sectionName(), row.kind(), row.targetKind(),
                row.templateKey(), row.reason(), row.automatic(), row.createdAt(), row.expiresAt(), row.permanent(),
                row.caseStatus() == null ? null : CaseStatus.valueOf(row.caseStatus()), row.actorName())).toList();
        return new ActivePunishmentDTO.ListPage(items, pageNumber, pageSize, result.total(), (int) Math.ceil(result.total() / (double) pageSize));
    }
}
