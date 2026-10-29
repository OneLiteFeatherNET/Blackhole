package net.onelitefeather.blackhole.backend.rollback;

import java.util.List;
import java.util.UUID;

/** Reads the punishments a selector matches. Read-only: nothing here writes a punishment, a slot or a job. */
public interface RollbackSelectionReader {

    /**
     * The punishments the selector wrote with a {@code created_at} inside {@code [from, to]}.
     *
     * @param selector the staff actor or unlinked account
     * @param from     the window start, epoch milliseconds, inclusive
     * @param to       the window end, epoch milliseconds, inclusive
     * @return every matching row, lifted or not; {@link Candidate#revocable()} tells which still hold their slot
     */
    List<Candidate> select(RollbackSelector selector, long from, long to);

    /** Whether {@code oid} is a HUMAN staff actor. A SERVICE principal, such as the proxy, and an unknown oid are not. */
    boolean isHumanStaffActor(UUID oid);

    /**
     * One matching punishment.
     *
     * @param identifier the punishment identifier
     * @param owner      the hashed owner
     * @param sectionKey the section the punishment is in
     * @param kind       {@code PUNISHMENT} or {@code KICK}
     * @param revocable  whether the owner's section slot still holds this punishment
     */
    record Candidate(String identifier, String owner, String sectionKey, String kind, boolean revocable) {
    }
}
