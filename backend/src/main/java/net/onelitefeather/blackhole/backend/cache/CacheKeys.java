package net.onelitefeather.blackhole.backend.cache;

import java.util.Locale;
import java.util.UUID;

/**
 * Key namespace for the {@link SharedCache}. Every key carries the schema version segment, so a
 * deploy with a changed value shape ignores entries written by the previous version instead of
 * failing to deserialize them.
 *
 * <p>The proxy reads {@link #TEMPLATES_ALL} and {@link #SECTIONS_ALL} directly, so this format is a wire contract with the
 * Velocity plugin's {@code RedisTopology} and must stay in sync with it.</p>
 */
public final class CacheKeys {

    private static final String PREFIX = "blackhole:cache:v1:";

    /** The full punishment template list, as read by proxies for tab completion. */
    public static final String TEMPLATES_ALL = PREFIX + "templates:all";

    /** The full section list, as read by proxies. */
    public static final String SECTIONS_ALL = PREFIX + "sections:all";

    /** Glob matching every proxy's CloudNet catalogue snapshot. */
    public static final String CLOUDNET_CATALOGUE_PATTERN = PREFIX + "cloudnet:catalogue:*";

    /** One proxy's CloudNet catalogue snapshot; {@code proxyId} must not contain glob characters. */
    public static String cloudnetCatalogue(String proxyId) {
        return PREFIX + "cloudnet:catalogue:" + proxyId;
    }

    public static String section(String key) {
        return PREFIX + "section:" + key;
    }

    public static String profile(String owner) {
        return PREFIX + "profile:" + owner;
    }

    public static String elo(String owner) {
        return PREFIX + "elo:" + owner;
    }

    public static String template(UUID identifier) {
        return PREFIX + "template:" + identifier;
    }

    /** Keyed by the public player name, so only the name and the resolved public UUID are stored. */
    public static String player(String name) {
        return PREFIX + "player:" + name.toLowerCase(Locale.ROOT);
    }

    private CacheKeys() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
