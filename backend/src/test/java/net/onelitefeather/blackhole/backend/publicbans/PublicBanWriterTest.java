package net.onelitefeather.blackhole.backend.publicbans;

import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The listing columns across apply and revoke: the day, the expiry and the revoke instant. */
class PublicBanWriterTest {

    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final long HOUR = 3_600_000L;

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentEntity appliedListing() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50)).setPublicReason("Repeated spam in chat");
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(
                OWNER, "spam", null, null, null, null, null, null, PLAYER.toString()), ACTOR);
        return bed.punishments.all().getFirst();
    }

    @Test
    void applyStoresTheUtcDayAndTheExpiryInstant() {
        PunishmentEntity punishment = appliedListing();

        long now = bed.eloBed.clock.millis();
        assertEquals(now / 86_400_000L, punishment.getPublicIssuedDay(), "the issued day is the UTC day of the apply");
        assertEquals(now + HOUR, punishment.getPublicExpiresAt(), "the expiry is the apply instant plus the step duration");
        assertNull(punishment.getPublicRevokedAt(), "a new entry is not revoked");
    }

    @Test
    void revokingAListedPunishmentRecordsTheRevokeInstant() {
        PunishmentEntity punishment = appliedListing();

        bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        assertNotNull(punishment.getPublicRevokedAt(), "the revoke instant is stored for the status REVOKED");
        assertEquals(bed.eloBed.clock.millis(), punishment.getPublicRevokedAt(), "the revoke instant is the clock's instant");
    }

    @Test
    void revokingAnUnlistedPunishmentLeavesTheListingEmpty() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
        PunishmentEntity punishment = bed.punishments.all().getFirst();

        bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        assertNull(punishment.getPublicRevokedAt(), "an unlisted punishment gets no listing columns");
        assertNull(punishment.getPublicHash(), "and stays unlisted");
    }

    @Test
    void theEntryHashIsKeyedByTheIdentifierOnly() {
        PunishmentEntity punishment = appliedListing();

        assertEquals(new PublicBanHasher(KEY).entryHash(punishment.getIdentifier()), punishment.getPublicHash(),
                "the stored hash is the entry hash of the identifier under the configured key");
    }
}
