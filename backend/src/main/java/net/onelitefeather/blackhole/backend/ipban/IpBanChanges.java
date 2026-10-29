package net.onelitefeather.blackhole.backend.ipban;

/**
 * Told after every IP ban write, so the firewall feeds are rebuilt at once instead of at their next interval. Implemented
 * by the feed cache; the service does not know the firewall.
 */
public interface IpBanChanges {

    void changed();
}
