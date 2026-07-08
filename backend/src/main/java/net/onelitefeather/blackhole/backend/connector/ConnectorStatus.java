package net.onelitefeather.blackhole.backend.connector;

/**
 * Whether a registered connector may currently authenticate/receive webhook deliveries.
 */
public enum ConnectorStatus {

    ACTIVE,
    SUSPENDED
}
