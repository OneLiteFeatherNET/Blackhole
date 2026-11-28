package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The source addresses that may read a feed, from {@code BLACKHOLE_FIREWALL_FEED_ALLOWED_SOURCES}: IP addresses and CIDR
 * ranges only. A host name is refused at start-up, because it would be resolved on every request. An empty list admits
 * every peer. The peer is the TCP peer; forwarded headers are never read.
 */
@Singleton
public class FeedAllowList {

    private final List<IntelPrefix> networks;

    public FeedAllowList(@Value("${blackhole.firewall.feed.allowed-sources:}") String sources) {
        this.networks = parse(sources);
    }

    /** @return true when the peer may read a feed; always true when the list is empty */
    public boolean allows(InetAddress peer) {
        if (this.networks.isEmpty()) {
            return true;
        }
        if (peer == null) {
            return false;
        }
        IntelPrefix address = IntelPrefix.ofAddress(peer);
        for (IntelPrefix allowed : this.networks) {
            if (allowed.ipv4() == address.ipv4() && address.withLength(allowed.length()).equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    static List<IntelPrefix> parse(String csv) {
        List<IntelPrefix> parsed = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return parsed;
        }
        for (String raw : csv.split(",")) {
            String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
            String cidr = entry.contains("/") ? entry : entry + "/" + (entry.contains(":") ? 128 : 32);
            IntelPrefix prefix = IntelPrefix.parseCidr(cidr).orElseThrow(() -> new IllegalStateException(
                    "BLACKHOLE_FIREWALL_FEED_ALLOWED_SOURCES takes IP addresses and CIDR ranges only; refused: " + entry));
            parsed.add(prefix);
        }
        return List.copyOf(parsed);
    }
}
