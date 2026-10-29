package net.onelitefeather.blackhole.backend.ipban;

/**
 * Told after every IP ban write, so the firewall reconciler runs soon instead of at its next interval. Implemented by
 * the firewall adapter; the service does not know the firewall.
 */
public interface IpBanChanges {

    void changed();
}
