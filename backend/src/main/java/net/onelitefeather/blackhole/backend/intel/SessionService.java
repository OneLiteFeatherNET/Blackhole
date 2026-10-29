package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evasion.service.IpCorrelationService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.intel.IpEnricher.IpEnrichment;
import net.onelitefeather.blackhole.backend.intel.dto.SessionDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Records each login attempt and session the proxies report, enriches the IP offline, and reads a player's sessions
 * back for the panel. The session event carries the owner hash and the session id only.
 */
@Singleton
public class SessionService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;
    private static final Pattern OWNER = Pattern.compile("^[a-fA-F0-9]{128}$");
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");
    private static final int TEXT_MAX = 64;
    private static final int HOST_MAX = 255;

    private final SessionStore store;
    private final IpEnricher enricher;
    private final IpCorrelationService correlation;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final boolean enabled;

    public SessionService(
            SessionStore store,
            IpEnricher enricher,
            IpCorrelationService correlation,
            DomainEventPublisher events,
            Clock clock,
            @Value("${blackhole.intel.enabled:true}") boolean enabled
    ) {
        this.store = store;
        this.enricher = enricher;
        this.correlation = correlation;
        this.events = events;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** False when {@code blackhole.intel.enabled} is off: the routes answer 404 and nothing is written. */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Stores a login attempt. A repeat with the same session id returns the stored record and writes nothing.
     */
    public SessionDTO start(SessionDTO.StartRequest request) {
        Optional<UUID> sessionId = uuid(request.sessionId());
        Optional<UUID> playerUuid = uuid(request.playerUuid());
        if (sessionId.isEmpty() || playerUuid.isEmpty()) {
            return error("sessionId and playerUuid must be UUIDs", ErrorKind.VALIDATION);
        }
        if (request.owner() == null || !OWNER.matcher(request.owner()).matches()) {
            return error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        if (request.outcome() == null) {
            return error("outcome must be ALLOWED or DENIED", ErrorKind.VALIDATION);
        }
        if (request.playerName() == null || !PLAYER_NAME.matcher(request.playerName()).matches()) {
            return error("playerName must be 1 to 16 letters, digits, underscores or dots", ErrorKind.VALIDATION);
        }
        if (IpLiteral.parse(request.ip()).isEmpty()) {
            return error("ip must be an IPv4 or IPv6 address", ErrorKind.VALIDATION);
        }
        if (request.virtualHost() != null && request.virtualHost().length() > HOST_MAX) {
            return error("virtualHost is longer than 255 characters", ErrorKind.VALIDATION);
        }
        Optional<SessionRecord> stored = this.store.find(sessionId.get());
        if (stored.isPresent()) {
            return response(stored.get());
        }

        IpEnrichment enrichment = this.enricher.enrich(request.ip());
        String token = this.correlation.tokenFor(request.ip()).orElse(null);
        long now = this.clock.millis();
        SessionRecord record = new SessionRecord(sessionId.get(), request.owner(), playerUuid.get(), request.playerName(),
                request.ip(), token, enrichment.asn(), enrichment.asOrg(), enrichment.countryCode(), null, null,
                request.protocolVersion(), request.virtualHost(), request.outcome(), now, null);
        if (!this.store.insert(record)) {
            return this.store.find(sessionId.get()).<SessionDTO>map(SessionService::response)
                    .orElseGet(() -> error("the session could not be stored", ErrorKind.CONFLICT));
        }
        this.events.publish("session.started", Map.of("owner", request.owner(), "sessionId", sessionId.get().toString()));
        if (token != null) {
            this.correlation.correlate(token, now);
        }
        return response(record);
    }

    /**
     * Sets the client brand and version, and the end time when the player disconnected. The first end time is kept.
     */
    public SessionDTO update(String sessionId, SessionDTO.UpdateRequest request) {
        Optional<UUID> id = uuid(sessionId);
        if (id.isEmpty()) {
            return error("sessionId must be a UUID", ErrorKind.VALIDATION);
        }
        if (request.clientBrand() != null && request.clientBrand().length() > TEXT_MAX
                || request.clientVersion() != null && request.clientVersion().length() > TEXT_MAX) {
            return error("clientBrand and clientVersion are at most 64 characters", ErrorKind.VALIDATION);
        }
        Optional<SessionRecord> existing = this.store.find(id.get());
        if (existing.isEmpty()) {
            return error("no such session", ErrorKind.NOT_FOUND);
        }
        if (request.clientBrand() != null || request.clientVersion() != null) {
            this.store.updateClient(id.get(), request.clientBrand(), request.clientVersion());
        }
        if (request.ended() && existing.get().endedAt() == null) {
            this.store.markEnded(id.get(), this.clock.millis());
            this.events.publish("session.ended", Map.of("owner", existing.get().ownerHash(), "sessionId", id.get().toString()));
        }
        return this.store.find(id.get()).<SessionDTO>map(SessionService::response)
                .orElseGet(() -> error("no such session", ErrorKind.NOT_FOUND));
    }

    /** One page of the sessions of an owner, newest first. */
    public SessionDTO listForOwner(String owner, Integer page, Integer size) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            return error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return error("page must be 0 or more and size 1 to 100", ErrorKind.VALIDATION);
        }
        List<SessionRecord> rows = this.store.findByOwner(owner, pageSize + 1, pageNumber * pageSize);
        boolean hasMore = rows.size() > pageSize;
        List<SessionDTO.Response> sessions = new ArrayList<>();
        rows.stream().limit(pageSize).map(SessionService::response).forEach(sessions::add);
        return new SessionDTO.Page(sessions, pageNumber, pageSize, hasMore);
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static SessionDTO.Error error(String message, ErrorKind kind) {
        return new SessionDTO.Error(message, kind);
    }

    static SessionDTO.Response response(SessionRecord r) {
        return new SessionDTO.Response(r.sessionId().toString(), r.ownerHash(), r.playerUuid().toString(), r.playerName(), r.ip(),
                r.asn(), r.asOrg(), r.countryCode(), r.clientBrand(), r.clientVersion(), r.protocolVersion(), r.virtualHost(),
                r.outcome(), r.startedAt(), r.endedAt());
    }
}
