package net.onelitefeather.blackhole.backend.elo;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The write path for per-section scores. Every mutation goes through here so the threshold check
 * and audit trail are never bypassed regardless of which signal caused a change.
 */
public interface EloScoring {

    /**
     * Applies a signed delta to the owner's score in one section, reconciling pending decay first,
     * recording an audit event and, for a negative delta, letting the escalation policy decide
     * whether the system punishes automatically.
     *
     * @param owner            the SHA-512-hashed owner
     * @param sectionKey       the section whose score changes
     * @param delta            signed change (negative for violations)
     * @param reason           why this delta happened
     * @param sourceEvidenceId optional reference to a {@code PunishmentEvidenceEntity}
     * @param metaData         extra context for the audit trail
     */
    EloChange applyDelta(String owner, String sectionKey, int delta, EloReasonCode reason, UUID sourceEvidenceId, Map<String, Object> metaData);

    /**
     * The owner's current score, or the section's base score if the owner was never scored.
     *
     * @return the score, or empty if the section does not exist
     */
    Optional<Integer> score(String owner, String sectionKey);

    /**
     * Sets the owner's score to the section's pardon score (reason {@link EloReasonCode#PARDON_RESET}).
     *
     * @return the new score, or empty if the section does not exist
     */
    Optional<Integer> resetToPardon(String owner, String sectionKey, Map<String, Object> metaData);
}
