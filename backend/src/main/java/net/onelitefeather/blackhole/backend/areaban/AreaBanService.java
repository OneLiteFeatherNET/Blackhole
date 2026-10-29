package net.onelitefeather.blackhole.backend.areaban;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.areaban.dto.AreaBanDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
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

    /** The caller of a request: the token's oid, and whether it carries the admin role. */
    public record Actor(String oid, boolean admin) {
    }

    private final AreaBanStore store;
    private final AreaBanCache cache;
    private final Clock clock;

    public AreaBanService(AreaBanStore store, AreaBanCache cache, Clock clock) {
        this.store = store;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Creates a ban. An unlifted ban of the same area that has already expired is lifted first, so an expired ban does not
     * block a new one.
     *
     * @param durationSeconds the ban length, or null for a permanent ban
     */
    public AreaBanDTO create(AreaBanDTO.CreateRequest request, Actor actor) {
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
        if (kind.get() == AreaBan.Kind.COUNTRY && !actor.admin()) {
            return error("only an admin may ban a country", ErrorKind.FORBIDDEN_KIND);
        }
        if (request.durationSeconds() != null && request.durationSeconds() <= 0) {
            return error("durationSeconds must be positive, or omitted for a permanent ban", ErrorKind.VALIDATION);
        }
        Duration duration = request.durationSeconds() == null ? null : Duration.ofSeconds(request.durationSeconds());
        if (!actor.admin() && (duration == null || duration.compareTo(MODERATOR_LIMIT) > 0)) {
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
        AreaBan ban = new AreaBan(UUID.randomUUID(), kind.get(), value.get(), reason, AreaBan.Source.MANUAL, actor.oid(),
                now, duration == null ? null : now + duration.toMillis(), null, null, null);
        if (!this.store.insert(ban)) {
            return error("this area is already banned", ErrorKind.CONFLICT);
        }
        this.cache.reload();
        return response(ban, now);
    }

    /** Lifts an unlifted ban. A reason is required, and the ban must still apply. */
    public AreaBanDTO lift(String id, AreaBanDTO.LiftRequest request, Actor actor) {
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
        AreaBan lifted = ban.get().lifted(now, reason, actor.oid());
        if (!this.store.lift(lifted)) {
            return error("this area ban is already lifted", ErrorKind.CONFLICT);
        }
        this.cache.reload();
        return response(lifted, now);
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
