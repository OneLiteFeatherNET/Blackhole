package net.onelitefeather.blackhole.backend.discord.ticket;

import net.onelitefeather.blackhole.backend.discord.dto.StaffTicketDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.FakeTicketStore;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import net.onelitefeather.blackhole.backend.support.TestClock;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The staff lock of an appeal ticket: a lock is a STAFF freeze, an unlock lifts only a STAFF freeze, a leak freeze is not lifted. */
class StaffFreezeTest {

    private static final String CASE = "freeze-case";
    private static final UUID MODERATOR = UUID.nameUUIDFromBytes("moderator".getBytes());
    private static final Actor PANEL_MODERATOR = new Actor(MODERATOR, "Moderator", ActorKind.HUMAN, false);

    private final TestClock clock = new TestClock();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier).build();
    private final FakeTicketStore store = new FakeTicketStore();
    private final StaffTicketService service = new StaffTicketService(this.punishments.repository(), this.store,
            StaffDelegations.withoutLinks(this.clock), StaffDelegations.recorder(this.clock),
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), this.clock);

    private UUID givenTicket(String freezeSource, boolean frozen) {
        this.punishments.given(new PunishmentEntity(CASE, UUID.randomUUID(), "owner", "network", false, 1L, null, new HashMap<>()), CASE);
        UUID id = UUID.randomUUID();
        this.store.tickets.put(id, new DiscordTicket(id, 1, CASE, UUID.randomUUID(), UUID.randomUUID(), TicketState.OPEN, "123456789012345678",
                "223456789012345678", frozen, 0, 0, null, null, null, 1L, 1L, frozen ? freezeSource : null));
        return id;
    }

    private StaffTicketDTO freeze(boolean frozen) {
        return this.service.freeze(CASE, new StaffTicketDTO.FreezeRequest(frozen, null), PANEL_MODERATOR);
    }

    @Test
    void aLockSetsTheFreezeWithSourceStaff() {
        UUID ticket = givenTicket(null, false);

        StaffTicketDTO result = freeze(true);

        assertEquals(new StaffTicketDTO.Response(true, DiscordTicket.FREEZE_STAFF), result, "the lock is a STAFF freeze");
        assertEquals(DiscordTicket.FREEZE_STAFF, this.store.tickets.get(ticket).freezeSource(), "stored with source STAFF");
    }

    @Test
    void aRepeatedLockChangesNothingAndAnswersTheLockedState() {
        UUID ticket = givenTicket(DiscordTicket.FREEZE_STAFF, true);

        StaffTicketDTO result = freeze(true);

        assertEquals(new StaffTicketDTO.Response(true, DiscordTicket.FREEZE_STAFF), result, "the current state, not an error");
        assertEquals(DiscordTicket.FREEZE_STAFF, this.store.tickets.get(ticket).freezeSource(), "the source is unchanged");
    }

    @Test
    void anUnlockLiftsAStaffFreeze() {
        UUID ticket = givenTicket(DiscordTicket.FREEZE_STAFF, true);

        StaffTicketDTO result = freeze(false);

        assertEquals(new StaffTicketDTO.Response(false, null), result, "unlocked");
        assertNull(this.store.tickets.get(ticket).freezeSource(), "no source once thawed");
    }

    @Test
    void anUnlockOfALeakFreezeIsRefusedWithFreezeByLeak() {
        UUID ticket = givenTicket(DiscordTicket.FREEZE_LEAK, true);

        StaffTicketDTO result = freeze(false);

        StaffTicketDTO.Error error = assertInstanceOf(StaffTicketDTO.Error.class, result, "a leak freeze is not lifted by staff");
        assertEquals("FREEZE_BY_LEAK", error.code());
        assertEquals(ErrorKind.CONFLICT, error.kind());
        assertEquals(DiscordTicket.FREEZE_LEAK, this.store.tickets.get(ticket).freezeSource(), "the leak freeze stays");
    }

    @Test
    void aLockOnALeakFreezeKeepsTheLeakSource() {
        UUID ticket = givenTicket(DiscordTicket.FREEZE_LEAK, true);

        freeze(true);

        assertEquals(DiscordTicket.FREEZE_LEAK, this.store.tickets.get(ticket).freezeSource(), "a lock never weakens a leak freeze");
    }
}
