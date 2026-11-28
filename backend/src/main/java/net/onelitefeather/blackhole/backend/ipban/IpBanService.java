package net.onelitefeather.blackhole.backend.ipban;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import net.onelitefeather.blackhole.backend.ipban.dto.IpBanDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates, lifts and reads IP bans. Every expected failure is answered with the {@link IpBanDTO.Error} variant; the
 * role rules live here, not in the controller. A moderator may create a ban that expires within
 * {@code blackhole.ipban.moderator-max-duration}; an administrator may create a permanent one.
 */
@Singleton
public class IpBanService {

    private static final int MAX_REASON = 200;
    private static final int MAX_LISTING = 200;

    private final IpBanStore store;
    private final IpBanProtection protection;
    private final IpBanEvents events;
    private final IpBanChanges changes;
    private final Clock clock;
    private final Duration moderatorMaxDuration;

    public IpBanService(IpBanStore store, IpBanProtection protection, IpBanEvents events, IpBanChanges changes, Clock clock,
                        @Value("${blackhole.ipban.moderator-max-duration:P30D}") Duration moderatorMaxDuration) {
        this.store = store;
        this.protection = protection;
        this.events = events;
        this.changes = changes;
        this.clock = clock;
        this.moderatorMaxDuration = moderatorMaxDuration;
    }

    /** Stores a ban of one address or range; answers the created ban (with any warnings) or the reason it was refused. */
    public IpBanDTO create(IpBanDTO.CreateRequest request, Actor actor) {
        Optional<IpBanNetwork> parsed = IpBanNetwork.parse(request.address());
        if (parsed.isEmpty()) {
            return error(ErrorKind.VALIDATION, "IPBAN_ADDRESS_INVALID", "address must be an IPv4 or IPv6 address or a CIDR range");
        }
        IntelPrefix prefix = parsed.get().network();
        if (prefix.length() < IpBanNetwork.minimumLength(prefix)) {
            return error(ErrorKind.IP_BAN_NOT_ALLOWED, "IPBAN_RANGE_TOO_WIDE",
                    "the range is wider than /16 for IPv4 or /32 for IPv6; use the firewall directly for wider ranges");
        }
        if (this.protection.overlapping(prefix).isPresent()) {
            return error(ErrorKind.IP_BAN_NOT_ALLOWED, "IPBAN_PROTECTED", "the range overlaps a protected network");
        }
        String reason = normalize(request.reason());
        if (reason == null) {
            return error(ErrorKind.VALIDATION, "IPBAN_REASON_INVALID", "reason must be 1 to 200 characters");
        }
        IpBanSource source = request.source() == null ? IpBanSource.PANEL : parseSource(request.source());
        if (source == null || source == IpBanSource.PUNISHMENT || source == IpBanSource.IMPORT) {
            return error(ErrorKind.VALIDATION, "IPBAN_SOURCE_INVALID", "source must be PANEL or COMMAND");
        }
        long now = this.clock.millis();
        Long expiresAt = request.expiresAt();
        if (expiresAt != null && expiresAt <= now) {
            return error(ErrorKind.VALIDATION, "IPBAN_EXPIRY_PAST", "the expiry must lie in the future");
        }
        if (!actor.admin()) {
            boolean permanent = expiresAt == null;
            if (permanent || expiresAt - now > this.moderatorMaxDuration.toMillis()) {
                return error(ErrorKind.FORBIDDEN, "FORBIDDEN_DURATION",
                        "a moderator may ban an address for at most " + this.moderatorMaxDuration + "; an administrator may ban permanently");
            }
        }

        String range = parsed.get().range();
        this.store.liftExpired(range, now);
        List<IpBanRecord> active = this.store.active(now);
        if (active.stream().anyMatch(ban -> ban.range().equals(range))) {
            return error(ErrorKind.CONFLICT, "ALREADY_BANNED", "this range is already banned; the existing ban is unchanged");
        }
        List<String> warnings = new ArrayList<>();
        for (IpBanRecord wider : active) {
            if (IpBanProtection.contains(wider.network(), prefix)) {
                warnings.add("the active ban " + wider.id() + " of " + wider.range() + " already covers this range");
            }
        }

        UUID id = UUID.randomUUID();
        String templateKey = blankToNull(request.templateKey());
        IpBanRecord record = new IpBanRecord(id, prefix, reason, templateKey, source, null, null, actor.oid(), now,
                expiresAt, null, null, null);
        if (!this.store.insert(record)) {
            return error(ErrorKind.CONFLICT, "ALREADY_BANNED", "this range is already banned; the existing ban is unchanged");
        }
        this.events.created(id, now);
        this.changes.changed();
        return response(record, warnings);
    }

    /** Lifts an active ban with its mandatory reason. A player's punishment is never touched. */
    public IpBanDTO lift(String id, IpBanDTO.LiftRequest request, Actor actor) {
        Optional<UUID> banId = parseId(id);
        if (banId.isEmpty()) {
            return notFound();
        }
        String reason = normalize(request.reason());
        if (reason == null) {
            return error(ErrorKind.VALIDATION, "IPBAN_LIFT_REASON_REQUIRED", "a lift needs a reason of 1 to 200 characters");
        }
        Optional<IpBanRecord> current = this.store.find(banId.get());
        if (current.isEmpty()) {
            return notFound();
        }
        long now = this.clock.millis();
        if (!current.get().isActive(now)) {
            return error(ErrorKind.CONFLICT, "IPBAN_NOT_ACTIVE", "this IP ban is not active");
        }
        if (!this.store.lift(banId.get(), actor.oid(), reason, now)) {
            return error(ErrorKind.CONFLICT, "IPBAN_NOT_ACTIVE", "this IP ban is not active");
        }
        this.events.lifted(banId.get(), now);
        this.changes.changed();
        IpBanRecord banned = current.get();
        return response(new IpBanRecord(banned.id(), banned.network(), banned.reason(), banned.templateKey(),
                banned.source(), banned.punishmentId(), banned.caseId(), banned.actorOid(), banned.createdAt(),
                banned.expiresAt(), now, actor.oid(), reason), List.of());
    }

    /** One ban, for staff. */
    public IpBanDTO find(String id) {
        return parseId(id).flatMap(this.store::find)
                .<IpBanDTO>map(record -> response(record, List.of()))
                .orElseGet(IpBanService::notFound);
    }

    /** The newest bans, active or not, for staff. */
    public IpBanDTO.Listing list(int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LISTING));
        return new IpBanDTO.Listing(this.store.newest(bounded).stream()
                .map(record -> response(record, List.of())).toList());
    }

    /** Every active ban, for the proxy. Carries no actor and no case. */
    public IpBanDTO.ActiveList active() {
        long now = this.clock.millis();
        return new IpBanDTO.ActiveList(this.store.active(now).stream()
                .map(ban -> new IpBanDTO.ActiveEntry(ban.id(), ban.range(), ban.expiresAt(), ban.reason())).toList());
    }

    private static IpBanDTO.Response response(IpBanRecord ban, List<String> warnings) {
        return new IpBanDTO.Response(ban.id(), ban.range(), ban.reason(), ban.templateKey(), ban.source().name(),
                ban.expiresAt(), ban.createdAt(), ban.actorOid(), ban.liftedAt(), ban.liftedByOid(), ban.liftReason(),
                List.copyOf(warnings));
    }

    private static IpBanDTO notFound() {
        return error(ErrorKind.NOT_FOUND, "IPBAN_NOT_FOUND", "there is no such IP ban");
    }

    private static IpBanDTO error(ErrorKind kind, String code, String message) {
        return new IpBanDTO.Error(message, kind, code);
    }

    private static Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** @return the stripped reason, or null when it is blank or longer than 200 characters */
    private static String normalize(String reason) {
        if (reason == null) {
            return null;
        }
        String stripped = reason.strip();
        return stripped.isEmpty() || stripped.length() > MAX_REASON ? null : stripped;
    }

    /** @return the source a request may name, or null for an unknown name */
    private static IpBanSource parseSource(String name) {
        try {
            return IpBanSource.valueOf(name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
