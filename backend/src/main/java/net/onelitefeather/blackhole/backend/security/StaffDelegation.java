package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a machine caller may act for a staff member named by {@code onBehalfOf}. The Discord bot names the
 * member by its oid and is checked against a live staff {@code discord_link}; the proxy names the member by the same oid and
 * is checked against a live staff link ({@code staff_link}), which the proxy first resolves from the in-game Minecraft
 * account. In both cases the member's role snapshot ({@code staff_actor.roles}, written on every HUMAN request) must be fresh
 * and hold MODERATOR or ADMIN. A removed role therefore stops working once the snapshot is older than the window.
 *
 * <p>{@link #attribute} serves the four Discord delegation routes (the appeal review, the case update, the ticket close and
 * the ticket freeze). {@link #attributeGuard} serves the guard level and the area ban routes, which the proxy and the bot may
 * call as well.</p>
 */
@Singleton
public class StaffDelegation {

    private final DiscordLinkRepository links;
    private final StaffActorRepository actors;
    private final StaffLinkRepository staffLinks;
    private final Clock clock;
    private final Duration maxAge;

    public StaffDelegation(DiscordLinkRepository links, StaffActorRepository actors, StaffLinkRepository staffLinks, Clock clock,
                           @Value("${blackhole.discord.staff-role-max-age:PT24H}") Duration maxAge) {
        this.links = links;
        this.actors = actors;
        this.staffLinks = staffLinks;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /**
     * The attribution of a Discord delegation-capable write.
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
            return self(caller);
        }
        if (onBehalfOf == null) {
            return refused(Reason.REQUIRED, "the Discord bot must name the staff member with onBehalfOf");
        }
        Optional<DiscordLinkEntity> link = this.links.findByKindAndSubject(DiscordLinkKind.STAFF, onBehalfOf.toString());
        if (link.isEmpty()) {
            return refused(Reason.NOT_ELIGIBLE, "STAFF_NOT_ELIGIBLE: the staff member has no live Discord link");
        }
        return snapshot(onBehalfOf, caller.oid());
    }

    /**
     * The attribution of a guard or area ban write. A human caller acts for itself and may not name anyone. The Discord bot
     * acts for a Discord-linked staff member, and the proxy for a staff member with a live staff link. Both must name the member.
     *
     * @param caller     the verified caller
     * @param onBehalfOf the staff member's oid the bot or the proxy names, or {@code null}
     * @return {@link Outcome.Allowed} with the staff member's attribution, role and display name, or {@link Outcome.Refused}
     */
    public Outcome attributeGuard(Actor caller, @Nullable UUID onBehalfOf) {
        if (caller.discord()) {
            return attribute(caller, onBehalfOf);
        }
        if (!caller.proxy()) {
            if (onBehalfOf != null) {
                return refused(Reason.NOT_DISCORD, "onBehalfOf is only accepted from the Discord bot or the proxy on this route");
            }
            return self(caller);
        }
        if (onBehalfOf == null) {
            return refused(Reason.REQUIRED, "the proxy must name the staff member with onBehalfOf");
        }
        if (this.staffLinks.findById(onBehalfOf).isEmpty()) {
            return refused(Reason.NOT_ELIGIBLE, "STAFF_NOT_ELIGIBLE: the staff member has no live staff link");
        }
        return snapshot(onBehalfOf, caller.oid());
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

    private Outcome self(Actor caller) {
        return new Outcome.Allowed(caller.attribution(), caller.admin(), caller.displayName());
    }

    /** The delegated member's fresh role snapshot must hold MODERATOR or ADMIN; the attribution names the member and the machine. */
    private Outcome snapshot(UUID staff, UUID recordedBy) {
        Optional<StaffActorEntity> actor = this.actors.findById(staff);
        if (actor.isEmpty()) {
            return refused(Reason.NOT_ELIGIBLE, "STAFF_NOT_ELIGIBLE: the staff member has no role snapshot");
        }
        if (!fresh(actor.get())) {
            return refused(Reason.ROLE_STALE, "STAFF_ROLE_STALE: sign in to the team panel once, then try again");
        }
        if (!holdsModeratorOrAdmin(actor.get())) {
            return refused(Reason.NOT_ELIGIBLE, "STAFF_NOT_ELIGIBLE: the staff member is not a moderator or an admin");
        }
        return new Outcome.Allowed(new Attribution(staff, recordedBy), holdsRole(actor.get(), Roles.ADMIN), actor.get().getDisplayName());
    }

    private boolean fresh(StaffActorEntity actor) {
        return actor.getRolesSeenAt() != null && this.clock.millis() - actor.getRolesSeenAt() <= this.maxAge.toMillis();
    }

    private static boolean holdsModeratorOrAdmin(StaffActorEntity actor) {
        return holdsRole(actor, Roles.MODERATOR) || holdsRole(actor, Roles.ADMIN);
    }

    private static boolean holdsRole(StaffActorEntity actor, String role) {
        return actor.getRoles() != null && Arrays.asList(actor.getRoles().split(",")).contains(role);
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

    /** The result of {@link #attribute} and {@link #attributeGuard}. */
    public sealed interface Outcome {

        /**
         * @param attribution the staff member the write is for, and the verified machine or person that made it
         * @param admin       whether the staff member holds the ADMIN role (for the caller itself, its own admin flag)
         * @param displayName the staff member's name, or {@code null} when none is known
         */
        record Allowed(Attribution attribution, boolean admin, @Nullable String displayName) implements Outcome {
        }

        record Refused(Reason reason, String message) implements Outcome {

            /** The response kind of a refusal: a malformed request is 400, a refusal of authority is 403. */
            public ErrorKind errorKind() {
                return this.reason == Reason.NOT_DISCORD ? ErrorKind.VALIDATION : ErrorKind.FORBIDDEN;
            }
        }
    }
}
