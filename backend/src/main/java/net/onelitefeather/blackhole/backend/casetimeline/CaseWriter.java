package net.onelitefeather.blackhole.backend.casetimeline;

/**
 * Changes to the case row that are themselves history: each is one SQL statement that changes the
 * state and appends its event together, so the two never diverge (no {@code @Transactional}, see
 * {@code JdbcEloScoreWriter} for the precedent).
 */
public interface CaseWriter {

    /**
     * Reopens a {@code CLOSED} case to {@code OPEN} (a filed appeal needs a look) and appends a
     * {@code case.status_changed} event by the system. Does nothing for a case that is not closed or
     * does not exist.
     *
     * @return whether the case was reopened
     */
    boolean reopenIfClosed(String punishmentIdentifier, long now);
}
