package net.onelitefeather.blackhole.backend.kick.service;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.kick.KickDeadline;
import net.onelitefeather.blackhole.backend.kick.KickDeliveries;
import net.onelitefeather.blackhole.backend.kick.KickEventPublisher;
import net.onelitefeather.blackhole.backend.kick.KickKind;
import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.kick.KickStatus;
import net.onelitefeather.blackhole.backend.kick.KickStore;
import net.onelitefeather.blackhole.backend.exemption.ExemptionGuard;
import net.onelitefeather.blackhole.backend.kick.KickValidator;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.ProfileSlots;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentKind;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.phoca.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Kicks players. A kick of one player is a punishment row of kind {@code KICK} (it sits in the player's
 * history and in its own case, counts for escalation, and is never active). A mass kick is one audited
 * {@code kick_action} row and nothing per player. Both answer at once and tell the proxies over the
 * event bus; the backend never waits for them. What the proxies did comes back as acknowledgements,
 * and the status is derived from them when the kick is read.
 *
 * <p>The {@code Idempotency-Key} and the caller's {@code oid} derive the kick's identifier, so a
 * repeated request finds the kick it made and writes nothing. {@code @Transactional} is unusable in
 * this codebase, so the steps after the kick row are separate writes; the ones that fail without
 * hurting the kick are logged and left.</p>
 */
@Singleton
public class KickService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KickService.class);

    private static final Pattern PROXY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** Metadata key of the time a kick stops waiting for the proxies. */
    static final String DEADLINE_KEY = "kickDeadlineAt";

    private final PunishmentRepository punishments;
    private final PunishmentProfileRepository profiles;
    private final ProfileSlots slots;
    private final ProfileCache profileCache;
    private final KickStore store;
    private final KickDeadline deadline;
    private final KickEventPublisher events;
    private final SectionService sections;
    private final CaseTimeline caseTimeline;
    private final StaffLinkService staffLinks;
    private final StaffAuditRecorder audit;
    private final ExemptionGuard exemptions;

    public KickService(
            PunishmentRepository punishments,
            PunishmentProfileRepository profiles,
            ProfileSlots slots,
            ProfileCache profileCache,
            KickStore store,
            KickDeadline deadline,
            KickEventPublisher events,
            SectionService sections,
            CaseTimeline caseTimeline,
            StaffLinkService staffLinks,
            StaffAuditRecorder audit,
            ExemptionGuard exemptions
    ) {
        this.exemptions = exemptions;
        this.audit = audit;
        this.punishments = punishments;
        this.profiles = profiles;
        this.slots = slots;
        this.profileCache = profileCache;
        this.store = store;
        this.deadline = deadline;
        this.events = events;
        this.sections = sections;
        this.caseTimeline = caseTimeline;
        this.staffLinks = staffLinks;
    }

    /**
     * Kicks one player.
     *
     * @return {@link KickDTO.Accepted}, or a {@link KickDTO.Error} for an invalid request, an unknown or disabled
     * section, or an {@code Idempotency-Key} already used for a different kick; in every error case nothing changed
     */
    public KickDTO kick(KickDTO.CreateRequest request, String idempotencyKey, Actor actor) {
        Optional<KickDTO.Error> invalid = KickValidator.validateKey(idempotencyKey).or(() -> KickValidator.validate(request));
        if (invalid.isPresent()) {
            return invalid.get();
        }
        KickScope scope = KickValidator.scopeOf(request);
        String target = scope == KickScope.NETWORK ? null : request.target();
        Optional<KickDTO.Error> sectionProblem = checkSection(scope, target);
        if (sectionProblem.isPresent()) {
            return sectionProblem.get();
        }

        UUID id = kickId("kick", actor, idempotencyKey);
        String identifier = IdGenerator.generateId(id);
        Optional<PunishmentEntity> existing = this.punishments.findById(identifier);
        if (existing.isPresent()) {
            return repeat(existing.get(), request.owner(), scope, target);
        }

        Attribution attribution = actor.attribution();
        ExemptionGuard.Outcome exempt = this.exemptions.check(request.owner(), scope == KickScope.SECTION ? target : null,
                attribution, actor, request.exemptionOverride());
        if (exempt instanceof ExemptionGuard.Outcome.Refused refused) {
            return new KickDTO.Error(refused.message(), refused.kind());
        }
        long now = this.deadline.now();
        long deadlineAt = this.deadline.deadlineAt();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, now);
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
        metadata.put("reason", request.reason().strip());
        metadata.put(DEADLINE_KEY, deadlineAt);
        if (exempt instanceof ExemptionGuard.Outcome.Allowed allowed && allowed.overridden()) {
            metadata.put(ExemptionGuard.OVERRIDE_METADATA_KEY, allowed.record());
        }
        // A kick has no expiry: it is a moment. It also takes no slot, so it is never active.
        PunishmentEntity kick = new PunishmentEntity(identifier, attribution.source(), request.owner(),
                scope == KickScope.SECTION ? target : null, false, now, null, metadata);
        kick.recordKick(scope.name(), target);
        kick.setRecordedByOid(attribution.recordedByOid());
        kick.setActorOid(attribution.actorOid());
        PunishmentEntity saved;
        try {
            saved = this.punishments.save(kick);
        } catch (RuntimeException e) {
            // Two requests with one key at once: the other one won the insert.
            Optional<PunishmentEntity> winner = this.punishments.findById(identifier);
            if (winner.isEmpty()) {
                throw e;
            }
            return repeat(winner.get(), request.owner(), scope, target);
        }

        List<String> warnings = warningsFor(request.owner(), scope, target);
        announce(saved, id, scope, target, now);
        if (exempt instanceof ExemptionGuard.Outcome.Allowed allowed && allowed.overridden()) {
            this.exemptions.announceOverride(request.owner(), scope == KickScope.SECTION ? target : null, allowed.record());
        }
        this.audit.record(attribution, actor, AuditAction.KICK_CREATE, AuditTarget.ofPlayer(request.owner()));
        return new KickDTO.Accepted(id.toString(), KickKind.PLAYER, KickStatus.PENDING, deadlineAt, warnings, false);
    }

    /**
     * Checks what a template kick step needs before it is applied: the reason, and a scope with a target
     * that exists. Nothing is written.
     *
     * @return the {@link KickDTO.Error} to answer, or empty when the kick can be recorded
     */
    public Optional<KickDTO.Error> checkTemplateKick(String owner, KickScope scope, String target, String reason) {
        Optional<KickDTO.Error> invalid = KickValidator.validate(new KickDTO.CreateRequest(owner, scope, target, reason));
        if (invalid.isPresent()) {
            return invalid;
        }
        return checkSection(scope == null ? KickScope.NETWORK : scope, target);
    }

    /**
     * Records the kick a template step stands for: a {@code KICK} row of the template's escalation group (so it
     * counts for escalation), listed in the player's history and in its case, announced to the proxies, and
     * never active. Charging the step's ELO cost is the caller's job. The request is checked with
     * {@link #checkTemplateKick} first.
     *
     * @param override the exemption override record for the metadata; empty unless an admin's override let the kick through
     * @return the stored kick
     */
    public PunishmentEntity kickForTemplate(String owner, KickScope scope, String target, String reason, Attribution attribution,
                                            PunishmentTemplateEntity template, String group, int stepIndex, int stepCount, int eloCost,
                                            Map<String, Object> override) {
        KickScope effective = scope == null ? KickScope.NETWORK : scope;
        String effectiveTarget = effective == KickScope.NETWORK ? null : target;
        UUID id = UUID.randomUUID();
        long now = this.deadline.now();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, now);
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
        metadata.put("reason", reason.strip());
        metadata.put(DEADLINE_KEY, this.deadline.deadlineAt());
        if (!override.isEmpty()) {
            metadata.put(ExemptionGuard.OVERRIDE_METADATA_KEY, override);
        }
        PunishmentEntity kick = new PunishmentEntity(IdGenerator.generateId(id), attribution.source(), owner,
                effective == KickScope.SECTION ? effectiveTarget : null, false, now, template, metadata);
        kick.recordKick(effective.name(), effectiveTarget);
        kick.recordEscalation(group, stepIndex, stepCount, eloCost);
        kick.setRecordedByOid(attribution.recordedByOid());
        kick.setActorOid(attribution.actorOid());
        PunishmentEntity saved = this.punishments.save(kick);
        announce(saved, id, effective, effectiveTarget, now);
        return saved;
    }

    /** The kick is stored: list it in the history and its case, and tell the proxies. */
    private void announce(PunishmentEntity saved, UUID id, KickScope scope, String target, long now) {
        addToHistory(saved);
        this.caseTimeline.kicked(saved);
        this.events.kicked(id, saved.getOwner(), scope, target, now);
    }

    /**
     * Kicks everybody from a server or section: one audited action, no punishment, case or ELO.
     *
     * @return {@link KickDTO.Accepted}, or a {@link KickDTO.Error}; in every error case nothing changed
     */
    public KickDTO kickMass(KickDTO.MassCreateRequest request, String idempotencyKey, Actor actor) {
        Optional<KickDTO.Error> invalid = KickValidator.validateKey(idempotencyKey).or(() -> KickValidator.validate(request));
        if (invalid.isPresent()) {
            return invalid.get();
        }
        Optional<KickDTO.Error> sectionProblem = checkSection(request.scope(), request.target());
        if (sectionProblem.isPresent()) {
            return sectionProblem.get();
        }

        UUID id = kickId("kick-mass", actor, idempotencyKey);
        Optional<KickStore.Action> existing = this.store.findAction(id);
        if (existing.isPresent()) {
            return repeat(existing.get(), request);
        }
        long now = this.deadline.now();
        long deadlineAt = this.deadline.deadlineAt();
        KickStore.Action action = new KickStore.Action(id, request.scope(), request.target(), request.reason().strip(), actor.oid(), now, deadlineAt);
        if (!this.store.insertAction(action)) {
            return repeat(this.store.findAction(id).orElse(action), request);
        }
        this.events.massKicked(id, request.scope(), request.target(), now);
        this.audit.record(actor.attribution(), actor, AuditAction.KICK_MASS, massTarget(request.scope(), request.target()));
        return new KickDTO.Accepted(id.toString(), KickKind.MASS, KickStatus.PENDING, deadlineAt, List.of(), false);
    }

    /**
     * Reads a kick and how it went so far.
     *
     * @return the {@link KickDTO.Response}, or a {@link KickDTO.Error} when there is no such kick
     */
    public KickDTO find(String id) {
        Optional<UUID> uuid = parse(id);
        if (uuid.isEmpty()) {
            return notFound();
        }
        Optional<PunishmentEntity> kick = findKick(uuid.get());
        if (kick.isPresent()) {
            return playerResponse(uuid.get(), kick.get());
        }
        Optional<KickStore.Action> action = this.store.findAction(uuid.get());
        if (action.isPresent()) {
            return massResponse(action.get());
        }
        return notFound();
    }

    /**
     * Stores what one proxy did about a kick. Answering again for the same kick and proxy changes nothing.
     *
     * @return the stored {@link KickDTO.Ack}, or a {@link KickDTO.Error} for an invalid proxy id or an unknown kick
     */
    public KickDTO acknowledge(String id, KickDTO.Ack ack) {
        Optional<UUID> uuid = parse(id);
        if (uuid.isEmpty()) {
            return notFound();
        }
        if (ack.proxyId() == null || !PROXY_ID.matcher(ack.proxyId()).matches()) {
            return new KickDTO.Error("proxyId must be 1 to 64 characters of letters, digits, '.', '_', ':' and '-'", ErrorKind.VALIDATION);
        }
        if (findKick(uuid.get()).isEmpty() && this.store.findAction(uuid.get()).isEmpty()) {
            return notFound();
        }
        this.store.recordDelivery(uuid.get(), new KickStore.Delivery(ack.proxyId(), ack.found(), ack.moved(), this.deadline.now()));
        return ack;
    }

    private Optional<KickDTO.Error> checkSection(KickScope scope, String target) {
        if (scope != KickScope.SECTION) {
            return Optional.empty();
        }
        SectionDTO.Response section = this.sections.find(target).orElse(null);
        if (section == null) {
            return Optional.of(new KickDTO.Error("there is no such section", ErrorKind.KICK_TARGET_INVALID));
        }
        if (!section.enabled()) {
            return Optional.of(new KickDTO.Error("section '" + section.key() + "' is disabled", ErrorKind.CONFLICT));
        }
        return Optional.empty();
    }

    private List<String> warningsFor(String owner, KickScope scope, String target) {
        List<String> warnings = new ArrayList<>();
        if (scope == KickScope.SECTION) {
            PunishmentProfileEntity profile = this.profiles.findById(owner).orElse(null);
            PunishmentEntity active = profile == null ? null : profile.getActive().get(target);
            if (active != null && active.canBeActive() && active.isPermanent()) {
                warnings.add("the player is permanently punished in section '" + target + "'; the kick does not change that");
            }
        }
        return warnings;
    }

    /** The kick is stored; a failure to list it in the player's history is logged and leaves it. */
    private void addToHistory(PunishmentEntity kick) {
        try {
            this.slots.appendHistory(kick.getOwner(), kick.getIdentifier());
            this.profileCache.evict(kick.getOwner());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not add kick {} to the player's history", kick.getIdentifier(), e);
        }
    }

    private KickDTO repeat(PunishmentEntity existing, String owner, KickScope scope, String target) {
        boolean same = existing.getKind() == PunishmentKind.KICK
                && owner.equals(existing.getOwner())
                && scope.name().equals(existing.getKickScope())
                && java.util.Objects.equals(target, existing.getKickTarget());
        if (!same) {
            return conflict();
        }
        UUID id = idOf(existing);
        KickStatus status = statusOf(KickKind.PLAYER, existing, id);
        return new KickDTO.Accepted(id.toString(), KickKind.PLAYER, status, deadlineAtOf(existing), List.of(), true);
    }

    private KickDTO repeat(KickStore.Action existing, KickDTO.MassCreateRequest request) {
        boolean same = existing.scope() == request.scope() && existing.target().equals(request.target());
        if (!same) {
            return conflict();
        }
        KickStatus status = KickStatus.derive(KickKind.MASS, existing.deadlineAt(), this.deadline.now(),
                KickDeliveries.of(this.store.deliveries(existing.id())));
        return new KickDTO.Accepted(existing.id().toString(), KickKind.MASS, status, existing.deadlineAt(), List.of(), true);
    }

    private static KickDTO.Error conflict() {
        return new KickDTO.Error("the Idempotency-Key was already used for a different kick", ErrorKind.CONFLICT);
    }

    private KickDTO playerResponse(UUID id, PunishmentEntity kick) {
        KickDeliveries deliveries = KickDeliveries.of(this.store.deliveries(id));
        long deadlineAt = deadlineAtOf(kick);
        KickStatus status = KickStatus.derive(KickKind.PLAYER, deadlineAt, this.deadline.now(), deliveries);
        return new KickDTO.Response(id.toString(), KickKind.PLAYER, kick.getOwner(), KickScope.valueOf(kick.getKickScope()), kick.getKickTarget(),
                reasonOf(kick), actorName(kick.getRecordedByOid()), kick.getCreatedAt() == null ? 0 : kick.getCreatedAt(), status,
                deliveries.deliveredCount(), deadlineAt);
    }

    private KickDTO massResponse(KickStore.Action action) {
        KickDeliveries deliveries = KickDeliveries.of(this.store.deliveries(action.id()));
        KickStatus status = KickStatus.derive(KickKind.MASS, action.deadlineAt(), this.deadline.now(), deliveries);
        return new KickDTO.Response(action.id().toString(), KickKind.MASS, null, action.scope(), action.target(), action.reason(),
                actorName(action.actorOid()), action.createdAt(), status, deliveries.deliveredCount(), action.deadlineAt());
    }

    private KickStatus statusOf(KickKind kind, PunishmentEntity kick, UUID id) {
        return KickStatus.derive(kind, deadlineAtOf(kick), this.deadline.now(), KickDeliveries.of(this.store.deliveries(id)));
    }

    private Optional<PunishmentEntity> findKick(UUID id) {
        return this.punishments.findById(IdGenerator.generateId(id)).filter(kick -> kick.getKind() == PunishmentKind.KICK);
    }

    private long deadlineAtOf(PunishmentEntity kick) {
        long createdAt = kick.getCreatedAt() == null ? 0 : kick.getCreatedAt();
        return kick.getMetaData().get(DEADLINE_KEY) instanceof Number number
                ? number.longValue()
                : createdAt + this.deadline.ackTimeout().toMillis();
    }

    private static String reasonOf(PunishmentEntity kick) {
        return kick.getMetaData().get("reason") instanceof String reason ? reason : "";
    }

    private String actorName(UUID oid) {
        return oid == null ? null : this.staffLinks.displayName(oid).orElse(null);
    }

    /** The kick's id: the UUID the identifier was made from. */
    private static UUID idOf(PunishmentEntity kick) {
        byte[] bytes = java.util.Base64.getUrlDecoder().decode(kick.getIdentifier());
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID kickId(String namespace, Actor actor, String idempotencyKey) {
        return UUID.nameUUIDFromBytes((namespace + ":" + actor.oid() + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static AuditTarget massTarget(KickScope scope, String target) {
        return switch (scope) {
            case NETWORK -> AuditTarget.none();
            case SECTION -> AuditTarget.ofSection(target);
            default -> AuditTarget.ofServer(target);
        };
    }

    private static KickDTO.Error notFound() {
        return new KickDTO.Error("there is no such kick", ErrorKind.NOT_FOUND);
    }
}
