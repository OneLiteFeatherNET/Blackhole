package net.onelitefeather.blackhole.backend.guard;

/**
 * One row of the level history: a change of the effective level and what caused it. {@code actorOid} is null for the
 * unattended evaluator.
 */
public record GuardChange(long changedAt, GuardLevel previous, GuardLevel next, Source source, String actorOid) {

    /** What caused the change. */
    public enum Source {
        /** A staff member set the manual level. */
        MANUAL,
        /** The unattended evaluator raised the automatic level. */
        UNATTENDED,
        /** The unattended evaluator cleared the automatic level because staff came back. */
        CLEARED
    }
}
