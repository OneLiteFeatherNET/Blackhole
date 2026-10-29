package net.onelitefeather.blackhole.backend.rollback;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the read-only preview of a rollback: it counts the matching punishments and freezes the
 * revocable ones, and every matching kick, into a {@code PREVIEW} job. A kick is frozen even when the
 * admin does not ask for marks, so the confirmed set is the set that runs; the execution decides whether
 * the kicks are marked. It never changes a punishment, a slot, an ELO score or a case.
 */
@Singleton
public class RollbackPreviewService {

    private static final String PUNISHMENT = "PUNISHMENT";
    private static final String KICK = "KICK";

    private final RollbackSelectionReader selection;
    private final RollbackJobStore jobs;
    private final Clock clock;
    private final Duration previewTtl;
    private final int maxItems;

    @Inject
    public RollbackPreviewService(RollbackSelectionReader selection, RollbackJobStore jobs, Clock clock,
                                  @Value("${blackhole.rollback.preview-ttl:PT1H}") Duration previewTtl,
                                  @Value("${blackhole.rollback.max-items:5000}") int maxItems) {
        this.selection = selection;
        this.jobs = jobs;
        this.clock = clock;
        this.previewTtl = previewTtl;
        this.maxItems = maxItems;
    }

    /**
     * @param request       the selector and the window
     * @param requestedByOid the verified {@code oid} of the admin who asked
     * @return the {@link RollbackDTO.Response} with the frozen job, or a {@link RollbackDTO.Error}
     */
    public RollbackDTO preview(RollbackDTO.PreviewRequest request, UUID requestedByOid) {
        if ((request.staffOid() == null) == (request.minecraftUuid() == null)) {
            return error(ErrorKind.VALIDATION, "give exactly one of staffOid and minecraftUuid");
        }
        if (request.windowFrom() > request.windowTo()) {
            return error(ErrorKind.VALIDATION, "the window starts after it ends");
        }
        RollbackSelector selector;
        if (request.staffOid() != null) {
            if (!this.selection.isHumanStaffActor(request.staffOid())) {
                return error(ErrorKind.ROLLBACK_SELECTOR_INVALID, "the selector must be a human staff member");
            }
            selector = RollbackSelector.staffActor(request.staffOid());
        } else {
            selector = RollbackSelector.unlinkedInGame(request.minecraftUuid());
        }

        List<RollbackSelectionReader.Candidate> rows = this.selection.select(selector, request.windowFrom(), request.windowTo());
        List<RollbackSelectionReader.Candidate> revocable = rows.stream()
                .filter(row -> row.revocable() && PUNISHMENT.equals(row.kind())).toList();
        int alreadyInactive = (int) rows.stream()
                .filter(row -> !row.revocable() && PUNISHMENT.equals(row.kind())).count();
        List<RollbackSelectionReader.Candidate> kicks = rows.stream().filter(row -> KICK.equals(row.kind())).toList();
        if (revocable.size() + kicks.size() > this.maxItems) {
            return error(ErrorKind.ROLLBACK_TOO_LARGE, "more than " + this.maxItems + " punishments match; narrow the window");
        }

        UUID jobId = UUID.randomUUID();
        long now = this.clock.millis();
        long expiresAt = now + this.previewTtl.toMillis();
        List<RollbackJobStore.FrozenItem> frozen = Stream.concat(revocable.stream(), kicks.stream())
                .map(row -> new RollbackJobStore.FrozenItem(row.identifier(), row.owner(), row.sectionKey(), row.kind()))
                .toList();
        this.jobs.insertPreview(new RollbackJobStore.PreviewJob(jobId, requestedByOid, selector, request.windowFrom(),
                request.windowTo(), now, expiresAt), frozen);

        Map<String, Integer> byKind = revocable.stream()
                .collect(Collectors.groupingBy(RollbackSelectionReader.Candidate::kind, Collectors.summingInt(row -> 1)));
        Map<String, Integer> bySection = revocable.stream()
                .collect(Collectors.groupingBy(RollbackSelectionReader.Candidate::sectionKey, Collectors.summingInt(row -> 1)));
        int players = (int) revocable.stream().map(RollbackSelectionReader.Candidate::owner).distinct().count();
        return new RollbackDTO.Response(jobId, "PREVIEW", expiresAt, revocable.size(), alreadyInactive, players, kicks.size(),
                byKind, bySection);
    }

    private static RollbackDTO.Error error(ErrorKind kind, String message) {
        return new RollbackDTO.Error(message, kind);
    }
}
