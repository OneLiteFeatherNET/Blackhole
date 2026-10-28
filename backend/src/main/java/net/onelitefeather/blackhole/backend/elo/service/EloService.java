package net.onelitefeather.blackhole.backend.elo.service;

import net.onelitefeather.blackhole.backend.security.Actor;
import io.micronaut.context.annotation.Value;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloEventEntity;
import net.onelitefeather.blackhole.backend.elo.EloEventRepository;
import net.onelitefeather.blackhole.backend.elo.EloProfileCache;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloScoreEntity;
import net.onelitefeather.blackhole.backend.elo.EloScoreId;
import net.onelitefeather.blackhole.backend.elo.EloScoreRepository;
import net.onelitefeather.blackhole.backend.elo.EloScoreWriter;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.elo.EscalationPolicy;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.SectionPunisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.utils.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central write path for the per-section ELO scores. Every mutation - a chat toxicity flag, an
 * anticheat signal, an actioned report, a staff punishment, a manual adjustment or nightly decay -
 * goes through here, so the threshold check and audit trail are never bypassed regardless of which
 * signal caused a change.
 *
 * <p>Score writes are single atomic statements ({@link EloScoreWriter}), so concurrent deltas for the
 * same player and section add up instead of overwriting each other, and the resulting score the
 * threshold check sees is the one actually stored. {@code @Transactional} is unusable in this
 * codebase (two competing auto-configured {@code TransactionOperations} beans for the "default"
 * datasource), so the event row and the escalation are separate steps after the score write; two
 * simultaneous violations for the same player and section can still both observe "no active
 * permanent punishment", which the primary key of the active-punishment slot resolves.</p>
 */
@Singleton
public class EloService implements EloScoring {

    private static final Logger LOGGER = LoggerFactory.getLogger(EloService.class);

    /**
     * A well-defined, deterministic identity for punishments the ELO system applies
     * automatically - never a real staff member. Distinct from the vanilla-import source UUID, so
     * the audit trail can tell "algorithm decided" apart from "an import" apart from "a human decided".
     */
    public static final UUID SYSTEM_ELO_SOURCE = UUID.nameUUIDFromBytes("blackhole-elo-system".getBytes(StandardCharsets.UTF_8));

    private static final int MAX_NOTE_LENGTH = 500;

    private final EloScoreRepository scoreRepository;
    private final EloScoreWriter scoreWriter;
    private final EloEventRepository eventRepository;
    private final SectionService sectionService;
    private final SectionPunisher punisher;
    private final EscalationPolicy escalationPolicy;
    private final DomainEventPublisher eventPublisher;
    private final EloProfileCache profileCache;
    private final Clock clock;
    private final long decayIntervalMs;
    private final StaffAuditRecorder audit;

    public EloService(
            EloScoreRepository scoreRepository,
            EloScoreWriter scoreWriter,
            EloEventRepository eventRepository,
            SectionService sectionService,
            SectionPunisher punisher,
            EscalationPolicy escalationPolicy,
            DomainEventPublisher eventPublisher,
            EloProfileCache profileCache,
            Clock clock,
            @Value("${blackhole.elo.decay.interval-ms:86400000}") long decayIntervalMs,
            StaffAuditRecorder audit
    ) {
        this.audit = audit;
        this.scoreRepository = scoreRepository;
        this.scoreWriter = scoreWriter;
        this.eventRepository = eventRepository;
        this.sectionService = sectionService;
        this.punisher = punisher;
        this.escalationPolicy = escalationPolicy;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.clock = clock;
        this.decayIntervalMs = decayIntervalMs;
    }

    @Override
    public EloChange applyDelta(
            String owner,
            String sectionKey,
            int delta,
            EloReasonCode reason,
            UUID sourceEvidenceId,
            Map<String, Object> metaData
    ) {
        SectionDTO.Response section = this.sectionService.find(sectionKey).orElse(null);
        if (section == null) {
            return new EloChange.Rejected(ErrorKind.NOT_FOUND, "there is no section with key '" + sectionKey + "'");
        }
        if (!section.enabled()) {
            return new EloChange.Rejected(ErrorKind.CONFLICT, "section '" + sectionKey + "' is disabled");
        }

        long now = this.clock.millis();
        EloScoreEntity existing = this.scoreRepository.findById(new EloScoreId(owner, sectionKey)).orElse(null);
        if (existing != null) {
            reconcileDecay(existing, section, now);
        }

        // One atomic statement: concurrent deltas add up and a first write cannot collide on the key.
        int after = this.scoreWriter.addDelta(owner, sectionKey, section.baseScore(), delta, now);
        int before = after - delta;
        this.profileCache.evict(owner);

        this.eventRepository.save(new EloEventEntity(
                owner, sectionKey, delta, reason, sourceEvidenceId, after, now,
                metaData == null ? new HashMap<>() : new HashMap<>(metaData)
        ));

        Optional<EloChange.AutomaticPunishment> automatic = escalate(owner, section, before, after, reason);
        return new EloChange.Applied(sectionKey, before, after, automatic);
    }

    @Override
    public Optional<Integer> score(String owner, String sectionKey) {
        return this.sectionService.find(sectionKey).map(section -> this.scoreRepository
                .findById(new EloScoreId(owner, sectionKey))
                .map(EloScoreEntity::getScore)
                .orElse(section.baseScore()));
    }

    @Override
    public Optional<Integer> resetToPardon(String owner, String sectionKey, Map<String, Object> metaData) {
        SectionDTO.Response section = this.sectionService.find(sectionKey).orElse(null);
        if (section == null) {
            return Optional.empty();
        }
        long now = this.clock.millis();
        int before = this.scoreRepository.findById(new EloScoreId(owner, sectionKey))
                .map(EloScoreEntity::getScore).orElse(section.baseScore());
        int pardon = section.pardonScore();
        this.scoreWriter.setScore(owner, sectionKey, pardon, now);
        this.profileCache.evict(owner);
        this.eventRepository.save(new EloEventEntity(
                owner, sectionKey, pardon - before, EloReasonCode.PARDON_RESET, null, pardon, now,
                metaData == null ? new HashMap<>() : new HashMap<>(metaData)
        ));
        return Optional.of(pardon);
    }

    /**
     * Lets the escalation policy decide and, if it says so, has the punisher apply the matching
     * automatic punishment. Only decreases can escalate, so rewards skip the lookup of the active
     * permanent punishment entirely.
     */
    private Optional<EloChange.AutomaticPunishment> escalate(
            String owner, SectionDTO.Response section, int before, int after, EloReasonCode trigger
    ) {
        if (after >= before) {
            return Optional.empty();
        }
        boolean hasActivePermanent = this.punisher.hasActivePermanent(owner, section.key());
        EscalationPolicy.Decision decision = this.escalationPolicy.decide(
                new EscalationPolicy.Context(owner, section, before, after, hasActivePermanent)
        );
        if (decision == EscalationPolicy.Decision.SOFT && trigger == EloReasonCode.PUNISHMENT_APPLIED) {
            // The staff member's own temporary punishment already occupies the section's slot; an
            // additional automatic one would replace it with a possibly shorter punishment.
            return Optional.empty();
        }
        Map<String, Object> extraMetaData = Map.of("eloTriggerReasonCode", trigger.toString(), "sectionKey", section.key());

        return switch (decision) {
            case NONE -> Optional.empty();
            case PERMANENT -> {
                Optional<String> identifier = this.punisher.applyPermanent(owner, section, extraMetaData);
                if (identifier.isEmpty()) {
                    LOGGER.error("Permanent escalation in section {} failed to apply for {}", section.key(), owner);
                    yield Optional.empty();
                }
                this.eventPublisher.publish("section.escalated", Map.of(
                        "owner", owner,
                        "section", section.key(),
                        "resultingScore", after,
                        "punishmentIdentifier", identifier.get()
                ));
                this.eventPublisher.publish("elo.threshold_crossed", Map.of(
                        "owner", owner, "section", section.key(), "tier", EloChange.Tier.PERMANENT.name(), "resultingScore", after
                ));
                yield Optional.of(new EloChange.AutomaticPunishment(EloChange.Tier.PERMANENT, identifier.get()));
            }
            case SOFT -> {
                Optional<Duration> duration = Durations.parsePositive(section.softDuration());
                if (duration.isEmpty()) {
                    LOGGER.error("Section {} has a soft threshold but no valid soft duration; skipping the soft punishment", section.key());
                    yield Optional.empty();
                }
                Optional<String> identifier = this.punisher.applySoft(owner, section, duration.get(), extraMetaData);
                if (identifier.isEmpty()) {
                    LOGGER.error("Soft punishment in section {} failed to apply for {}", section.key(), owner);
                    yield Optional.empty();
                }
                this.eventPublisher.publish("elo.threshold_crossed", Map.of(
                        "owner", owner, "section", section.key(), "tier", EloChange.Tier.SOFT.name(), "resultingScore", after
                ));
                yield Optional.of(new EloChange.AutomaticPunishment(EloChange.Tier.SOFT, identifier.get()));
            }
        };
    }

    /**
     * Applies any decay owed since the score's last update, capped so it never overshoots the
     * section's base score - only explicit violations or rewards can cross the base, pure decay only
     * restores toward it. The recovery is written conditionally on the row not having changed since it
     * was read; if somebody else wrote it meanwhile nothing is applied now and the decay is picked up
     * later. Records a {@code DECAY_RECOVERY} event when it was applied.
     *
     * @return {@code true} if any decay was applied
     */
    private boolean reconcileDecay(EloScoreEntity score, SectionDTO.Response section, long now) {
        int current = score.getScore();
        int base = section.baseScore();
        if (current >= base || section.decayPerDay() <= 0) {
            return false;
        }
        long daysElapsed = (now - score.getUpdatedAt()) / this.decayIntervalMs;
        if (daysElapsed <= 0) {
            return false;
        }
        int recovery = (int) Math.min((long) section.decayPerDay() * daysElapsed, (long) (base - current));
        if (recovery <= 0) {
            return false;
        }
        if (!this.scoreWriter.recover(score.getOwner(), score.getSectionKey(), recovery, score.getUpdatedAt(), now)) {
            return false;
        }
        int recovered = current + recovery;
        this.eventRepository.save(new EloEventEntity(
                score.getOwner(), score.getSectionKey(), recovery, EloReasonCode.DECAY_RECOVERY, null, recovered, now,
                new HashMap<>(Map.of("daysElapsed", daysElapsed))
        ));
        return true;
    }

    /**
     * Reconciles pending decay of a single stored score and saves it if anything changed. Called
     * by the nightly sweep so inactive players stay eventually consistent even without any
     * triggering delta.
     *
     * @return {@code true} if the score was updated
     */
    public boolean reconcileDecayForScore(EloScoreEntity score) {
        SectionDTO.Response section = this.sectionService.find(score.getSectionKey()).orElse(null);
        if (section == null) {
            return false;
        }
        if (reconcileDecay(score, section, this.clock.millis())) {
            this.profileCache.evict(score.getOwner());
            return true;
        }
        return false;
    }

    /**
     * Dashboard read: a player's standing in every enabled section, with the thresholds of each.
     * A section the player was never scored in shows its base score. Served from the shared cache
     * when present.
     *
     * @param owner the SHA-512-hashed owner
     */
    public EloDTO.ProfileResponse getProfile(String owner) {
        Optional<EloDTO.ProfileResponse> cached = this.profileCache.get(owner);
        if (cached.isPresent()) {
            return cached.get();
        }
        Map<String, Integer> scores = this.scoreRepository.findByIdOwner(owner).stream()
                .collect(Collectors.toMap(EloScoreEntity::getSectionKey, EloScoreEntity::getScore, (a, b) -> a));
        List<EloDTO.SectionScore> sections = this.sectionService.listEnabled().stream()
                .map(section -> new EloDTO.SectionScore(
                        section.key(), section.displayName(), section.enforcement(),
                        scores.getOrDefault(section.key(), section.baseScore()), section.baseScore(),
                        section.softThreshold(), section.permanentThreshold(), section.pardonScore()
                ))
                .toList();
        EloDTO.ProfileResponse profile = new EloDTO.ProfileResponse(owner, sections);
        this.profileCache.put(owner, profile);
        return profile;
    }

    /**
     * Dashboard read: the audit trail of ELO changes for this owner.
     *
     * @param owner      the SHA-512-hashed owner
     * @param sectionKey restricts the history to one section, or {@code null} for all
     * @param pageable   pagination/sorting parameters
     */
    public Page<EloEventEntity> getHistory(String owner, String sectionKey, Pageable pageable) {
        if (sectionKey == null || sectionKey.isBlank()) {
            return this.eventRepository.findByOwner(owner, pageable);
        }
        return this.eventRepository.findByOwnerAndSectionKey(owner, sectionKey, pageable);
    }

    /**
     * Applies a manual score adjustment, subject to the same threshold rules as any other delta. The
     * event records the caller's verified {@code oid} as its {@code source} and {@code recordedByOid}.
     *
     * @return the {@link EloDTO.AdjustResponse}, or an {@link EloDTO.Error} for an invalid
     * request or an unknown or disabled section
     */
    public EloDTO adjust(String owner, EloDTO.AdjustRequest request, Actor actor) {
        if (request.delta() == 0) {
            return new EloDTO.Error("delta must not be zero", ErrorKind.VALIDATION);
        }
        if (request.note() != null && request.note().length() > MAX_NOTE_LENGTH) {
            return new EloDTO.Error("note must be at most " + MAX_NOTE_LENGTH + " characters", ErrorKind.VALIDATION);
        }
        Map<String, Object> metaData = new HashMap<>();
        metaData.put("source", actor.oid().toString());
        metaData.put("recordedByOid", actor.oid().toString());
        if (request.note() != null) {
            metaData.put("note", request.note());
        }
        EloChange change = applyDelta(owner, request.section(), request.delta(), EloReasonCode.MANUAL_ADJUSTMENT, null, metaData);
        return switch (change) {
            case EloChange.Applied applied -> {
                this.audit.record(actor.attribution(), actor, AuditAction.ELO_ADJUST, AuditTarget.ofPlayer(owner));
                yield EloDTO.AdjustResponse.of(applied);
            }
            case EloChange.Rejected rejected -> new EloDTO.Error(rejected.message(), rejected.kind());
        };
    }
}
