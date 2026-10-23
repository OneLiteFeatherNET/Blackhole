package net.onelitefeather.blackhole.backend.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns the validated token into the {@link Actor} of a request and keeps its {@code staff_actor}
 * row current. Only the {@code oid} and {@code name} claims are read: email, user principal name
 * and preferred username are neither read nor stored, and nothing here is logged but the oid.
 *
 * <p>The upsert is plain repository calls, no {@code @Transactional} (unusable in this codebase): it
 * inserts on the first write, updates when the name or kind changed or the last-seen time is older
 * than {@link #REFRESH_AFTER}, and is idempotent, so two concurrent first writes of the same
 * principal both end with one row.</p>
 */
@Singleton
public class ActorResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ActorResolver.class);

    /** How stale {@code last_seen_at} may get before a write refreshes it; keeps reads from becoming writes. */
    static final Duration REFRESH_AFTER = Duration.ofHours(1);

    private static final String NAME_CLAIM = "name";

    private final StaffActorRepository actors;
    private final Clock clock;

    public ActorResolver(StaffActorRepository actors, Clock clock) {
        this.actors = actors;
        this.clock = clock;
    }

    /**
     * The verified actor of the request, recorded in {@code staff_actor}.
     *
     * @throws HttpStatusException 401 if the token carries no valid object id
     */
    public Actor resolve(Authentication authentication) {
        Map<String, Object> claims = authentication.getAttributes();
        UUID oid = parseOid(claims.get(BlackholeRolesFinder.OBJECT_ID_CLAIM));
        if (oid == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "the token carries no valid object id");
        }
        String name = claims.get(NAME_CLAIM) instanceof String text && !text.isBlank() ? text : null;
        ActorKind kind = BlackholeRolesFinder.isDelegated(claims) ? ActorKind.HUMAN : ActorKind.SERVICE;
        record(oid, kind, name);
        return new Actor(oid, name, kind, authentication.getRoles().contains(Roles.PROXY));
    }

    private void record(UUID oid, ActorKind kind, String name) {
        long now = this.clock.millis();
        Optional<StaffActorEntity> existing = this.actors.findById(oid);
        if (existing.isEmpty()) {
            try {
                this.actors.save(new StaffActorEntity(oid, kind, name, now));
                return;
            } catch (RuntimeException insertFailed) {
                // Another request of the same principal inserted first: continue with its row.
                existing = this.actors.findById(oid);
                if (existing.isEmpty()) {
                    throw insertFailed;
                }
            }
        }
        StaffActorEntity actor = existing.get();
        boolean nameChanged = name != null && !name.equals(actor.getDisplayName());
        boolean stale = now - actor.getLastSeenAt() >= REFRESH_AFTER.toMillis();
        if (nameChanged || actor.getKind() != kind || stale) {
            if (nameChanged) {
                actor.setDisplayName(name);
            }
            actor.setKind(kind);
            actor.setLastSeenAt(now);
            this.actors.update(actor);
            LOG.debug("Refreshed staff actor {}", oid);
        }
    }

    private static UUID parseOid(Object claim) {
        try {
            return claim instanceof String text ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
