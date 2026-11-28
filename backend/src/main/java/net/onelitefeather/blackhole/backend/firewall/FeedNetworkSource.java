package net.onelitefeather.blackhole.backend.firewall;

import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.util.List;

/**
 * A source of networks for the networks feed, besides the CIDR IP bans. Each source names its kind for the comment line
 * of its entries; no source writes any name, owner or reason into a comment.
 */
public interface FeedNetworkSource {

    /** @return the networks this source contributes right now; an empty list when it has none */
    List<IntelPrefix> networks();

    /**
     * @return a value that is equal for as long as {@link #networks()} would return the same networks. The feed cache
     * rebuilds only when this changes, so it must be cheap to read and must change whenever the networks do.
     */
    Object version();

    /** @return the category shown in a comment line, such as {@code LIST} or {@code HOSTING_ASN} */
    String kind();
}
