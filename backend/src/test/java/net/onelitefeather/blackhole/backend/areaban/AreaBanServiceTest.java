package net.onelitefeather.blackhole.backend.areaban;

import net.onelitefeather.blackhole.backend.areaban.dto.AreaBanDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AreaBanServiceTest {

    private static final AreaBanService.Actor MODERATOR = new AreaBanService.Actor("moderator-oid", false);
    private static final AreaBanService.Actor ADMIN = new AreaBanService.Actor("admin-oid", true);

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryAreaBanStore store = new InMemoryAreaBanStore();
    private final AreaBanService service = new AreaBanService(this.store, new AreaBanCache(this.store),
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), this.clock);

    private static AreaBanDTO.CreateRequest asn(Long seconds) {
        return new AreaBanDTO.CreateRequest("ASN", "16276", "hosting wave", seconds);
    }

    private static AreaBanDTO.CreateRequest country(String code, Long seconds) {
        return new AreaBanDTO.CreateRequest("COUNTRY", code, "wave from one country", seconds);
    }

    private static ErrorKind kindOf(AreaBanDTO result) {
        return assertInstanceOf(AreaBanDTO.Error.class, result, "the request must be refused").kind();
    }

    @Test
    void aModeratorMayBanAnAsnForSevenDays() {
        AreaBanDTO result = service.create(asn(7L * 24 * 3600), MODERATOR);

        assertInstanceOf(AreaBanDTO.Response.class, result, "a moderator's seven day ASN ban must be created");
    }

    @Test
    void aModeratorMayNotCreateAPermanentAsnBan() {
        AreaBanDTO result = service.create(asn(null), MODERATOR);

        assertEquals(ErrorKind.FORBIDDEN_DURATION, kindOf(result), "a moderator must not create a permanent ban");
    }

    @Test
    void aModeratorMayNotBanForMoreThanSevenDays() {
        AreaBanDTO result = service.create(asn(8L * 24 * 3600), MODERATOR);

        assertEquals(ErrorKind.FORBIDDEN_DURATION, kindOf(result), "a moderator must not create a ban longer than seven days");
    }

    @Test
    void aModeratorMayNotBanACountry() {
        AreaBanDTO result = service.create(country("DE", 3600L), MODERATOR);

        assertEquals(ErrorKind.FORBIDDEN_KIND, kindOf(result), "only an admin may ban a country");
    }

    @Test
    void anAdminMayBanACountryPermanently() {
        AreaBanDTO result = service.create(country("DE", null), ADMIN);

        assertInstanceOf(AreaBanDTO.Response.class, result, "an admin may create a permanent country ban");
    }

    @Test
    void aSecondActiveBanOfTheSameAreaIsAConflict() {
        service.create(asn(3600L), ADMIN);

        AreaBanDTO second = service.create(asn(3600L), ADMIN);

        assertEquals(ErrorKind.CONFLICT, kindOf(second), "a second active ban of the same ASN must be refused");
    }

    @Test
    void aReasonOfZeroCharactersIsRefused() {
        AreaBanDTO result = service.create(new AreaBanDTO.CreateRequest("ASN", "16276", "   ", 3600L), ADMIN);

        assertEquals(ErrorKind.VALIDATION, kindOf(result), "a blank reason must be refused");
    }

    @Test
    void aReasonOf201CharactersIsRefused() {
        AreaBanDTO result = service.create(new AreaBanDTO.CreateRequest("ASN", "16276", "x".repeat(201), 3600L), ADMIN);

        assertEquals(ErrorKind.VALIDATION, kindOf(result), "a reason longer than 200 characters must be refused");
    }

    @Test
    void anExpiredUnliftedBanDoesNotBlockANewOne() {
        Clock later = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);
        AreaBanService first = new AreaBanService(this.store, new AreaBanCache(this.store),
                new DomainEventPublisher(new RecordingEventTransport(), later), later);
        first.create(asn(60L), ADMIN);
        Clock afterTheHour = Clock.fixed(Instant.parse("2026-10-29T13:00:00Z"), ZoneOffset.UTC);
        AreaBanService afterExpiry = new AreaBanService(this.store, new AreaBanCache(this.store),
                new DomainEventPublisher(new RecordingEventTransport(), afterTheHour), afterTheHour);

        AreaBanDTO result = afterExpiry.create(asn(3600L), ADMIN);

        assertInstanceOf(AreaBanDTO.Response.class, result, "an expired but unlifted ban must not block a new ban");
    }
}
