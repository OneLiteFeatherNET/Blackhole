package net.onelitefeather.blackhole.backend.elo.service;

import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.elo.ToxicityScorer;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.EvidenceType;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvidenceEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvidenceRepository;
import net.onelitefeather.blackhole.backend.utils.SecretHasher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Scores a chat message and, if flagged, records a {@code PunishmentEvidenceEntity} (a hash of
 * the message, never the raw text - the whole point of the Phase 2 evidence model) and applies
 * the resulting ELO delta in the configured chat section. The raw message is only ever held in
 * memory long enough to hash and score it; it is never persisted.
 */
@Singleton
public class ChatToxicityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatToxicityService.class);

    private final ToxicityScorer scorer;
    private final EloScoring eloService;
    private final PunishmentEvidenceRepository evidenceRepository;
    private final Clock clock;
    private final String section;
    private final double flagThreshold;
    private final int flagDelta;
    private final long evidenceRetentionMillis;

    public ChatToxicityService(
            ToxicityScorer scorer,
            EloScoring eloService,
            PunishmentEvidenceRepository evidenceRepository,
            Clock clock,
            @Value("${blackhole.elo.chat.section:chat}") String section,
            @Value("${blackhole.elo.chat.flag-threshold:0.3}") double flagThreshold,
            @Value("${blackhole.elo.chat.flag-delta:-50}") int flagDelta,
            @Value("${blackhole.elo.chat.evidence-retention-days:60}") int evidenceRetentionDays
    ) {
        this.scorer = scorer;
        this.eloService = eloService;
        this.evidenceRepository = evidenceRepository;
        this.clock = clock;
        this.section = section;
        this.flagThreshold = flagThreshold;
        this.flagDelta = flagDelta;
        this.evidenceRetentionMillis = Duration.ofDays(evidenceRetentionDays).toMillis();
    }

    public EloDTO.ChatSignalResponse evaluate(String owner, String message) {
        double score = this.scorer.score(message);
        if (score < this.flagThreshold) {
            return new EloDTO.ChatSignalResponse(false, score);
        }

        long now = this.clock.millis();
        String contentHash = SecretHasher.hash(message);
        PunishmentEvidenceEntity evidence = new PunishmentEvidenceEntity(
                null, EvidenceType.CHAT_MESSAGE, null, contentHash, now + this.evidenceRetentionMillis, new HashMap<>()
        );
        PunishmentEvidenceEntity savedEvidence = this.evidenceRepository.save(evidence);

        int delta = (int) Math.round(this.flagDelta * score);
        Map<String, Object> metaData = new HashMap<>();
        metaData.put("score", score);
        EloChange change = this.eloService.applyDelta(owner, this.section, delta, EloReasonCode.TOXICITY_FLAG, savedEvidence.getIdentifier(), metaData);
        if (change instanceof EloChange.Rejected rejected) {
            LOGGER.warn("Toxic chat message could not be scored in section {}: {}", this.section, rejected.message());
        }

        return new EloDTO.ChatSignalResponse(true, score);
    }
}
