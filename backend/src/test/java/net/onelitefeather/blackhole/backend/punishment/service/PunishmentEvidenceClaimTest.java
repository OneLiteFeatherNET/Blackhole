package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEvidenceGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentEvidenceClaimTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @BeforeEach
    void givenTemplate() {
        bed.givenTemplate("spam", "network", null, step("P1D", 100));
    }

    private PunishmentDTO apply(String correlationId) {
        return bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, STAFF, correlationId));
    }

    @Test
    void theNewPunishmentClaimsTheEvidenceSentUnderTheCorrelationId() {
        PunishmentDTO.ApplyResponse applied = assertInstanceOf(PunishmentDTO.ApplyResponse.class, apply("c1"));

        assertEquals(1, bed.evidence.claims().size());
        RecordingEvidenceGateway.Claim claim = bed.evidence.claims().getFirst();
        assertEquals(OWNER, claim.owner());
        assertEquals("c1", claim.correlationId());
        assertEquals(applied.punishment().identifier(), claim.punishmentIdentifier());
    }

    @Test
    void withoutACorrelationIdNothingIsClaimed() {
        assertInstanceOf(PunishmentDTO.ApplyResponse.class, apply(null));

        assertTrue(bed.evidence.claims().isEmpty());
    }

    @Test
    void aBlankCorrelationIdClaimsNothing() {
        assertInstanceOf(PunishmentDTO.ApplyResponse.class, apply("  "));

        assertTrue(bed.evidence.claims().isEmpty());
    }

    @Test
    void aFailingClaimDoesNotFailThePunishment() {
        bed.evidence.failClaims(new IllegalStateException("database down"));

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, apply("c1"));
    }

    @Test
    void aRejectedApplyClaimsNothing() {
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest("not-a-hash", "spam", null, STAFF, "c1"));

        assertInstanceOf(PunishmentDTO.Error.class, result);
        assertTrue(bed.evidence.claims().isEmpty());
    }
}
