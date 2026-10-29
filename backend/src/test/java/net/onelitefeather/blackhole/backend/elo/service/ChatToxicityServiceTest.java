package net.onelitefeather.blackhole.backend.elo.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.ToxicityScorer;
import net.onelitefeather.blackhole.backend.elo.ToxicityVerdict;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import net.onelitefeather.blackhole.backend.elo.toxicity.ToxicityCategory;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvidenceEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvidenceRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEloScoring;
import net.onelitefeather.blackhole.backend.support.SnapshotLogAppender;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToxicityServiceTest {

    private static final String OWNER = "cd".repeat(64);
    private static final double THRESHOLD = 0.5;

    private final RecordingEloScoring scoring = new RecordingEloScoring();
    private final InMemoryRepository<PunishmentEvidenceRepository, PunishmentEvidenceEntity, UUID> evidence =
            InMemoryRepository.<PunishmentEvidenceRepository, PunishmentEvidenceEntity, UUID>of(
                    PunishmentEvidenceRepository.class, entity -> UUID.randomUUID()).build();
    private final TestClock clock = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ChatToxicityService service(String mode, ToxicityScorer scorer) {
        return service("chat", mode, THRESHOLD, scorer);
    }

    private ChatToxicityService service(String section, String mode, double threshold, ToxicityScorer scorer) {
        return new ChatToxicityService(scorer, scoring, evidence.repository(), clock, meters, section, mode, threshold, -50, 60);
    }

    private static ToxicityScorer fixed(double score, ToxicityCategory category) {
        return message -> new ToxicityVerdict(score, Optional.of(category));
    }

    @Test
    void aFlaggedInsultIsScoredInTheConfiguredChatSection() {
        service("chat", "ENFORCE", THRESHOLD, fixed(1.0, ToxicityCategory.INSULT)).evaluate(OWNER, "you idiot");

        assertEquals(1, scoring.deltas().size(), "one delta");
        assertEquals("chat", scoring.deltas().getFirst().section());
        assertEquals(-50, scoring.deltas().getFirst().delta(), "flag delta scaled by the score");
        assertEquals(EloReasonCode.TOXICITY_FLAG, scoring.deltas().getFirst().reason());
    }

    @Test
    void theChatSectionIsConfigurable() {
        service("social", "ENFORCE", THRESHOLD, fixed(1.0, ToxicityCategory.INSULT)).evaluate(OWNER, "you idiot");

        assertEquals("social", scoring.deltas().getFirst().section());
    }

    @Test
    void anInsultAtWeightPointSixAppliesThirtyPoints() {
        EloDTO.ChatSignalResponse result = service("ENFORCE", fixed(0.6, ToxicityCategory.INSULT)).evaluate(OWNER, "idiot");

        assertTrue(result.flagged(), "an insult at 0.6 reaches the 0.5 threshold");
        assertEquals(-30, scoring.deltas().getFirst().delta(), "0.6 times the flag delta of -50 is -30");
        assertEquals(EloReasonCode.TOXICITY_FLAG, scoring.deltas().getFirst().reason());
    }

    @Test
    void aCleanMessageIsNotFlaggedAndNothingIsScored() {
        EloDTO.ChatSignalResponse result = service("ENFORCE", message -> ToxicityVerdict.CLEAN).evaluate(OWNER, "hello");

        assertFalse(result.flagged());
        assertTrue(scoring.deltas().isEmpty(), "no delta for a clean message");
        assertTrue(evidence.all().isEmpty(), "no evidence for a clean message");
    }

    @Test
    void aCategoryBelowTheFlagThresholdIsNotFlagged() {
        EloDTO.ChatSignalResponse result = service("chat", "ENFORCE", 0.6, fixed(0.5, ToxicityCategory.SPAM))
                .evaluate(OWNER, "buy now");

        assertFalse(result.flagged(), "a SPAM score of 0.5 is below a threshold of 0.6");
        assertTrue(scoring.deltas().isEmpty(), "no delta below the threshold");
    }

    @Test
    void aFlaggedMessageStoresOnlyAHashAsEvidence() {
        service("ENFORCE", fixed(1.0, ToxicityCategory.INSULT)).evaluate(OWNER, "you idiot");

        assertEquals(1, evidence.all().size(), "one evidence record");
        assertFalse(evidence.all().getFirst().getCapturedContentHash().contains("idiot"), "the raw message is never stored");
    }

    @Test
    void shadowModeCallsTheScorerOnceAndAppliesNoDelta() {
        AtomicInteger calls = new AtomicInteger();
        ToxicityScorer counting = message -> {
            calls.incrementAndGet();
            return new ToxicityVerdict(1.0, Optional.of(ToxicityCategory.INSULT));
        };

        EloDTO.ChatSignalResponse result = service("SHADOW", counting).evaluate(OWNER, "you idiot");

        assertEquals(1, calls.get(), "the scorer is called once");
        assertTrue(scoring.deltas().isEmpty(), "shadow mode applies no ELO delta");
        assertTrue(evidence.all().isEmpty(), "shadow mode writes no evidence record");
        assertTrue(result.flagged(), "the would-be flag is reported in the response");
    }

    @Test
    void shadowModeCountsTheWouldBeFlag() {
        service("SHADOW", fixed(1.0, ToxicityCategory.THREAT)).evaluate(OWNER, "kill you");

        assertEquals(1.0, meters.counter("blackhole.chat.toxicity.flags", "category", "THREAT", "mode", "SHADOW").count(),
                "one would-be flag is counted");
    }

    @Test
    void offModeNeverCallsTheScorer() {
        AtomicInteger calls = new AtomicInteger();
        ToxicityScorer counting = message -> {
            calls.incrementAndGet();
            return new ToxicityVerdict(1.0, Optional.of(ToxicityCategory.INSULT));
        };

        EloDTO.ChatSignalResponse result = service("OFF", counting).evaluate(OWNER, "you idiot");

        assertEquals(0, calls.get(), "the scorer is not called in OFF mode");
        assertFalse(result.flagged(), "nothing is flagged in OFF mode");
        assertTrue(scoring.deltas().isEmpty(), "no delta in OFF mode");
    }

    @Test
    void aScorerFailureAppliesNoDeltaNoEvidenceAndStillAnswers() {
        ToxicityScorer failing = message -> {
            throw new IllegalStateException("scorer broke");
        };

        EloDTO.ChatSignalResponse result = service("ENFORCE", failing).evaluate(OWNER, "you idiot");

        assertFalse(result.flagged(), "a failed scoring is not a flag");
        assertTrue(scoring.deltas().isEmpty(), "no delta when scoring fails");
        assertTrue(evidence.all().isEmpty(), "no evidence when scoring fails");
    }

    @Test
    void theLogNeverContainsTheMessageTextOrTheOwnerHash() {
        String sentinel = "sentinel-text-7f3a";
        SnapshotLogAppender appender = new SnapshotLogAppender();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ChatToxicityService.class);
        logger.addAppender(appender);
        try {
            ToxicityScorer failing = message -> {
                throw new IllegalStateException(message);
            };
            service("ENFORCE", failing).evaluate(OWNER, sentinel);
            service("SHADOW", fixed(1.0, ToxicityCategory.INSULT)).evaluate(OWNER, sentinel);

            List<ILoggingEvent> lines = appender.snapshot();
            assertFalse(lines.isEmpty(), "the failure and the shadow flag are logged");
            lines.forEach(line -> {
                String text = line.getFormattedMessage();
                assertFalse(text.contains(sentinel), "a log line must not carry the message text: " + text);
                assertFalse(text.contains(OWNER), "a log line must not carry the owner hash: " + text);
            });
            assertTrue(lines.stream().anyMatch(line -> line.getLevel() == Level.WARN), "the scoring failure is logged as a warning");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
