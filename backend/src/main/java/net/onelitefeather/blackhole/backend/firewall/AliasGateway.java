package net.onelitefeather.blackhole.backend.firewall;

import java.util.Set;

/**
 * The firewall side of the IP ban alias. The reconciler works through this port; {@link OpnsenseAliasClient} is the
 * OPNsense adapter. Every failure is an {@link AliasException} naming the operation, never the address.
 */
public interface AliasGateway {

    /** @return the entries the alias holds, as the firewall reports them */
    Set<String> entries(String alias);

    void add(String alias, String range);

    void delete(String alias, String range);

    /** Applies the alias changes to the running firewall. */
    void reconfigure();
}
