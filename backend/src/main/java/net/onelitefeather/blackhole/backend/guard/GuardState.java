package net.onelitefeather.blackhole.backend.guard;

/**
 * The guard row: the manual level, the unattended level and the effective level, which is the stricter of the two.
 * Built only through {@link #of}, so the effective level can never disagree with its sources.
 */
public record GuardState(GuardLevel manual, GuardLevel automatic, GuardLevel effective, long updatedAt) {

    public static GuardState of(GuardLevel manual, GuardLevel automatic, long updatedAt) {
        return new GuardState(manual, automatic, manual.stricterOf(automatic), updatedAt);
    }

    public static GuardState initial(long updatedAt) {
        return of(GuardLevel.OFF, GuardLevel.OFF, updatedAt);
    }
}
