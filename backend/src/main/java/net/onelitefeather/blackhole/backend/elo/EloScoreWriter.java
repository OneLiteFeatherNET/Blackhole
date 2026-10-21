package net.onelitefeather.blackhole.backend.elo;

/**
 * Atomic writes of {@link EloScoreEntity} rows. Each method is a single statement, so concurrent
 * writers cannot lose each other's updates and the first write of a never-scored owner cannot fail
 * on the primary key - without needing a transaction.
 */
public interface EloScoreWriter {

    /**
     * Adds {@code delta} to the stored score, creating the row at {@code baseScore + delta} when the
     * owner was never scored in the section.
     *
     * @return the score actually stored afterwards
     */
    int addDelta(String owner, String sectionKey, int baseScore, int delta, long now);

    /**
     * Sets the stored score to {@code score}, creating the row if needed.
     */
    void setScore(String owner, String sectionKey, int score, long now);

    /**
     * Adds decay recovery, but only while the row was not written since {@code expectedUpdatedAt}.
     *
     * @return {@code false} if someone else wrote the row in the meantime and nothing changed
     */
    boolean recover(String owner, String sectionKey, int recovery, long expectedUpdatedAt, long now);
}
