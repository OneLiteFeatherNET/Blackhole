package net.onelitefeather.blackhole.backend.punishment;

/**
 * Well-known Redis channel/key names shared between {@link PunishmentRedisWriter} and every
 * Velocity proxy's read-side mirror - the key/channel format here must be replicated
 * byte-for-byte on the Velocity side.
 *
 * <p><b>Deliberately not env-var configurable</b> (unlike this project's usual
 * {@code blackhole.*} tunables convention - see {@code application.yml}): these strings are a
 * wire protocol between two independently-deployed and independently-configured processes (this
 * backend and every Velocity proxy instance), not a single-side tunable. Making them
 * configurable per-side would let the backend and a proxy silently drift onto mismatched
 * keys/channels - a much worse failure mode (proxies quietly never see punishment updates) than
 * the inflexibility of a fixed constant. If this ever needs to vary, it should be a single value
 * distributed to both sides from one source, not two independent config surfaces
 * (specs/002-punishment-core/tasks.md T031).</p>
 */
public final class RedisTopology {

    /** Pub/Sub channel carrying every {@link PunishmentSyncMessage} delta. */
    public static final String PUNISHMENT_SYNC_CHANNEL = "blackhole.punishment.sync";

    /** Pub/Sub channel announcing that the section list changed; the message is the changed section key. */
    public static final String SECTION_CHANGED_CHANNEL = "blackhole.section.changed";

    /** Pub/Sub channel announcing that the client rules changed; the message is the changed rule key. */
    public static final String CLIENT_RULE_CHANGED_CHANNEL = "blackhole.clientrule.changed";

    /** Pub/Sub channel announcing that a reporter has report feedback waiting; the message is the reporter's owner hash. */
    public static final String REPORT_FEEDBACK_CHANNEL = "blackhole.report.feedback";

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
