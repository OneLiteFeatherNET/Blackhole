package net.onelitefeather.blackhole.backend.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** The only roles a snapshot stores, in the order they are written. */
    private static final List<String> STAFF_ROLES = List.of(Roles.ADMIN, Roles.MODERATOR, Roles.VIEWER);

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
        Collection<String> roles = authentication.getRoles();
        // The role snapshot is taken from a HUMAN token only; an app-only token is never a staff member.
        String staffRoles = kind == ActorKind.HUMAN ? staffRoles(roles) : null;
        record(oid, kind, name, staffRoles);
        return new Actor(oid, name, kind, roles.contains(Roles.PROXY), roles.contains(Roles.ADMIN), false, roles.contains(Roles.DISCORD));
    }

    /**
     * Writes the principal's row. For a HUMAN token {@code staffRoles} is the role snapshot of this request (null when the token
     * holds no staff role); it is written when it changed or was last taken more than {@link #REFRESH_AFTER} ago.
     */
    private void record(UUID oid, ActorKind kind, String name, @Nullable String staffRoles) {
        long now = this.clock.millis();
        boolean human = kind == ActorKind.HUMAN;
        Optional<StaffActorEntity> existing = this.actors.findById(oid);
        if (existing.isEmpty()) {
            try {
                StaffActorEntity created = new StaffActorEntity(oid, kind, name, now);
                if (human) {
                    created.setRoles(staffRoles);
                    created.setRolesSeenAt(now);
                }
                this.actors.save(created);
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
        boolean snapshotDue = human && (!Objects.equals(staffRoles, actor.getRoles()) || actor.getRolesSeenAt() == null
                || now - actor.getRolesSeenAt() >= REFRESH_AFTER.toMillis());
        if (nameChanged || actor.getKind() != kind || stale || snapshotDue) {
            if (nameChanged) {
                actor.setDisplayName(name);
            }
            actor.setKind(kind);
            actor.setLastSeenAt(now);
            if (snapshotDue) {
                actor.setRoles(staffRoles);
                actor.setRolesSeenAt(now);
            }
            this.actors.update(actor);
            LOG.debug("Refreshed staff actor {}", oid);
        }
    }

    /** The staff role names of the token in a fixed order ({@code ADMIN,MODERATOR,VIEWER}), or null when it holds none. */
    @Nullable
    static String staffRoles(Collection<String> roles) {
        String joined = STAFF_ROLES.stream().filter(roles::contains).collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    private static UUID parseOid(Object claim) {
        try {
            return claim instanceof String text ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
