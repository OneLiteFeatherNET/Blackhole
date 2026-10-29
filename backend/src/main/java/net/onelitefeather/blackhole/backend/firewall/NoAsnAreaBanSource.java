package net.onelitefeather.blackhole.backend.firewall;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.util.List;

/** The placeholder until join guard merges: no ASN area bans, so this source adds no networks to the feed. */
@Singleton
public class NoAsnAreaBanSource implements AsnAreaBanSource {

    @Override
    public List<IntelPrefix> networks() {
        return List.of();
    }

    @Override
    public Object version() {
        return List.of();
    }
}
