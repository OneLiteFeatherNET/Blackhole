package net.onelitefeather.blackhole.backend.ipban;

import net.onelitefeather.blackhole.backend.ipban.dto.IpBanDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpBanServiceTest {

    private static IpBanDTO.CreateRequest request(String address, Long expiresAt) {
        return new IpBanDTO.CreateRequest(address, "Bot wave", null, expiresAt, null);
    }

    @Test
    void aModeratorCreatesATemporaryBan() {
        IpBanFixture fixture = new IpBanFixture();

        IpBanDTO result = fixture.service.create(request("203.0.113.7", IpBanFixture.NOW + Duration.ofDays(7).toMillis()), IpBanFixture.moderator());

        IpBanDTO.Response created = assertInstanceOf(IpBanDTO.Response.class, result, "a temporary ban is created");
        assertEquals("203.0.113.7/32", created.range(), "a single address is stored with its full prefix");
        assertEquals("PANEL", created.source(), "the panel is the default source");
    }

    @Test
    void aModeratorCannotCreateAPermanentBan() {
        IpBanFixture fixture = new IpBanFixture();

        IpBanDTO result = fixture.service.create(request("203.0.113.7", null), IpBanFixture.moderator());

        IpBanDTO.Error error = assertInstanceOf(IpBanDTO.Error.class, result, "a moderator may not ban permanently");
        assertEquals("FORBIDDEN_DURATION", error.code(), "the refusal names the duration rule");
        assertEquals(ErrorKind.FORBIDDEN, error.kind(), "the refusal is a 403");
        assertTrue(fixture.store.rows.isEmpty(), "nothing is stored");
    }

    @Test
    void aModeratorCannotCreateABanLongerThanTheLimit() {
        IpBanFixture fixture = new IpBanFixture();

        IpBanDTO result = fixture.service.create(request("203.0.113.7", IpBanFixture.NOW + Duration.ofDays(31).toMillis()), IpBanFixture.moderator());

        assertEquals("FORBIDDEN_DURATION", assertInstanceOf(IpBanDTO.Error.class, result, "31 days is over the limit").code(),
                "a ban over moderator-max-duration is refused");
    }

    @Test
    void anAdministratorCreatesAPermanentBan() {
        IpBanFixture fixture = new IpBanFixture();

        IpBanDTO result = fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin());

        assertNull(assertInstanceOf(IpBanDTO.Response.class, result, "an administrator may ban permanently").expiresAt(),
                "a permanent ban has no expiry");
    }

    @Test
    void aSecondActiveBanOfTheSameRangeIsRefused() {
        IpBanFixture fixture = new IpBanFixture();
        fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin());

        IpBanDTO result = fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin());

        IpBanDTO.Error error = assertInstanceOf(IpBanDTO.Error.class, result, "the same range twice is refused");
        assertEquals("ALREADY_BANNED", error.code(), "the refusal names the existing ban");
        assertEquals(1, fixture.store.rows.size(), "the existing ban is unchanged and no second row exists");
    }

    @Test
    void aNarrowBanInsideAnActiveWideBanIsAcceptedWithAWarning() {
        IpBanFixture fixture = new IpBanFixture();
        fixture.service.create(request("203.0.113.0/24", null), IpBanFixture.admin());

        IpBanDTO result = fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin());

        IpBanDTO.Response created = assertInstanceOf(IpBanDTO.Response.class, result, "a narrower ban is accepted");
        assertEquals(1, created.warnings().size(), "the response warns about the wider ban");
        assertTrue(created.warnings().getFirst().contains("203.0.113.0/24"), "the warning names the wider range");
    }

    @Test
    void aRangeWiderThanSixteenIsRefusedWithTheRangeCode() {
        IpBanFixture fixture = new IpBanFixture();

        IpBanDTO result = fixture.service.create(request("203.0.0.0/15", null), IpBanFixture.admin());

        IpBanDTO.Error error = assertInstanceOf(IpBanDTO.Error.class, result, "a /15 is too wide");
        assertEquals("IPBAN_RANGE_TOO_WIDE", error.code(), "the refusal names the width rule");
        assertEquals(ErrorKind.IP_BAN_NOT_ALLOWED, error.kind(), "the refusal is a 422");
    }

    @Test
    void aLoopbackAddressIsRefusedAsProtected() {
        IpBanFixture fixture = new IpBanFixture();

        IpBanDTO result = fixture.service.create(request("127.0.0.1", null), IpBanFixture.admin());

        assertEquals("IPBAN_PROTECTED", assertInstanceOf(IpBanDTO.Error.class, result, "loopback is protected").code(),
                "the refusal names the protection rule");
    }

    @Test
    void aLiftNeedsAReason() {
        IpBanFixture fixture = new IpBanFixture();
        IpBanDTO.Response created = assertInstanceOf(IpBanDTO.Response.class,
                fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin()));

        IpBanDTO result = fixture.service.lift(created.id().toString(), new IpBanDTO.LiftRequest("   "), IpBanFixture.admin());

        assertEquals("IPBAN_LIFT_REASON_REQUIRED", assertInstanceOf(IpBanDTO.Error.class, result, "a blank reason is refused").code(),
                "the lift needs a reason");
        assertTrue(fixture.store.find(created.id()).orElseThrow().isActive(IpBanFixture.NOW), "the ban stays active");
    }

    @Test
    void aLiftRecordsTheActorAndTheReason() {
        IpBanFixture fixture = new IpBanFixture();
        IpBanDTO.Response created = assertInstanceOf(IpBanDTO.Response.class,
                fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin()));

        IpBanDTO lifted = fixture.service.lift(created.id().toString(), new IpBanDTO.LiftRequest("False positive"), IpBanFixture.admin());

        IpBanDTO.Response response = assertInstanceOf(IpBanDTO.Response.class, lifted, "the lift succeeds");
        assertEquals(IpBanFixture.ADMIN_OID, response.liftedByOid(), "the lifting actor is recorded");
        assertEquals("False positive", response.liftReason(), "the reason is recorded");
        assertFalse(fixture.store.find(created.id()).orElseThrow().isActive(IpBanFixture.NOW), "the ban is no longer active");
    }

    @Test
    void theProxyListOmitsLiftedAndExpiredBans() {
        IpBanFixture fixture = new IpBanFixture();
        fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin());
        fixture.service.create(request("198.51.100.9", IpBanFixture.NOW + 1000), IpBanFixture.admin());
        fixture.store.rows.values().stream().filter(row -> row.range().equals("198.51.100.9/32")).findFirst()
                .ifPresent(row -> fixture.store.rows.put(row.id(), new IpBanRecord(row.id(), row.network(), row.reason(),
                        row.templateKey(), row.source(), null, null, row.actorOid(), row.createdAt(), IpBanFixture.NOW - 1, null, null, null)));

        List<IpBanDTO.ActiveEntry> entries = fixture.service.active().bans();

        assertEquals(List.of("203.0.113.7/32"), entries.stream().map(IpBanDTO.ActiveEntry::range).toList(),
                "only the permanent ban is active; the expired one is absent");
    }

    @Test
    void theCreatedEventNamesTheBanButNeverTheAddress() {
        IpBanFixture fixture = new IpBanFixture();

        fixture.service.create(request("203.0.113.7", null), IpBanFixture.admin());

        assertTrue(fixture.eventTypes.contains(IpBanEvents.CREATED), "the created event is published");
        assertTrue(fixture.eventTypes.contains(IpBanEvents.CHANGED), "the change event is published");
        assertEquals(1, fixture.changes.size(), "the feeds are told once about the new ban");
        fixture.events.forEach(event -> assertFalse(event.payload().toString().contains("203.0.113.7"),
                "no published event carries the address: " + event.eventType()));
    }
}
