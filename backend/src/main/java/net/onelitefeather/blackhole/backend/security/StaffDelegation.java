package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether the Discord bot may act for a staff member named by {@code onBehalfOf} (decision 1 of the Discord staff ticket
 * actions). The bot is trusted to name the member only when the backend can check it: a live staff {@code discord_link} exists for
 * the oid, and its role snapshot ({@code staff_actor.roles}, written on every HUMAN request) is fresh and holds MODERATOR or ADMIN.
 * A removed role therefore stops working once the snapshot is older than the window.
 *
 * <p>Only the four delegation routes call this: the appeal review, the case update, the ticket close and the ticket freeze.</p>
 */
@Singleton
public class StaffDelegation {

    private final DiscordLinkRepository links;
    private final StaffActorRepository actors;
    private final Clock clock;
    private final Duration maxAge;

    public StaffDelegation(DiscordLinkRepository links, StaffActorRepository actors, Clock clock,
                           @Value("${blackhole.discord.staff-role-max-age:PT24H}") Duration maxAge) {
        this.links = links;
        this.actors = actors;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /**
     * The attribution of a delegation-capable write.
     *
     * @param caller     the verified caller
     * @param onBehalfOf the staff member's oid the bot names, or {@code null}
     * @return {@link Outcome.Allowed} with the attribution, or {@link Outcome.Refused} with the reason
     */
    public Outcome attribute(Actor caller, @Nullable UUID onBehalfOf) {
        if (!caller.discord()) {
            if (onBehalfOf != null) {
                return refused(Reason.NOT_DISCORD, "onBehalfOf is only accepted from the Discord bot on this route");
            }
            return new Outcome.Allowed(caller.attribution());
        }
        if (onBehalfOf == null) {
            return refused(Reason.REQUIRED, "the Discord bot must name the staff member with onBehalfOf");
        }
        Optional<DiscordLinkEntity> link = this.links.findByKindAndSubject(DiscordLinkKind.STAFF, onBehalfOf.toString());
        if (link.isEmpty()) {
            return refused(Reason.NOT_ELIGIBLE, "STAFF_NOT_ELIGIBLE: the staff member has no live Discord link");
        }
        Optional<StaffActorEntity> actor = this.actors.findById(onBehalfOf);
        if (actor.isEmpty()) {
            return refused(Reason.NOT_ELIGIBLE, "STAFF_NOT_ELIGIBLE: the staff member has no role snapshot");
        }
        if (!fresh(actor.get())) {
            return refused(Reason.ROLE_STALE, "STAFF_ROLE_STALE: sign in to the team panel once, then try again");
        }
        if (!holdsModeratorOrAdmin(actor.get())) {
            return refused(Reason.NOT_ELIGIBLE, "STAFF_NOT_ELIGIBLE: the staff member is not a moderator or an admin");
        }
        return new Outcome.Allowed(new Attribution(onBehalfOf, caller.oid()));
    }

    /**
     * Whether the staff member behind a Discord account may act now, for the bot's staff lookup.
     *
     * @return the staff oid and whether a press would be allowed now, or empty when the Discord user has no live staff link
     */
    public Optional<Lookup> lookup(String discordUserId) {
        return this.links.findByKindAndDiscordUserId(DiscordLinkKind.STAFF, discordUserId).map(link -> {
            UUID oid = UUID.fromString(link.getSubject());
            boolean eligible = this.actors.findById(oid)
                    .filter(this::fresh)
                    .filter(StaffDelegation::holdsModeratorOrAdmin)
                    .isPresent();
            return new Lookup(oid, eligible);
        });
    }

    private boolean fresh(StaffActorEntity actor) {
        return actor.getRolesSeenAt() != null && this.clock.millis() - actor.getRolesSeenAt() <= this.maxAge.toMillis();
    }

    private static boolean holdsModeratorOrAdmin(StaffActorEntity actor) {
        return actor.getRoles() != null && Arrays.asList(actor.getRoles().split(",")).stream()
                .anyMatch(role -> role.equals(Roles.MODERATOR) || role.equals(Roles.ADMIN));
    }

    private static Outcome.Refused refused(Reason reason, String message) {
        return new Outcome.Refused(reason, message);
    }

    /** Why a delegation was refused. {@link #NOT_DISCORD} is a malformed request; the others are refusals of authority. */
    public enum Reason {
        NOT_DISCORD,
        REQUIRED,
        NOT_ELIGIBLE,
        ROLE_STALE
    }

    /** The staff oid a Discord user is linked to, and whether that member may act now. */
    public record Lookup(UUID oid, boolean eligible) {
    }

    /** The result of {@link #attribute}. */
    public sealed interface Outcome {

        record Allowed(Attribution attribution) implements Outcome {
        }

        record Refused(Reason reason, String message) implements Outcome {
        }
    }
}
