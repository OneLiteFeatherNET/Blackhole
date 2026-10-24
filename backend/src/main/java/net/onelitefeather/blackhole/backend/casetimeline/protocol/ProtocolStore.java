package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The index of stored protocol versions ({@code appeal_protocol}). Each write is one SQL statement; there
 * is no transaction ({@code @Transactional} is unusable here), so the unique indexes carry the rules:
 * one version number once, one source digest once while readable, one {@code ACTIVE} version per appeal.
 */
public interface ProtocolStore {

    /**
     * The statement every redaction embeds as a data-modifying CTE, with the punishment identifier as its
     * one parameter: it makes every readable protocol version of the punishment unreadable in the same
     * statement that redacts the text, so no statement can leave old text readable.
     */
    String MARK_PURGE_PENDING = "UPDATE appeal_protocol SET status = 'PURGE_PENDING' "
            + "WHERE punishment_identifier = ? AND status IN ('ACTIVE', 'SUPERSEDED')";

    /** A version about to be added. */
    record NewVersion(
            UUID identifier, String punishmentIdentifier, UUID appealId, int version, UUID evidenceId, String sourceDigest,
            int entryCount, @Nullable Long firstSeq, @Nullable Long lastSeq, long createdAt
    ) {
    }

    /** The readable latest version of the appeal. */
    Optional<ProtocolVersion> active(UUID appealId);

    /** The version number the next version gets: one more than the highest ever used, purged ones included. */
    int nextVersion(UUID appealId);

    /**
     * Adds the version as {@code ACTIVE} and, in the same statement, turns the previous {@code ACTIVE}
     * version into {@code SUPERSEDED}. Adds nothing when a readable version with the same digest exists
     * or a concurrent run took the version number.
     *
     * @return whether the version was added
     */
    boolean add(NewVersion version);

    /** Every version of the punishment's appeals, newest first, whatever its status. */
    List<ProtocolVersion> versionsOf(String punishmentIdentifier);

    /** One version by its evidence item, whatever its status. */
    Optional<ProtocolVersion> byEvidence(UUID evidenceId);

    /** Versions a redaction marked {@code PURGE_PENDING}, oldest first. */
    List<ProtocolVersion> purgePending(int limit);

    /**
     * Marks a pending version {@code PURGED}.
     *
     * @return whether the version was pending
     */
    boolean markPurged(UUID identifier, long now);

    /**
     * Marks readable versions whose evidence item is gone (an admin deleted it) {@code PURGED}, so the
     * appeal counts as having no protocol and a new one is stored.
     *
     * @param appealId one appeal, or {@code null} for all
     * @return how many versions changed
     */
    int settleLostEvidence(@Nullable UUID appealId, long now);

    /**
     * Marks every readable version of the punishment {@code PURGE_PENDING} (a participant of its
     * conversation was erased).
     *
     * @return how many versions changed
     */
    int markPurgePending(String punishmentIdentifier);

    /**
     * Marks every readable version of every punishment with a case event by this staff actor
     * {@code PURGE_PENDING} (the actor was anonymised).
     *
     * @return the affected punishments
     */
    List<String> markPurgePendingForActor(UUID actorOid);

    /**
     * Decided appeals that may need a protocol: without a readable one, with a pending purge, or with a
     * message, appeal, Ban ID or ticket event newer than their active version. An over-approximation;
     * generation compares the digest and stores nothing when nothing changed.
     *
     * @param limit at most this many, most recently decided first
     */
    List<UUID> candidates(int limit);
}
