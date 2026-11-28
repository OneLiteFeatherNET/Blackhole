package net.onelitefeather.blackhole.velocity.config;

/**
 * The startup settings ({@code startup} in {@code config.json}). {@code denyUnenforcedLogins} decides what a
 * proxy whose initialisation failed does with players: refused with a maintenance message (the default, since
 * nothing would check their bans), or let in without any enforcement.
 */
public final class StartupConfig {

    private Boolean denyUnenforcedLogins;

    /**
     * @return whether non-staff logins are refused while nothing is enforced; {@code true} when the setting is absent
     */
    public boolean isDenyUnenforcedLogins() {
        return denyUnenforcedLogins == null || denyUnenforcedLogins;
    }

    public void setDenyUnenforcedLogins(boolean denyUnenforcedLogins) {
        this.denyUnenforcedLogins = denyUnenforcedLogins;
    }
}
