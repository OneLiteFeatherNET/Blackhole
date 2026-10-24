package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MemberAccess;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.metrics.Metrics.Counter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides who of the team sees a ticket channel: exactly the members who hold the staff role <b>and</b> have a live staff
 * link. The staff role itself grants nothing (the categories give it no View), so a role alone, or a link that outlived the
 * role, opens no channel. Access is given as per-member overwrites, never as a role overwrite. The reconcile runs at ticket
 * creation, on an interval and when a link is removed; an unlink or a lost role takes the access away at the next run.
 *
 * <p>If a role lookup fails, that member is left as they are (neither added nor removed); a failed link list changes
 * nothing at all. Discord administrators and the owner see every channel regardless; that is Discord's rule.</p>
 */
public final class StaffAccessReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StaffAccessReconciler.class);
    static final Set<Permission> STAFF_ALLOW = Set.of(Permission.VIEW, Permission.SEND, Permission.READ_HISTORY);

    private final BackendGateway backend;
    private final DiscordGateway discord;
    private final TicketRegistry registry;
    private final long staffRoleId;
    private final Metrics metrics;

    public StaffAccessReconciler(@NotNull BackendGateway backend, @NotNull DiscordGateway discord, @NotNull TicketRegistry registry,
                                 long staffRoleId, @NotNull Metrics metrics) {
        this.backend = backend;
        this.discord = discord;
        this.registry = registry;
        this.staffRoleId = staffRoleId;
        this.metrics = metrics;
    }

    /** Reconciles every known ticket channel. */
    public void reconcileAll() {
        reconcile(this.registry.all());
    }

    /** Reconciles the given tickets (those with a channel). */
    public void reconcile(@NotNull List<Ticket> tickets) {
        Result<List<String>> links = this.backend.staffLinks();
        if (links instanceof Result.Failed<List<String>> failed) {
            this.metrics.increment(Counter.BACKEND_UNAVAILABLE);
            LOGGER.warn("Reading the staff links failed with status {}; channel access is left as it is", failed.status());
            return;
        }
        Set<Long> linked = new LinkedHashSet<>();
        for (String id : ((Result.Ok<List<String>>) links).value()) {
            try {
                linked.add(Long.parseUnsignedLong(id));
            } catch (NumberFormatException ignored) {
                // A malformed id cannot be a Discord member.
            }
        }
        Map<Long, Boolean> roleCache = new HashMap<>();
        Set<Long> unknown = new HashSet<>();
        Set<Long> desired = new LinkedHashSet<>();
        for (long user : linked) {
            try {
                boolean holds = this.discord.hasRole(user, this.staffRoleId);
                roleCache.put(user, holds);
                if (holds) {
                    desired.add(user);
                }
            } catch (GatewayException e) {
                this.metrics.increment(Counter.DISCORD_ERRORS);
                unknown.add(user);
            }
        }
        for (Ticket ticket : tickets) {
            Long channel = ticket.channel();
            if (channel == null) {
                continue;
            }
            try {
                apply(channel, ticket.player(), desired, unknown);
            } catch (GatewayException e) {
                this.metrics.increment(Counter.DISCORD_ERRORS);
                LOGGER.warn("Reconciling channel access failed (permanent={})", e.permanent());
            }
        }
    }

    private void apply(long channel, Long player, Set<Long> desired, Set<Long> unknown) throws GatewayException {
        Set<Long> current = this.discord.memberAccess(channel);
        for (long user : desired) {
            if (!current.contains(user) && (player == null || player != user)) {
                this.discord.setMemberAccess(channel, new MemberAccess(user, STAFF_ALLOW, Set.of()));
            }
        }
        for (long user : current) {
            boolean isPlayer = player != null && player == user;
            if (!isPlayer && !desired.contains(user) && !unknown.contains(user)) {
                this.discord.removeMemberAccess(channel, user);
            }
        }
    }
}
