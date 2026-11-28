package net.onelitefeather.blackhole.backend.publicbans;

import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Erasure of an owner removes the listing and the internal UUID of every punishment of that owner. */
class PublicBanErasureTest {

    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentEntity listedPunishment() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50)).setPublicReason("Repeated spam in chat");
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(
                OWNER, "spam", null, null, null, null, null, null, PLAYER.toString()), ACTOR);
        PunishmentEntity punishment = bed.punishments.all().getFirst();
        assertNotNull(punishment.getPublicHash(), "precondition: the punishment is listed");
        return punishment;
    }

    @Test
    void erasingTheOwnerClearsTheListingAndTheInternalUuid() {
        PunishmentEntity punishment = listedPunishment();

        new PublicBanListing(new PublicBanHasher(null), bed.punishments.repository()).erase(OWNER);

        assertNull(punishment.getPublicHash(), "the entry leaves the list: its hash no longer resolves");
        assertNull(punishment.getPublicReason(), "the copied public reason is removed");
        assertNull(punishment.getPlayerUuid(), "the internal player UUID is removed");
    }

    @Test
    void erasingAnotherOwnerLeavesThePunishmentListed() {
        PunishmentEntity punishment = listedPunishment();

        new PublicBanListing(new PublicBanHasher(null), bed.punishments.repository()).erase("cd".repeat(64));

        assertNotNull(punishment.getPublicHash(), "only the erased owner's punishments are cleared");
        assertEquals(PLAYER, punishment.getPlayerUuid(), "the other owner's UUID is kept");
    }
}
