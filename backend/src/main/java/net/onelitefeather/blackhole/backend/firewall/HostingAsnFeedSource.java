package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.AsnNetworks;
import net.onelitefeather.blackhole.backend.intel.IntelClass;
import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import net.onelitefeather.blackhole.backend.intel.IntelPolicyCache;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import net.onelitefeather.blackhole.backend.intel.ListReloader;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The networks of the configured hosting ASNs, from the offline GeoIP ASN file. Contributes nothing unless the HOSTING
 * policy is {@code DENY}, the same rule as the list classes. Walking the file is the expensive part, so the walk is cached
 * against the file's version and the ASN set: it runs again only when one of those changes.
 */
@Singleton
public class HostingAsnFeedSource implements FeedNetworkSource {

    /** The inputs of a cached walk. */
    private record Key(long asnFileVersion, Set<Long> asns) {
    }

    private final AsnNetworks asnNetworks;
    private final Supplier<IntelPolicy> hostingPolicy;
    private final Set<Long> hostingAsns;
    private Key cachedKey;
    private List<IntelPrefix> cachedNetworks = List.of();

    @Inject
    public HostingAsnFeedSource(AsnNetworks asnNetworks, IntelPolicyCache policies,
                                @Value("${blackhole.intel.hosting-asns:}") String hostingAsns) {
        this(asnNetworks, () -> policies.policyOf(IntelClass.HOSTING), ListReloader.parseAsns(hostingAsns));
    }

    HostingAsnFeedSource(AsnNetworks asnNetworks, Supplier<IntelPolicy> hostingPolicy, Set<Long> hostingAsns) {
        this.asnNetworks = asnNetworks;
        this.hostingPolicy = hostingPolicy;
        this.hostingAsns = Set.copyOf(hostingAsns);
    }

    @Override
    public synchronized List<IntelPrefix> networks() {
        if (this.hostingAsns.isEmpty() || this.hostingPolicy.get() != IntelPolicy.DENY) {
            return List.of();
        }
        Key key = new Key(this.asnNetworks.version(), this.hostingAsns);
        if (!key.equals(this.cachedKey)) {
            this.cachedNetworks = List.copyOf(this.asnNetworks.networksOfAsns(this.hostingAsns));
            this.cachedKey = key;
        }
        return this.cachedNetworks;
    }

    @Override
    public Object version() {
        return List.of(this.hostingPolicy.get(), this.asnNetworks.version());
    }

    @Override
    public String kind() {
        return "HOSTING_ASN";
    }
}
