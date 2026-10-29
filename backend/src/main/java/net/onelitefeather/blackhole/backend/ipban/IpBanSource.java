package net.onelitefeather.blackhole.backend.ipban;

/** Where an IP ban came from. Stored by name. */
public enum IpBanSource {
    /** Created in the panel. */
    PANEL,
    /** Created with the {@code /ipban} command on the proxy. */
    COMMAND,
    /** Created together with a network ban ("also ban last IP"). */
    PUNISHMENT,
    /** Imported from a vanilla {@code banned-ips.json}. */
    IMPORT
}
