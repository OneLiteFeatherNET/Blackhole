package net.onelitefeather.blackhole.backend.casetimeline.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChangeRules;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPublisher;
import net.onelitefeather.blackhole.backend.casetimeline.CaseMeta;
import net.onelitefeather.blackhole.backend.casetimeline.CasePriority;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTags;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseBulkOutcome;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies one change to up to {@value #MAX_BULK} cases. The request is checked once, then each case is written on its own: a case
 * is either fully changed, with its event, or not changed at all. A failure or a conflict of one case never stops the others, and
 * the answer names every case. {@code @Transactional} is unusable here, so the batch is deliberately not one transaction.
 */
@Singleton
public class CaseBulkService {

    /** The most cases one request may name: the size of a page of the case list. */
    public static final int MAX_BULK = 100;
    static final String AUDIT_FAILURES_METER = "blackhole.case.bulk.audit-failures";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseBulkService.class);
    private static final String STALE = "the case was changed by someone else; reload it and try again";

    private final PunishmentRepository punishments;
    private final StaffActorRepository staff;
    private final CaseReader reader;
    private final CaseChanges changes;
    private final CaseEventPublisher caseEvents;
    private final Clock clock;
    private final StaffAuditRecorder audit;
    private final Counter auditFailures;

    @Inject
    public CaseBulkService(PunishmentRepository punishments, StaffActorRepository staff, CaseReader reader, CaseChanges changes,
                           DomainEventPublisher publisher, Clock clock, StaffAuditRecorder audit, MeterRegistry meters) {
        this.punishments = punishments;
        this.staff = staff;
        this.reader = reader;
        this.changes = changes;
        this.caseEvents = new CaseEventPublisher(publisher);
        this.clock = clock;
        this.audit = audit;
        this.auditFailures = Counter.builder(AUDIT_FAILURES_METER)
                .description("Changed cases of a bulk edit whose audit entry could not be written").register(meters);
    }

    /** The shared change of a request, with its tags checked. */
    private record Change(@Nullable CaseStatus status, @Nullable UUID assigneeOid, boolean clearAssignee, @Nullable CasePriority priority,
                          List<String> addTags, List<String> removeTags) {
    }

    /**
     * Applies the change to every case of the request.
     *
     * @return the {@link CaseDTO.BulkResult} with one item per case in request order, or an error that changed nothing: 400 for a shape,
     * size, duplicate, empty change or tag problem, 422 for an assignee who is no known human staff member
     */
    public CaseDTO bulk(CaseDTO.BulkRequest request, Actor actor) {
        if (request.cases() == null || request.cases().isEmpty() || request.cases().size() > MAX_BULK) {
            return error(CaseErrorKind.VALIDATION, "a bulk edit names 1 to " + MAX_BULK + " cases");
        }
        Set<String> identifiers = new HashSet<>();
        for (CaseDTO.BulkCase bulkCase : request.cases()) {
            if (bulkCase == null || bulkCase.punishmentIdentifier() == null || bulkCase.punishmentIdentifier().isBlank()
                    || bulkCase.expectedVersion() == null) {
                return error(CaseErrorKind.VALIDATION, "every case names its punishmentIdentifier and its expectedVersion");
            }
            if (!identifiers.add(bulkCase.punishmentIdentifier())) {
                return error(CaseErrorKind.VALIDATION, "a case is listed only once");
            }
        }
        List<String> addTags = request.addTags() == null ? List.of() : request.addTags();
        List<String> removeTags = request.removeTags() == null ? List.of() : request.removeTags();
        if (request.status() == null && request.assigneeOid() == null && !request.clearAssignee() && request.priority() == null
                && addTags.isEmpty() && removeTags.isEmpty()) {
            return error(CaseErrorKind.VALIDATION, "a bulk edit names at least one change");
        }
        if (request.assigneeOid() != null && request.clearAssignee()) {
            return error(CaseErrorKind.VALIDATION, "an assignee and clearAssignee cannot be sent together");
        }
        List<String> add = CaseChangeRules.normalizeTags(addTags);
        List<String> remove = CaseChangeRules.normalizeTags(removeTags);
        if (add == null || remove == null) {
            return error(CaseErrorKind.VALIDATION, "tags are at most " + CaseTags.MAX + " slugs of [a-z0-9-], 1 to 32 characters each");
        }
        if (request.assigneeOid() != null && !isKnownHuman(request.assigneeOid())) {
            return error(CaseErrorKind.UNPROCESSABLE, "the assignee is not a known staff member");
        }
        Change change = new Change(request.status(), request.assigneeOid(), request.clearAssignee(), request.priority(), add, remove);

        String batchId = UUID.randomUUID().toString();
        long now = this.clock.millis();
        Map<String, CaseReader.CaseRow> rows = this.reader.findAll(identifiers);
        List<CaseDTO.BulkItem> items = new ArrayList<>();
        for (CaseDTO.BulkCase bulkCase : request.cases()) {
            items.add(edit(bulkCase, change, rows.get(bulkCase.punishmentIdentifier()), batchId, now, actor));
        }
        return result(batchId, items);
    }

    private CaseDTO.BulkItem edit(CaseDTO.BulkCase bulkCase, Change change, @Nullable CaseReader.CaseRow row, String batchId, long now,
                                  Actor actor) {
        String identifier = bulkCase.punishmentIdentifier();
        Optional<PunishmentEntity> found = this.punishments.findById(identifier);
        if (found.isEmpty()) {
            return item(identifier, CaseBulkOutcome.NOT_FOUND, false, 0, null, false, "there is no such punishment");
        }
        long current = row == null ? 0 : row.version();
        if (bulkCase.expectedVersion() != current) {
            return item(identifier, CaseBulkOutcome.CONFLICT, false, current, current, false, STALE);
        }
        CaseMeta before = row == null ? CaseMeta.defaults() : row.meta();
        CaseMeta after = CaseChangeRules.adjust(before, change.status() == null ? null : change.status().name(), change.assigneeOid(),
                change.clearAssignee(), change.priority() == null ? null : change.priority().name(), change.addTags(), change.removeTags());
        if (after == null) {
            return item(identifier, CaseBulkOutcome.FAILED, false, current, null, false,
                    "the case would have more than " + CaseTags.MAX + " tags");
        }
        if (after.diffFrom(before).isEmpty()) {
            return item(identifier, CaseBulkOutcome.UNCHANGED, false, current, null, false, null);
        }
        CaseActor by = CaseActor.of(actor.attribution());
        CaseChanges.Appended appended;
        try {
            if (row == null) {
                this.changes.ensureCase(identifier, now);
            }
            appended = this.changes.bulkEdit(identifier, current, now, by, before, after, batchId);
        } catch (RuntimeException e) {
            LOGGER.warn("Bulk edit of case {} failed before its write: {}", identifier, e.getClass().getSimpleName());
            return item(identifier, CaseBulkOutcome.FAILED, false, current, null, false, "the case could not be written");
        }
        if (appended == null) {
            long latest = this.reader.find(identifier).map(CaseReader.CaseRow::version).orElse(current);
            return item(identifier, CaseBulkOutcome.CONFLICT, false, latest, latest, false, STALE);
        }
        PunishmentEntity punishment = found.get();
        boolean audited = this.audit.record(actor.attribution(), actor, AuditAction.CASE_BULK_UPDATE,
                AuditTarget.ofCase(punishment.getShortId())) instanceof StaffAuditRecorder.Outcome.Recorded;
        if (!audited) {
            this.auditFailures.increment();
        }
        for (CaseEventPayload payload : after.diffFrom(before)) {
            Map<String, Object> fieldChange = new HashMap<>(payload.toMap());
            fieldChange.put("batchId", batchId);
            this.caseEvents.publish(punishment, new CaseChanges.Appended(appended.identifier(), appended.seq(), payload.type()), by, now,
                    fieldChange);
        }
        return item(identifier, CaseBulkOutcome.UPDATED, true, current + 1, null, audited, null);
    }

    private boolean isKnownHuman(UUID oid) {
        return this.staff.findById(oid).filter(actor -> actor.getKind() == ActorKind.HUMAN).isPresent();
    }

    private static CaseDTO.BulkItem item(String identifier, CaseBulkOutcome outcome, boolean changed, long version, @Nullable Long currentVersion,
                                         boolean auditWritten, @Nullable String message) {
        return new CaseDTO.BulkItem(identifier, outcome, changed, version, currentVersion, auditWritten, message);
    }

    private static CaseDTO.BulkResult result(String batchId, List<CaseDTO.BulkItem> items) {
        int updated = 0;
        int unchanged = 0;
        int conflicts = 0;
        int notFound = 0;
        int failed = 0;
        for (CaseDTO.BulkItem item : items) {
            switch (item.outcome()) {
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case CONFLICT -> conflicts++;
                case NOT_FOUND -> notFound++;
                case FAILED -> failed++;
            }
        }
        return new CaseDTO.BulkResult(batchId, updated, unchanged, conflicts, notFound, failed, items);
    }

    private static CaseDTO.Error error(CaseErrorKind kind, String message) {
        return new CaseDTO.Error(message, kind);
    }
}
