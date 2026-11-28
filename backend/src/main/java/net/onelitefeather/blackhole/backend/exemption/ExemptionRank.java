package net.onelitefeather.blackhole.backend.exemption;

import java.util.Arrays;
import java.util.Optional;

/**
 * The rank an actor or an exempt target stands on, ordered VIEWER &lt; MODERATOR &lt; ADMIN. An actor may punish an exempt target
 * only when the actor's rank is strictly higher than the target's.
 */
public enum ExemptionRank {

    VIEWER(1),
    MODERATOR(2),
    ADMIN(3);

    private final int level;

    ExemptionRank(int level) {
        this.level = level;
    }

    /** Whether this rank is strictly higher than {@code other}. */
    public boolean outranks(ExemptionRank other) {
        return this.level > other.level;
    }

    /**
     * The highest rank named in a role snapshot such as {@code ADMIN,MODERATOR,VIEWER}; unknown names are ignored.
     *
     * @return the highest rank, or empty when the snapshot names none
     */
    public static Optional<ExemptionRank> highest(String roles) {
        if (roles == null || roles.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(roles.split(","))
                .map(String::strip)
                .map(name -> Arrays.stream(values()).filter(rank -> rank.name().equals(name)).findFirst())
                .flatMap(Optional::stream)
                .max((a, b) -> Integer.compare(a.level, b.level));
    }
}
