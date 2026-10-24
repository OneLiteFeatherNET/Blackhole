package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.testing.FakeGuild;
import net.onelitefeather.blackhole.discord.testing.FakeTicketBackend;
import net.onelitefeather.blackhole.discord.testing.Harness;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffAccessReconcilerTest {

    private static final long ADMIN_NOT_LINKED = 123456789012346003L;

    private static Set<Long> access(Harness h, FakeTicketBackend.Rec ticket) {
        return h.guild.memberAccessForTest(h.channelOf(ticket));
    }

    private static void link(Harness h, long user) {
        h.backend.staffLinks.add(Long.toString(user));
    }

    @Test
    void a_staff_member_with_the_role_and_a_live_link_gets_access() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);

        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER, Harness.STAFF_A), access(h, ticket), "player and linked staff");
        var allow = h.guild.channel(h.channelOf(ticket)).members.get(Harness.STAFF_A).allow();
        assertEquals(Set.of(Permission.VIEW, Permission.SEND, Permission.READ_HISTORY), allow, "view, send and history");
    }

    @Test
    void a_new_ticket_is_visible_to_the_linked_staff_from_the_start() {
        Harness h = new Harness();
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);

        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);

        assertTrue(access(h, ticket).contains(Harness.STAFF_A), "linked staff see the new channel");
    }

    @Test
    void a_member_with_the_role_but_no_link_gets_nothing() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);

        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER), access(h, ticket), "the role alone grants nothing");
    }

    @Test
    void a_linked_member_without_the_role_gets_nothing() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        link(h, Harness.STAFF_B);

        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER), access(h, ticket), "a link alone grants nothing");
    }

    @Test
    void an_unlink_removes_access_at_the_next_reconcile() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);
        h.staff.reconcileAll();

        h.backend.staffLinks.clear();
        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER), access(h, ticket), "access removed");
    }

    @Test
    void a_lost_role_removes_access_at_the_next_reconcile() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);
        h.staff.reconcileAll();

        h.guild.roleMembers.get(Harness.STAFF_ROLE).remove(Harness.STAFF_A);
        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER), access(h, ticket), "access removed");
    }

    @Test
    void a_discord_administrator_who_is_not_linked_is_given_no_overwrite() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(ADMIN_NOT_LINKED);

        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER), access(h, ticket), "Discord shows an administrator every channel, but the bot adds nothing");
    }

    @Test
    void the_player_is_never_touched_even_if_they_hold_the_role_and_a_link() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.PLAYER);
        link(h, Harness.PLAYER);
        var before = h.guild.channel(h.channelOf(ticket)).members.get(Harness.PLAYER);

        h.staff.reconcileAll();

        assertEquals(before, h.guild.channel(h.channelOf(ticket)).members.get(Harness.PLAYER), "the player's own overwrite is unchanged");
    }

    @Test
    void the_reconcile_adds_only_member_overwrites_never_a_role_overwrite() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);

        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER, Harness.STAFF_A, FakeGuild.SELF), h.guild.channel(h.channelOf(ticket)).members.keySet(), "members only");
    }

    @Test
    void a_failed_link_list_changes_nothing() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);
        h.staff.reconcileAll();

        h.backend.failing.add("staffLinks");
        h.staff.reconcileAll();

        assertTrue(access(h, ticket).contains(Harness.STAFF_A), "access is kept when the backend cannot say");
    }

    @Test
    void a_failed_role_lookup_leaves_that_member_as_they_are() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);
        link(h, Harness.STAFF_B);
        h.staffRoleFor(Harness.STAFF_B);
        h.staff.reconcileAll();

        h.guild.failOn.put("hasRole", new GatewayException("rate limited", false, null));
        h.guild.roleMembers.get(Harness.STAFF_ROLE).remove(Harness.STAFF_A);
        h.staff.reconcileAll();

        assertEquals(Set.of(Harness.PLAYER, Harness.STAFF_A, Harness.STAFF_B), access(h, ticket), "nobody is removed on a lookup that failed");
    }

    @Test
    void a_removed_staff_link_event_reconciles_at_once() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);
        h.staff.reconcileAll();
        h.backend.staffLinks.clear();

        h.caseEvents.onMessage("{\"eventId\":\"e\",\"eventType\":\"staff-link.removed\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8));
        h.drain();

        assertEquals(Set.of(Harness.PLAYER), access(h, ticket), "removed without waiting for the interval");
    }

    @Test
    void the_interval_reconciles_without_any_event() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);

        h.scheduler.advance(java.time.Duration.ofMinutes(6));

        assertTrue(access(h, ticket).contains(Harness.STAFF_A), "added by the five minute timer");
        assertEquals(State.OPEN, ticket.state);
    }

    @Test
    void a_backend_outage_leaves_access_as_it_is() {
        Harness h = new Harness();
        FakeTicketBackend.Rec ticket = h.openTicketWithChannel(1);
        h.staffRoleFor(Harness.STAFF_A);
        link(h, Harness.STAFF_A);
        h.staff.reconcileAll();
        h.backend.down = 0;
        h.backend.staffLinks.clear();

        h.staff.reconcileAll();

        assertTrue(access(h, ticket).contains(Harness.STAFF_A), "nothing is removed because the backend cannot be asked");
    }
}
