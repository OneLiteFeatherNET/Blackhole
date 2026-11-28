package net.onelitefeather.blackhole.backend.elo.service;

import io.micrometer.core.instrument.MeterRegistry;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.elo.ToxicityScorer;
import net.onelitefeather.blackhole.backend.elo.ToxicityVerdict;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import net.onelitefeather.blackhole.backend.elo.toxicity.ToxicityMode;
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
 * Scores a chat message and, depending on {@code blackhole.elo.chat.toxicity.mode}, records a flag. In
 * {@code ENFORCE} a flag applies the ELO delta and a {@code PunishmentEvidenceEntity} (a hash of the message,
 * never the raw text). In {@code SHADOW} a flag only increments a metric and writes a log line without text.
 * A scorer failure is fail-open: no delta, no flag, and the request still answers. The raw message is only
 * held in memory long enough to score and hash it; it is never persisted or logged.
 */
@Singleton
public class ChatToxicityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatToxicityService.class);
    private static final String REQUESTS_METER = "blackhole.chat.toxicity.requests";
    private static final String FLAGS_METER = "blackhole.chat.toxicity.flags";

    private final ToxicityScorer scorer;
    private final EloScoring eloService;
    private final PunishmentEvidenceRepository evidenceRepository;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String section;
    private final ToxicityMode mode;
    private final double flagThreshold;
    private final int flagDelta;
    private final long evidenceRetentionMillis;

    public ChatToxicityService(
            ToxicityScorer scorer,
            EloScoring eloService,
            PunishmentEvidenceRepository evidenceRepository,
            Clock clock,
            MeterRegistry meters,
            @Value("${blackhole.elo.chat.section:chat}") String section,
            @Value("${blackhole.elo.chat.toxicity.mode:OFF}") String mode,
            @Value("${blackhole.elo.chat.toxicity.flag-threshold:0.5}") double flagThreshold,
            @Value("${blackhole.elo.chat.flag-delta:-50}") int flagDelta,
            @Value("${blackhole.elo.chat.evidence-retention-days:60}") int evidenceRetentionDays
    ) {
        this.scorer = scorer;
        this.eloService = eloService;
        this.evidenceRepository = evidenceRepository;
        this.clock = clock;
        this.meters = meters;
        this.section = section;
        this.mode = ToxicityMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        this.flagThreshold = flagThreshold;
        this.flagDelta = flagDelta;
        this.evidenceRetentionMillis = Duration.ofDays(evidenceRetentionDays).toMillis();
    }

    public EloDTO.ChatSignalResponse evaluate(String owner, String message) {
        if (this.mode == ToxicityMode.OFF) {
            count(REQUESTS_METER, "outcome", "off");
            return new EloDTO.ChatSignalResponse(false, 0.0);
        }

        ToxicityVerdict verdict;
        try {
            verdict = this.scorer.score(message);
        } catch (RuntimeException failure) {
            count(REQUESTS_METER, "outcome", "error");
            LOGGER.warn("Chat toxicity scoring failed in mode {}, no delta applied: {}", this.mode, failure.getClass().getSimpleName());
            return new EloDTO.ChatSignalResponse(false, 0.0);
        }
        count(REQUESTS_METER, "outcome", "scored");

        double score = verdict.score();
        if (score < this.flagThreshold) {
            return new EloDTO.ChatSignalResponse(false, score);
        }

        String category = verdict.category().map(Enum::name).orElse("NONE");
        this.meters.counter(FLAGS_METER, "category", category, "mode", this.mode.name()).increment();
        int delta = (int) Math.round(this.flagDelta * score);

        if (this.mode == ToxicityMode.SHADOW) {
            LOGGER.info("Chat toxicity shadow flag in section {}: category={} score={} would-be delta={}",
                    this.section, category, score, delta);
            return new EloDTO.ChatSignalResponse(true, score);
        }

        long now = this.clock.millis();
        String contentHash = SecretHasher.hash(message);
        PunishmentEvidenceEntity evidence = new PunishmentEvidenceEntity(
                null, EvidenceType.CHAT_MESSAGE, null, contentHash, now + this.evidenceRetentionMillis, new HashMap<>()
        );
        PunishmentEvidenceEntity savedEvidence = this.evidenceRepository.save(evidence);

        Map<String, Object> metaData = new HashMap<>();
        metaData.put("score", score);
        EloChange change = this.eloService.applyDelta(owner, this.section, delta, EloReasonCode.TOXICITY_FLAG, savedEvidence.getIdentifier(), metaData);
        if (change instanceof EloChange.Rejected rejected) {
            LOGGER.warn("Toxic chat message could not be scored in section {}: {}", this.section, rejected.message());
        }

        return new EloDTO.ChatSignalResponse(true, score);
    }

    private void count(String name, String tagKey, String tagValue) {
        this.meters.counter(name, tagKey, tagValue, "mode", this.mode.name()).increment();
    }
}
