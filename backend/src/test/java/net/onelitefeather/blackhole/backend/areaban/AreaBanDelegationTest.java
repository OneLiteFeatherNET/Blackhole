package net.onelitefeather.blackhole.backend.areaban;

import net.onelitefeather.blackhole.backend.areaban.dto.AreaBanDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The area ban limits for a delegated write: they follow the staff member's role, never the proxy's. */
class AreaBanDelegationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID MODERATOR = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final UUID MINECRAFT_MODERATOR = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final UUID MINECRAFT_ADMIN = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
    private static final UUID PROXY = UUID.fromString("00000000-0000-4000-8000-0000000000c3");

    private final InMemoryAreaBanStore store = new InMemoryAreaBanStore();
    private final StaffDelegations.Linked linked = StaffDelegations.linked(CLOCK);

    private AreaBanService service() {
        return new AreaBanService(this.store, new AreaBanCache(this.store),
                new DomainEventPublisher(new RecordingEventTransport(), CLOCK), CLOCK, this.linked.delegation(),
                StaffDelegations.recorder(CLOCK));
    }

    private static Actor proxy() {
        return new Actor(PROXY, "proxy", ActorKind.SERVICE, true, false, false, false);
    }

    private static AreaBanDTO.CreateRequest permanentAsnFor(UUID onBehalfOf) {
        return new AreaBanDTO.CreateRequest("ASN", "16276", "hosting wave", null, onBehalfOf);
    }

    @Test
    void aPermanentAsnBanIsRefusedWhenTheProxyActsForAModerator() {
        this.linked.givenInGameStaff(MODERATOR, MINECRAFT_MODERATOR);
        this.linked.givenSnapshot(MODERATOR, "MODERATOR,VIEWER", CLOCK.millis());

        AreaBanDTO result = service().create(permanentAsnFor(MINECRAFT_MODERATOR), proxy());

        assertEquals(ErrorKind.FORBIDDEN_DURATION, assertInstanceOf(AreaBanDTO.Error.class, result, "the moderator limit applies").kind(),
                "the limit follows the delegated moderator, not the proxy");
    }

    @Test
    void aCountryBanIsAllowedWhenTheProxyActsForAnAdmin() {
        this.linked.givenInGameStaff(ADMIN, MINECRAFT_ADMIN);
        this.linked.givenSnapshot(ADMIN, "ADMIN,MODERATOR,VIEWER", CLOCK.millis());

        AreaBanDTO result = service().create(new AreaBanDTO.CreateRequest("COUNTRY", "DE", "wave", null, MINECRAFT_ADMIN), proxy());

        assertInstanceOf(AreaBanDTO.Response.class, result, "an admin's permanent country ban is allowed through the proxy");
    }

    @Test
    void aBanTheProxyCreatesIsStoredUnderTheInGameAccountAsPunishmentsAre() {
        this.linked.givenInGameStaff(ADMIN, MINECRAFT_ADMIN);
        this.linked.givenSnapshot(ADMIN, "ADMIN,MODERATOR,VIEWER", CLOCK.millis());

        service().create(new AreaBanDTO.CreateRequest("COUNTRY", "DE", "wave", null, MINECRAFT_ADMIN), proxy());

        assertEquals(MINECRAFT_ADMIN.toString(), this.store.listAll().getFirst().actorOid(),
                "the created_by column holds the in-game account, the same identity kind the punishment path stores");
    }

    @Test
    void aProxyWithoutAStaffMemberIsRefused() {
        AreaBanDTO result = service().create(permanentAsnFor(null), proxy());

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(AreaBanDTO.Error.class, result, "the proxy must name the staff member").kind(),
                "a machine without onBehalfOf is refused as the bot is");
    }
}
