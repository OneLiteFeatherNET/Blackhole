package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEvidenceEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentEvidenceRepository;
import net.onelitefeather.blackhole.backend.dto.EloReasonCode;
import net.onelitefeather.blackhole.backend.dto.EloTrack;
import net.onelitefeather.blackhole.backend.dto.EvidenceType;
import net.onelitefeather.blackhole.backend.utils.SecretHasher;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Scores a chat message and, if flagged, records a {@code PunishmentEvidenceEntity} (a hash of
 * the message, never the raw text - the whole point of the Phase 2 evidence model) and applies
 * the resulting chat-ELO delta. The raw message is only ever held in memory long enough to hash
 * and score it; it is never persisted.
 */
@Singleton
public class ChatToxicityService {

    private final ToxicityScorer scorer;
    private final EloService eloService;
    private final PunishmentEvidenceRepository evidenceRepository;
    private final double flagThreshold;
    private final int flagDelta;
    private final long evidenceRetentionMillis;

    public ChatToxicityService(
            ToxicityScorer scorer,
            EloService eloService,
            PunishmentEvidenceRepository evidenceRepository,
            @Value("${blackhole.elo.chat.flag-threshold:0.3}") double flagThreshold,
            @Value("${blackhole.elo.chat.flag-delta:-50}") int flagDelta,
            @Value("${blackhole.elo.chat.evidence-retention-days:60}") int evidenceRetentionDays
    ) {
        this.scorer = scorer;
        this.eloService = eloService;
        this.evidenceRepository = evidenceRepository;
        this.flagThreshold = flagThreshold;
        this.flagDelta = flagDelta;
        this.evidenceRetentionMillis = Duration.ofDays(evidenceRetentionDays).toMillis();
    }

    public ChatToxicityResult evaluate(UUID tenantId, String owner, String message) {
        double score = this.scorer.score(message);
        if (score < this.flagThreshold) {
            return new ChatToxicityResult(false, score);
        }

        long now = System.currentTimeMillis();
        String contentHash = SecretHasher.hash(message);
        PunishmentEvidenceEntity evidence = new PunishmentEvidenceEntity(
                tenantId, null, EvidenceType.CHAT_MESSAGE, null, contentHash, now + this.evidenceRetentionMillis, new HashMap<>()
        );
        PunishmentEvidenceEntity savedEvidence = this.evidenceRepository.save(evidence);

        int delta = (int) Math.round(this.flagDelta * score);
        Map<String, Object> metaData = new HashMap<>();
        metaData.put("score", score);
        this.eloService.applyDelta(tenantId, owner, EloTrack.CHAT, delta, EloReasonCode.TOXICITY_FLAG, savedEvidence.getIdentifier(), metaData);

        return new ChatToxicityResult(true, score);
    }
}
