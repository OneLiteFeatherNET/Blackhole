package net.onelitefeather.blackhole.velocity.redis;

/**
 * Mirrors the backend's {@code net.onelitefeather.blackhole.backend.punishment.RedisTopology}
 * byte-for-byte - the channel names and key formats are a hand-shared contract between the
 * backend (the sole writer) and every Velocity proxy (a reader only).
 */
public final class RedisTopology {

    static final String PUNISHMENT_SYNC_CHANNEL = "blackhole.punishment.sync";

    /** Announces that the section list changed; the message is the changed section key. */
    static final String SECTION_CHANGED_CHANNEL = "blackhole.section.changed";

    /**
     * The backend's full punishment template list in the shared cache, which the backend
     * refreshes whenever it lists templates. Read here for tab completion only.
     */
    static final String TEMPLATES_ALL_KEY = "blackhole:cache:v1:templates:all";

    /** The backend's full section list in the shared cache. */
    static final String SECTIONS_ALL_KEY = "blackhole:cache:v1:sections:all";

    /**
     * Key holding the active punishment of {@code owner} in {@code section}.
     */
    public static String punishKey(String section, String owner) {
        return "blackhole:punish:" + section + ":" + owner;
    }

    private RedisTopology() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
