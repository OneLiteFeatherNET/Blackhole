package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.EloEventEntity;
import net.onelitefeather.blackhole.backend.database.entities.EloProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileId;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.EloEventRepository;
import net.onelitefeather.blackhole.backend.database.repository.EloProfileRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.dto.EloReasonCode;
import net.onelitefeather.blackhole.backend.dto.EloTrack;
import net.onelitefeather.blackhole.backend.dto.PunishType;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentApplicationService;
import net.onelitefeather.phoca.metadata.Durationable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central write path for both ELO tracks. Every mutation - a chat toxicity flag, an anticheat
 * signal, an actioned report, or nightly decay - goes through {@link #applyDelta}, so the
 * threshold check and audit trail are never bypassed regardless of which signal caused a
 * change.
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> the roadmap calls for the threshold
 * check to run in the same transaction as the triggering write, so two concurrent violations on
 * the same track can't both race past the same threshold undetected. This was attempted via
 * {@code @Transactional}, but this project has two competing auto-configured
 * {@code TransactionOperations} beans for the "default" datasource
 * ({@code SpringHibernateTransactionOperations} from {@code micronaut-data-spring-jpa} and a
 * plain-JDBC one it pulls in transitively) that collide as soon as anything uses
 * {@code @Transactional} - the first real use of that annotation in this codebase. Resolving it
 * requires either untangling that dependency (attempted; swapping to
 * {@code micronaut-data-runtime} fixed the ambiguity but broke JPA repository operations
 * entirely - {@code PrimaryRepositoryOperations} disappeared too) or a properly-qualified
 * transaction manager bean, neither of which was safe to land without deeper Micronaut Data
 * expertise than this session could verify. In practice this is a narrow window (two
 * simultaneous violations for the exact same player/track landing in the same few
 * milliseconds), not something this phase's manual verification could exercise either way.</p>
 */
@Singleton
public class EloService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EloService.class);

    /**
     * A well-defined, deterministic identity for punishments the ELO system applies
     * automatically - never a real staff member. Distinct from Phase 2's vanilla-import source
     * UUID, so the audit trail can tell "algorithm decided" apart from "an import" apart from
     * "a human decided".
     */
    public static final UUID SYSTEM_ELO_SOURCE = UUID.nameUUIDFromBytes("blackhole-elo-system".getBytes(StandardCharsets.UTF_8));

    private static final String TIER_SOFT = "SOFT";
    private static final String TIER_HARD = "HARD";

    private final EloProfileRepository profileRepository;
    private final EloEventRepository eventRepository;
    private final PunishmentTemplateRepository templateRepository;
    private final PunishmentApplicationService punishmentApplicationService;
    private final DomainEventPublisher eventPublisher;

    private final int baseline;
    private final int softThreshold;
    private final int hardThreshold;
    private final int decayRecoveryPerDay;
    private final long decayIntervalMs;
    private final String chatSoftDuration;
    private final String gameplaySoftDuration;

    public EloService(
            EloProfileRepository profileRepository,
            EloEventRepository eventRepository,
            PunishmentTemplateRepository templateRepository,
            PunishmentApplicationService punishmentApplicationService,
            DomainEventPublisher eventPublisher,
            @Value("${blackhole.elo.baseline:1000}") int baseline,
            @Value("${blackhole.elo.soft-threshold:700}") int softThreshold,
            @Value("${blackhole.elo.hard-threshold:300}") int hardThreshold,
            @Value("${blackhole.elo.decay.recovery-per-day:10}") int decayRecoveryPerDay,
            @Value("${blackhole.elo.decay.interval-ms:86400000}") long decayIntervalMs,
            @Value("${blackhole.elo.auto-ban.chat-soft-duration:PT24H}") String chatSoftDuration,
            @Value("${blackhole.elo.auto-ban.gameplay-soft-duration:P3D}") String gameplaySoftDuration
    ) {
        this.profileRepository = profileRepository;
        this.eventRepository = eventRepository;
        this.templateRepository = templateRepository;
        this.punishmentApplicationService = punishmentApplicationService;
        this.eventPublisher = eventPublisher;
        this.baseline = baseline;
        this.softThreshold = softThreshold;
        this.hardThreshold = hardThreshold;
        this.decayRecoveryPerDay = decayRecoveryPerDay;
        this.decayIntervalMs = decayIntervalMs;
        this.chatSoftDuration = chatSoftDuration;
        this.gameplaySoftDuration = gameplaySoftDuration;
    }

    /**
     * Applies a delta to one track of a player's ELO profile, reconciling any pending decay
     * first, recording an audit-trail event, and triggering an automatic punishment if this
     * delta newly crosses the soft or hard threshold.
     *
     * @param tenantId         the tenant this applies to
     * @param owner            the SHA-512-hashed owner
     * @param track            which of the two independent tracks this affects
     * @param delta            signed change to apply (negative for violations)
     * @param reasonCode       why this delta happened
     * @param sourceEvidenceId optional reference to a {@code PunishmentEvidenceEntity}
     * @param metaData         extra context for the audit trail
     * @return the updated profile
     */
    public EloProfileEntity applyDelta(
            UUID tenantId,
            String owner,
            EloTrack track,
            int delta,
            EloReasonCode reasonCode,
            UUID sourceEvidenceId,
            Map<String, Object> metaData
    ) {
        long now = System.currentTimeMillis();
        PunishmentProfileId id = new PunishmentProfileId(tenantId, owner);
        EloProfileEntity profile = this.profileRepository.findById(id).orElse(null);
        boolean isNew = profile == null;
        if (isNew) {
            profile = new EloProfileEntity(tenantId, owner, this.baseline, this.baseline, now, now, new HashMap<>());
        }

        reconcileDecay(profile, track, now);

        int previousScore = profile.getScore(track);
        int newScore = previousScore + delta;
        profile.setScore(track, newScore);
        profile.setUpdatedAt(track, now);

        EloProfileEntity savedProfile = isNew ? this.profileRepository.save(profile) : this.profileRepository.update(profile);

        this.eventRepository.save(new EloEventEntity(
                tenantId, owner, track, delta, reasonCode, sourceEvidenceId, newScore, now,
                metaData == null ? new HashMap<>() : metaData
        ));

        checkThresholds(tenantId, owner, track, previousScore, newScore, reasonCode);

        return savedProfile;
    }

    /**
     * Applies any decay owed since the track's last update, capped so it never overshoots
     * {@code baseline} - only explicit violations/rewards can cross the baseline, pure decay
     * only ever restores toward it. Mutates {@code profile} in place; does not save it (the
     * caller saves once, after any further delta is applied on top).
     *
     * @return {@code true} if any decay was applied
     */
    private boolean reconcileDecay(EloProfileEntity profile, EloTrack track, long now) {
        int currentScore = profile.getScore(track);
        if (currentScore >= this.baseline) {
            return false;
        }
        long lastUpdated = profile.getUpdatedAt(track);
        long daysElapsed = (now - lastUpdated) / this.decayIntervalMs;
        if (daysElapsed <= 0) {
            return false;
        }
        int recovery = (int) Math.min((long) this.decayRecoveryPerDay * daysElapsed, (long) (this.baseline - currentScore));
        if (recovery <= 0) {
            return false;
        }
        int newScore = currentScore + recovery;
        profile.setScore(track, newScore);
        profile.setUpdatedAt(track, now);

        this.eventRepository.save(new EloEventEntity(
                profile.getTenantId(), profile.getOwner(), track, recovery, EloReasonCode.DECAY_RECOVERY, null, newScore, now,
                Map.of("daysElapsed", daysElapsed)
        ));
        return true;
    }

    /**
     * Reconciles pending decay for both tracks of a single profile and saves it if anything
     * changed. Called by the nightly sweep so inactive players stay eventually consistent even
     * without any triggering delta.
     *
     * @return {@code true} if the profile was updated
     */
    public boolean reconcileDecayForProfile(EloProfileEntity profile) {
        long now = System.currentTimeMillis();
        boolean chatChanged = reconcileDecay(profile, EloTrack.CHAT, now);
        boolean gameplayChanged = reconcileDecay(profile, EloTrack.GAMEPLAY, now);
        if (chatChanged || gameplayChanged) {
            this.profileRepository.update(profile);
            return true;
        }
        return false;
    }

    /**
     * Only acts on a genuine downward crossing (was at/above the threshold, now below it) -
     * otherwise a player already below the hard threshold would get re-banned on every
     * subsequent violation.
     */
    private void checkThresholds(UUID tenantId, String owner, EloTrack track, int previousScore, int newScore, EloReasonCode triggeringReasonCode) {
        if (previousScore >= this.hardThreshold && newScore < this.hardThreshold) {
            triggerAutoBan(tenantId, owner, track, TIER_HARD, newScore, triggeringReasonCode);
        } else if (previousScore >= this.softThreshold && newScore < this.softThreshold) {
            triggerAutoBan(tenantId, owner, track, TIER_SOFT, newScore, triggeringReasonCode);
        }
    }

    private void triggerAutoBan(UUID tenantId, String owner, EloTrack track, String tier, int resultingScore, EloReasonCode triggeringReasonCode) {
        PunishmentTemplateEntity template = findOrCreateAutoTemplate(tenantId, track, tier);
        Map<String, Object> extraMetaData = Map.of("eloTriggerReasonCode", triggeringReasonCode.toString());
        var applied = this.punishmentApplicationService.apply(tenantId, owner, template.getIdentifier(), SYSTEM_ELO_SOURCE, extraMetaData);
        if (applied.isEmpty()) {
            LOGGER.error("ELO auto-ban failed to apply template {} for tenant {}: template vanished after find-or-create", template.getIdentifier(), tenantId);
        }

        this.eventPublisher.publish("elo.threshold_crossed", Map.of(
                "tenantId", tenantId.toString(),
                "owner", owner,
                "track", track.toString(),
                "tier", tier,
                "resultingScore", resultingScore
        ));
    }

    private PunishmentTemplateEntity findOrCreateAutoTemplate(UUID tenantId, EloTrack track, String tier) {
        String reason = autoTemplateReason(track, tier);
        PunishType type = (track == EloTrack.CHAT && TIER_SOFT.equals(tier)) ? PunishType.CHAT : PunishType.NETWORK;
        return this.templateRepository.findByTenantIdAndReasonAndType(tenantId, reason, type)
                .orElseGet(() -> {
                    Map<String, Object> metaData = new HashMap<>();
                    metaData.put("auto", true);
                    metaData.put("eloTriggered", true);
                    if (TIER_SOFT.equals(tier)) {
                        metaData.put(Durationable.META_DATA_KEY_DURATION, track == EloTrack.CHAT ? this.chatSoftDuration : this.gameplaySoftDuration);
                    }
                    return this.templateRepository.save(new PunishmentTemplateEntity(null, tenantId, reason, type, metaData));
                });
    }

    private String autoTemplateReason(EloTrack track, String tier) {
        boolean soft = TIER_SOFT.equals(tier);
        return switch (track) {
            case CHAT -> soft ? "Automatic chat timeout (ELO system)" : "Automatic permanent ban (ELO system, chat)";
            case GAMEPLAY -> soft ? "Automatic temporary ban (ELO system, gameplay)" : "Automatic permanent ban (ELO system, gameplay)";
        };
    }
}
