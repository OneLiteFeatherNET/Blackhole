package net.onelitefeather.blackhole.velocity.config;

/**
 * The bound of the proxy-local punishment mirror ({@code mirror} in {@code config.json}): the most players
 * whose state is kept at once. Offline players are evicted first, so the bound only costs a backend read
 * for a player who is no longer connected.
 */
public final class MirrorConfig {

    /** The bound when the setting is absent or below one. */
    static final int DEFAULT_MAX_OWNERS = 4096;

    private Integer maxOwners;

    /**
     * @return the most players the mirror keeps; {@value #DEFAULT_MAX_OWNERS} when the setting is absent or below one
     */
    public int getMaxOwners() {
        return maxOwners == null || maxOwners < 1 ? DEFAULT_MAX_OWNERS : maxOwners;
    }

    public void setMaxOwners(int maxOwners) {
        this.maxOwners = maxOwners;
    }
}
