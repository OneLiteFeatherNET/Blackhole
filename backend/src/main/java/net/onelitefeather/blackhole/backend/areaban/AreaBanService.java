package net.onelitefeather.blackhole.backend.areaban;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.areaban.dto.AreaBanDTO;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffDelegation;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and lifts area bans, applying who may do what (design decision 12): MODERATOR may ban one ASN for up to seven
 * days, ADMIN may ban a country or an ASN without a limit. Any staff member may lift a ban.
 */
@Singleton
public class AreaBanService {

    /** The longest ban a moderator may create. */
    static final Duration MODERATOR_LIMIT = Duration.ofDays(7);
    static final int REASON_MAX = 200;

    private final AreaBanStore store;
    private final AreaBanCache cache;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final StaffDelegation delegation;
    private final StaffAuditRecorder audit;

    public AreaBanService(AreaBanStore store, AreaBanCache cache, DomainEventPublisher events, Clock clock,
                          StaffDelegation delegation, StaffAuditRecorder audit) {
        this.store = store;
        this.cache = cache;
        this.events = events;
        this.clock = clock;
        this.delegation = delegation;
        this.audit = audit;
    }

    /**
     * Creates the automatic ban of the burst rule for one ASN. Nothing happens when the ASN already has an active ban, or
     * when another replica inserted one first.
     *
     * @return the ban that was created, or empty when the ASN was already banned
     */
    public Optional<AreaBan> createBurstBan(long asn, Duration duration) {
        String value = Long.toString(asn);
        long now = this.clock.millis();
        Optional<AreaBan> existing = this.store.findUnlifted(AreaBan.Kind.ASN, value);
        if (existing.isPresent()) {
            if (existing.get().isActiveAt(now)) {
                return Optional.empty();
            }
            this.store.lift(existing.get().lifted(now, "expired", null));
        }
        AreaBan ban = new AreaBan(UUID.randomUUID(), AreaBan.Kind.ASN, value, "burst of join guard refusals",
                AreaBan.Source.AUTO_BURST, null, now, now + duration.toMillis(), null, null, null);
        if (!this.store.insert(ban)) {
            return Optional.empty();
        }
        this.cache.reload();
        this.events.publish(AreaBanEvents.CREATED_EVENT, AreaBanEvents.created(ban, null));
        return Optional.of(ban);
    }

    /**
     * Creates a ban. An unlifted ban of the same area that has already expired is lifted first, so an expired ban does not
     * block a new one. The limits apply to the staff member the write is for: the delegated one for a machine caller.
     *
     * @param actor the verified caller; a machine caller names the staff member in the request's onBehalfOf
     */
    public AreaBanDTO create(AreaBanDTO.CreateRequest request, Actor actor) {
        StaffDelegation.Outcome delegated = this.delegation.attributeGuard(actor, request.onBehalfOf());
        if (delegated instanceof StaffDelegation.Outcome.Refused refused) {
            return error(refused.message(), refused.errorKind());
        }
        StaffDelegation.Outcome.Allowed by = (StaffDelegation.Outcome.Allowed) delegated;
        Optional<AreaBan.Kind> kind = parseKind(request.kind());
        if (kind.isEmpty()) {
            return error("kind must be ASN or COUNTRY", ErrorKind.VALIDATION);
        }
        Optional<String> value = kind.get() == AreaBan.Kind.ASN
                ? AreaBanNormalizer.asn(request.value())
                : AreaBanNormalizer.country(request.value());
        if (value.isEmpty()) {
            return error(kind.get() == AreaBan.Kind.ASN
                    ? "an ASN is a number, with or without the AS prefix"
                    : "a country is an ISO 3166 alpha-2 code", ErrorKind.VALIDATION);
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > REASON_MAX) {
            return error("reason must be 1 to 200 characters", ErrorKind.VALIDATION);
        }
        if (kind.get() == AreaBan.Kind.COUNTRY && !by.admin()) {
            return error("only an admin may ban a country", ErrorKind.FORBIDDEN_KIND);
        }
        if (request.durationSeconds() != null && request.durationSeconds() <= 0) {
            return error("durationSeconds must be positive, or omitted for a permanent ban", ErrorKind.VALIDATION);
        }
        Duration duration = request.durationSeconds() == null ? null : Duration.ofSeconds(request.durationSeconds());
        if (!by.admin() && (duration == null || duration.compareTo(MODERATOR_LIMIT) > 0)) {
            return error("a moderator may ban for up to seven days only", ErrorKind.FORBIDDEN_DURATION);
        }

        long now = this.clock.millis();
        Optional<AreaBan> existing = this.store.findUnlifted(kind.get(), value.get());
        if (existing.isPresent()) {
            if (existing.get().isActiveAt(now)) {
                return error("this area is already banned", ErrorKind.CONFLICT);
            }
            this.store.lift(existing.get().lifted(now, "expired", null));
        }
        AreaBan ban = new AreaBan(UUID.randomUUID(), kind.get(), value.get(), reason, AreaBan.Source.MANUAL,
                by.attribution().source().toString(), now, duration == null ? null : now + duration.toMillis(), null, null, null);
        if (!this.store.insert(ban)) {
            return error("this area is already banned", ErrorKind.CONFLICT);
        }
        this.cache.reload();
        this.events.publish(AreaBanEvents.CREATED_EVENT, AreaBanEvents.created(ban, by.displayName()));
        this.audit.record(by.attribution(), actor, AuditAction.AREA_BAN_CREATE, AuditTarget.ofAreaBan(ban.value()));
        return response(ban, now);
    }

    /** Lifts an unlifted ban. A reason is required, and the ban must still apply. */
    public AreaBanDTO lift(String id, AreaBanDTO.LiftRequest request, Actor actor) {
        StaffDelegation.Outcome delegated = this.delegation.attributeGuard(actor, request.onBehalfOf());
        if (delegated instanceof StaffDelegation.Outcome.Refused refused) {
            return error(refused.message(), refused.errorKind());
        }
        Attribution by = ((StaffDelegation.Outcome.Allowed) delegated).attribution();
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) {
            return error("id must be a UUID", ErrorKind.VALIDATION);
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > REASON_MAX) {
            return error("reason must be 1 to 200 characters", ErrorKind.VALIDATION);
        }
        Optional<AreaBan> ban = this.store.findById(uuid.get());
        if (ban.isEmpty()) {
            return error("no such area ban", ErrorKind.NOT_FOUND);
        }
        long now = this.clock.millis();
        if (ban.get().liftedAt() != null) {
            return error("this area ban is already lifted", ErrorKind.CONFLICT);
        }
        AreaBan lifted = ban.get().lifted(now, reason, by.source().toString());
        if (!this.store.lift(lifted)) {
            return error("this area ban is already lifted", ErrorKind.CONFLICT);
        }
        this.cache.reload();
        this.events.publish(AreaBanEvents.LIFTED_EVENT, AreaBanEvents.lifted(lifted));
        this.audit.record(by, actor, AuditAction.AREA_BAN_LIFT, AuditTarget.ofAreaBan(lifted.value()));
        return response(lifted, now);
    }

    /**
     * The area bans for the panel: the ones that apply now, or with {@code includeInactive} every ban, lifted and expired
     * ones included, so the history stays readable.
     */
    public AreaBanDTO list(boolean includeInactive) {
        long now = this.clock.millis();
        List<AreaBan> bans = includeInactive
                ? this.store.listAll()
                : this.store.listUnlifted().stream().filter(ban -> ban.isActiveAt(now)).toList();
        return new AreaBanDTO.ListResponse(bans.stream().map(ban -> response(ban, now)).toList());
    }

    private static Optional<AreaBan.Kind> parseKind(String kind) {
        try {
            return Optional.of(AreaBan.Kind.valueOf(kind));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static AreaBanDTO.Error error(String message, ErrorKind kind) {
        return new AreaBanDTO.Error(message, kind);
    }

    private static AreaBanDTO.Response response(AreaBan ban, long now) {
        return new AreaBanDTO.Response(ban.id().toString(), ban.kind().name(), ban.value(), ban.reason(), ban.source().name(),
                ban.createdAt(), ban.expiresAt(), ban.liftedAt(), ban.isActiveAt(now));
    }
}
