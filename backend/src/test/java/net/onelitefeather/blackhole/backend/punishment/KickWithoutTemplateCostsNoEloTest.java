package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.kick.service.KickService;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a template step carries an ELO cost: a plain kick and a mass kick change no score. */
class KickWithoutTemplateCostsNoEloTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final KickService kicks = bed.kickService;

    @Test
    void aKickWithoutATemplateChangesNoScore() {
        bed.eloBed.givenScore(OWNER, "network", 900);

        KickDTO result = kicks.kick(new KickDTO.CreateRequest(OWNER, null, null, "AFK farming"), "key-1", ACTOR);

        assertInstanceOf(KickDTO.Accepted.class, result, "expected the kick to be accepted but got " + result);
        assertEquals(900, bed.eloBed.storedScore(OWNER, "network"), "a kick without a template costs nothing");
        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "and writes no ELO event");
    }

    @Test
    void aKickWithoutATemplateHasNoEscalationGroup() {
        kicks.kick(new KickDTO.CreateRequest(OWNER, KickScope.NETWORK, null, "AFK farming"), "key-2", ACTOR);

        PunishmentEntity row = bed.punishments.all().get(0);
        assertEquals(PunishmentKind.KICK, row.getKind());
        assertNull(row.getEscalationGroup(), "no template, no escalation group");
    }

    @Test
    void aMassKickChangesNoScoreAndWritesNoPunishment() {
        bed.eloBed.givenScore(OWNER, "network", 900);

        KickDTO result = kicks.kickMass(new KickDTO.MassCreateRequest(KickScope.SERVER, "Lobby-1", "maintenance"), "key-3", ACTOR);

        assertInstanceOf(KickDTO.Accepted.class, result, "expected the mass kick to be accepted but got " + result);
        assertEquals(900, bed.eloBed.storedScore(OWNER, "network"), "a mass kick costs nobody ELO");
        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "and writes no ELO event");
        assertTrue(bed.punishments.all().isEmpty(), "and no punishment");
    }
}
