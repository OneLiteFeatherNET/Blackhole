package net.onelitefeather.blackhole.backend.firewall;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.areaban.AreaBan;
import net.onelitefeather.blackhole.backend.areaban.AreaBanCache;
import net.onelitefeather.blackhole.backend.intel.AsnNetworks;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The networks of the autonomous systems under an active ASN area ban, read from the in-memory ban snapshot. The walk of the
 * ASN file is cached against the file's version and the banned ASN set, as {@link HostingAsnFeedSource} does. The version
 * holds the ids of the bans that apply at this moment, so a ban written, lifted or expired changes it.
 */
@Singleton
public class AreaBanAsnFeedSource implements AsnAreaBanSource {

    /** The inputs of a cached walk. */
    private record Key(long asnFileVersion, Set<Long> asns) {
    }

    private final AreaBanCache bans;
    private final AsnNetworks asnNetworks;
    private final Clock clock;
    private Key cachedKey;
    private List<IntelPrefix> cachedNetworks = List.of();

    public AreaBanAsnFeedSource(AreaBanCache bans, AsnNetworks asnNetworks, Clock clock) {
        this.bans = bans;
        this.asnNetworks = asnNetworks;
        this.clock = clock;
    }

    @Override
    public synchronized List<IntelPrefix> networks() {
        Set<Long> asns = activeAsns();
        if (asns.isEmpty()) {
            return List.of();
        }
        Key key = new Key(this.asnNetworks.version(), asns);
        if (!key.equals(this.cachedKey)) {
            this.cachedNetworks = List.copyOf(this.asnNetworks.networksOfAsns(asns));
            this.cachedKey = key;
        }
        return this.cachedNetworks;
    }

    @Override
    public Object version() {
        Set<UUID> ids = activeAsnBans().stream().map(AreaBan::id).collect(Collectors.toUnmodifiableSet());
        return List.of(ids, this.asnNetworks.version());
    }

    private Set<Long> activeAsns() {
        return activeAsnBans().stream().map(ban -> Long.parseLong(ban.value())).collect(Collectors.toUnmodifiableSet());
    }

    private List<AreaBan> activeAsnBans() {
        return this.bans.active(this.clock.millis()).stream().filter(ban -> ban.kind() == AreaBan.Kind.ASN).toList();
    }
}
