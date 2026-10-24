package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionSelfCheckTest {

    @Test
    void a_correct_setup_reports_no_problem() {
        Harness h = new Harness();

        List<String> problems = h.selfCheck.run();

        assertEquals(List.of(), problems, "nothing missing");
        assertEquals(List.of(), h.health.problems(), "health is clean");
    }

    @Test
    void a_missing_permission_is_named_by_category_and_permission_and_published_on_health() {
        Harness h = new Harness();
        h.guild.missing.put(Harness.TICKET_CATEGORY, Set.of(Permission.MANAGE_CHANNEL));
        h.guild.missing.put(Harness.ARCHIVE_CATEGORY, Set.of(Permission.MANAGE_PERMISSIONS));

        h.selfCheck.run();

        assertEquals(List.of("ticket category: missing MANAGE_CHANNEL", "archive category: missing MANAGE_PERMISSIONS"), h.health.problems(),
                "problems name the category and the permission only");
    }

    @Test
    void the_staff_role_seeing_a_ticket_category_is_a_warning() {
        Harness h = new Harness();
        h.guild.categoriesStaffRoleCanView.add(Harness.TICKET_CATEGORY);

        h.selfCheck.run();

        assertEquals(List.of(), h.health.problems(), "not a hard problem");
        assertTrue(h.health.warnings().getFirst().startsWith("ticket category: the staff role can view it"), h.health.warnings().toString());
    }

    @Test
    void the_check_runs_when_discord_reports_ready_and_on_a_timer() {
        Harness h = new Harness();
        h.guild.missing.put(Harness.TICKET_CATEGORY, Set.of(Permission.VIEW));

        h.ready();

        assertEquals(1, h.health.problems().size(), "found at start");

        h.guild.missing.clear();
        h.scheduler.advance(java.time.Duration.ofHours(2));

        assertEquals(0, h.health.problems().size(), "cleared once fixed");
    }

    @Test
    void a_missing_permission_to_create_the_channel_keeps_the_ticket_pending_and_is_counted() {
        Harness h = new Harness();
        var ticket = h.newTicket(1);
        h.opens(ticket);
        h.guild.failOn.put("createChannel", new net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException("missing permissions", true, null));

        h.appeals.open(net.onelitefeather.blackhole.discord.command.Lang.EN, Long.toString(Harness.PLAYER), "Player", "K7M2-9QXA", "x");
        h.tick();

        assertEquals(net.onelitefeather.blackhole.discord.backend.Tickets.State.PENDING_CHANNEL, ticket.state, "no channel, no OPEN");
        assertTrue(h.metrics.value(net.onelitefeather.blackhole.discord.metrics.Metrics.Counter.DISCORD_ERRORS) >= 2, "each failure counted");
    }
}
