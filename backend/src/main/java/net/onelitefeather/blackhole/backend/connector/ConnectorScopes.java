package net.onelitefeather.blackhole.backend.connector;

import java.util.Set;

/**
 * Scopes a connector can be granted, declaring what a registered connector is intended to be
 * allowed to do. Purely descriptive metadata for now - there is no authentication system to
 * enforce these against a caller (see the no-auth trust-model note in {@code application.yml}),
 * so this only validates that a connector registration requests a known scope value.
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
