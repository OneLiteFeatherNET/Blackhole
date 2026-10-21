package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

/**
 * Decides whether a score change makes the system punish automatically. The default is the
 * {@link ThresholdEscalationPolicy}; a network can provide its own bean (for example a strike-count
 * policy) to replace it.
 */
public interface EscalationPolicy {

    /**
     * @param context the score change and the state around it
     * @return what the system should do because of this change
     */
    Decision decide(Context context);

    /** What the system does after a score change. */
    enum Decision {
        /** Nothing. */
        NONE,
        /** Apply a temporary punishment for the section's soft duration. */
        SOFT,
        /** Apply a permanent punishment with the section's permanent reason. */
        PERMANENT
    }

    /**
     * @param owner              the hashed owner whose score changed
     * @param section            the section the score belongs to
     * @param scoreBefore        the score before the change
     * @param scoreAfter         the score after the change
     * @param hasActivePermanent whether the owner already has an active permanent punishment in the section
     */
    record Context(String owner, SectionDTO.Response section, int scoreBefore, int scoreAfter, boolean hasActivePermanent) {

        public boolean decreased() {
            return this.scoreAfter < this.scoreBefore;
        }
    }
}
