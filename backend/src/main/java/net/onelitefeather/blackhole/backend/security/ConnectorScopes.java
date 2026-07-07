package net.onelitefeather.blackhole.backend.security;

import java.util.Set;

/**
 * Scopes a connector can be granted. A connector's granted scopes are placed directly into its
 * minted JWT's role list (see {@code ConnectorAuthController}), so existing {@code @Secured}
 * checks can reference a scope string exactly like a role - no separate scope-checking
 * infrastructure needed.
 */
public final class ConnectorScopes {

    public static final String PUNISHMENT_READ = "punishment:read";
    public static final String REPORT_READ = "report:read";
    public static final String PROFILE_READ = "profile:read";
    public static final String SIGNAL_WRITE = "signal:write";
    public static final String EVENTS_SUBSCRIBE = "events:subscribe";

    public static final Set<String> ALL = Set.of(PUNISHMENT_READ, REPORT_READ, PROFILE_READ, SIGNAL_WRITE, EVENTS_SUBSCRIBE);

    private ConnectorScopes() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
