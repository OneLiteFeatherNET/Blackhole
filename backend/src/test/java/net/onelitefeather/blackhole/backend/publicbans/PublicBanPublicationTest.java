package net.onelitefeather.blackhole.backend.publicbans;

import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which applied punishments reach the public ban list, and what the listing copies from them. */
class PublicBanPublicationTest {

    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final PublicBanHasher hasher = new PublicBanHasher(KEY);

    private PunishmentEntity applyWithReason(String reason, String playerUuid) {
        PunishmentTemplateEntity template = bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        template.setPublicReason(reason);
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(
                OWNER, "spam", null, null, null, null, null, null, playerUuid), ACTOR);
        assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the punishment is applied");
        return bed.punishments.all().getFirst();
    }

    @Test
    void aTemplateWithAPublicReasonListsItsPunishmentWithTheCopiedReasonAndKind() {
        PunishmentEntity punishment = applyWithReason("Repeated spam in chat", PLAYER.toString());

        assertEquals("Repeated spam in chat", punishment.getPublicReason(), "the public reason is copied");
        assertEquals("MUTE", punishment.getPublicKind(), "the chat section is a mute, so the public kind is MUTE");
        assertEquals(hasher.entryHash(punishment.getIdentifier()), punishment.getPublicHash(), "the entry hash is stored");
    }

    @Test
    void theSubmittedUuidIsStoredNormalisedAsThePunishmentsInternalUuid() {
        PunishmentEntity punishment = applyWithReason("Repeated spam in chat", PLAYER.toString().toUpperCase());

        assertEquals(PLAYER, punishment.getPlayerUuid(), "the UUID is stored in its normalised form");
    }

    @Test
    void aPunishmentWithoutAUuidIsStillListedWithoutAPlayerUuid() {
        PunishmentEntity punishment = applyWithReason("Repeated spam in chat", null);

        assertNull(punishment.getPlayerUuid(), "no UUID is stored when the caller did not know it");
        assertEquals(hasher.entryHash(punishment.getIdentifier()), punishment.getPublicHash(),
                "a missing UUID does not prevent listing");
    }

    @Test
    void aTemplateWithoutAPublicReasonListsNothing() {
        PunishmentEntity punishment = applyWithReason(null, PLAYER.toString());

        assertNull(punishment.getPublicHash(), "a template without a public reason is never listed");
        assertNull(punishment.getPublicReason(), "no public reason is copied");
    }

    @Test
    void aBlankPublicReasonListsNothing() {
        PunishmentEntity punishment = applyWithReason("   ", PLAYER.toString());

        assertNull(punishment.getPublicHash(), "blank counts as absent");
    }

    @Test
    void aLaterTemplateEditDoesNotChangeTheCopiedReason() {
        PunishmentEntity punishment = applyWithReason("Repeated spam in chat", PLAYER.toString());
        bed.templates.all().getFirst().setPublicReason("A different text");

        assertEquals("Repeated spam in chat", punishment.getPublicReason(), "the listed text is a snapshot of the apply");
    }
}
