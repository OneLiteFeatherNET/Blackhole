package net.onelitefeather.blackhole.backend.dto;

/**
 * Whether a registered connector may currently authenticate/receive webhook deliveries.
 */
public enum ConnectorStatus {

    ACTIVE,
    SUSPENDED
}
