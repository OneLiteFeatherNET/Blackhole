package net.onelitefeather.blackhole.velocity.guard;

import org.jetbrains.annotations.Nullable;

/**
 * The join guard levels, in the order the backend ranks them: {@code OFF} refuses nothing, {@code STRICT}
 * refuses addresses of VPN, proxy and hosting providers, and {@code LOCKDOWN} also refuses players the
 * backend does not know.
 */
public enum GuardLevel {
    OFF,
    STRICT,
    LOCKDOWN;

    /**
     * @param name the level as the backend writes it, in any letter case
     * @return the level, or null when the name is none of the three
     */
    public static @Nullable GuardLevel parse(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (GuardLevel level : values()) {
            if (level.name().equalsIgnoreCase(name.strip())) {
                return level;
            }
        }
        return null;
    }
}
