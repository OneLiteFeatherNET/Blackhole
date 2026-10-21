package net.onelitefeather.blackhole.backend.elo.service;

import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvidenceEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvidenceRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEloScoring;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToxicityServiceTest {

    private static final String OWNER = "cd".repeat(64);

    private final RecordingEloScoring scoring = new RecordingEloScoring();
    private final InMemoryRepository<PunishmentEvidenceRepository, PunishmentEvidenceEntity, UUID> evidence =
            InMemoryRepository.<PunishmentEvidenceRepository, PunishmentEvidenceEntity, UUID>of(
                    PunishmentEvidenceRepository.class, entity -> UUID.randomUUID()).build();
    private final TestClock clock = new TestClock();

    private ChatToxicityService service(String section, double scorerResult) {
        return new ChatToxicityService(message -> scorerResult, scoring, evidence.repository(), clock, section, 0.3, -50, 60);
    }

    @Test
    void aToxicMessageIsScoredInTheConfiguredChatSection() {
        service("chat", 1.0).evaluate(OWNER, "you idiot");

        assertEquals(1, scoring.deltas().size(), "one delta");
        assertEquals("chat", scoring.deltas().getFirst().section());
        assertEquals(-50, scoring.deltas().getFirst().delta(), "flag delta scaled by the score");
        assertEquals(EloReasonCode.TOXICITY_FLAG, scoring.deltas().getFirst().reason());
    }

    @Test
    void theChatSectionIsConfigurable() {
        service("social", 1.0).evaluate(OWNER, "you idiot");

        assertEquals("social", scoring.deltas().getFirst().section());
    }

    @Test
    void theDeltaIsScaledByTheToxicityScore() {
        service("chat", 0.5).evaluate(OWNER, "meh");

        assertEquals(-25, scoring.deltas().getFirst().delta());
    }

    @Test
    void aCleanMessageIsNotFlaggedAndNothingIsScored() {
        EloDTO.ChatSignalResponse result = service("chat", 0.1).evaluate(OWNER, "hello");

        assertFalse(result.flagged());
        assertTrue(scoring.deltas().isEmpty(), "no delta for a clean message");
        assertTrue(evidence.all().isEmpty(), "no evidence for a clean message");
    }

    @Test
    void aFlaggedMessageStoresOnlyAHashAsEvidence() {
        service("chat", 1.0).evaluate(OWNER, "you idiot");

        assertEquals(1, evidence.all().size(), "one evidence record");
        assertFalse(evidence.all().getFirst().getCapturedContentHash().contains("idiot"), "the raw message is never stored");
    }
}
