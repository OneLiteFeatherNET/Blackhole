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
     * Announces a new report to the staff of every proxy. Unlike the channels above this one is written by
     * the proxy that took the report (the backend's events carry hashes only, no names) and is never stored.
     */
    static final String REPORT_CREATED_CHANNEL = "blackhole.report.created";

    /** Announces that the client rules changed; the message is the changed rule key. Written by the backend. */
    static final String CLIENT_RULE_CHANGED_CHANNEL = "blackhole.clientrule.changed";

    /**
     * Announces a client rule hit to the staff of every proxy. Written by the proxy that saw the hit (the notice carries the
     * player's name, which the backend never gets) and never stored.
     */
    static final String CLIENT_RULE_HIT_CHANNEL = "blackhole.clientrule.hit";

    /** Announces that a reporter has report feedback waiting; the message is the reporter's owner hash. */
    static final String REPORT_FEEDBACK_CHANNEL = "blackhole.report.feedback";

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
