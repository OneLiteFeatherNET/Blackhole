package net.onelitefeather.blackhole.backend.areaban;

import net.onelitefeather.blackhole.backend.areaban.dto.AreaBanDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaBanLiftTest {

    private static final AreaBanService.Actor ADMIN = new AreaBanService.Actor("admin-oid", true);
    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    private final InMemoryAreaBanStore store = new InMemoryAreaBanStore();

    private AreaBanService serviceAt(Instant now) {
        return new AreaBanService(this.store, Clock.fixed(now, ZoneOffset.UTC));
    }

    private AreaBanDTO.Response createdBan(AreaBanService service) {
        return assertInstanceOf(AreaBanDTO.Response.class,
                service.create(new AreaBanDTO.CreateRequest("ASN", "16276", "hosting wave", 3600L), ADMIN),
                "the fixture ban must be created");
    }

    @Test
    void liftingNeedsAReason() {
        AreaBanService service = serviceAt(START);
        AreaBanDTO.Response ban = createdBan(service);

        AreaBanDTO result = service.lift(ban.id(), new AreaBanDTO.LiftRequest("  "), ADMIN);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(AreaBanDTO.Error.class, result, "a blank reason must be refused").kind(),
                "lifting with no reason must be a validation error");
    }

    @Test
    void aLiftedBanNoLongerApplies() {
        AreaBanService service = serviceAt(START);
        AreaBanDTO.Response ban = createdBan(service);

        AreaBanDTO lifted = service.lift(ban.id(), new AreaBanDTO.LiftRequest("false positive"), ADMIN);

        assertFalse(assertInstanceOf(AreaBanDTO.Response.class, lifted, "a lift with a reason must succeed").active(),
                "a lifted ban must no longer be active");
    }

    @Test
    void anExpiredBanIsNoLongerActive() {
        AreaBanService service = serviceAt(START);
        AreaBanDTO.Response ban = createdBan(service);
        AreaBanService afterExpiry = serviceAt(START.plusSeconds(3601));

        AreaBanDTO result = afterExpiry.lift(ban.id(), new AreaBanDTO.LiftRequest("expired wave"), ADMIN);

        AreaBanDTO.Response lifted = assertInstanceOf(AreaBanDTO.Response.class, result,
                "an expired ban can still be recorded as lifted");
        assertFalse(lifted.active(), "an expired ban must not be active after the lift");
        assertTrue(lifted.liftedAt() != null, "the lift must record when the ban was lifted");
    }

    @Test
    void liftingAnUnknownIdIsNotFound() {
        AreaBanDTO result = serviceAt(START).lift(UUID.randomUUID().toString(), new AreaBanDTO.LiftRequest("reason"), ADMIN);

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(AreaBanDTO.Error.class, result, "an unknown id must be refused").kind(),
                "lifting an unknown ban must be not found");
    }
}
