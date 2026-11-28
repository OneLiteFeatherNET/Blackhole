package net.onelitefeather.blackhole.backend.intel;

import java.util.List;
import java.util.Set;

/**
 * The networks of autonomous systems in the offline ASN file. Walking the file is expensive, so a caller caches the result
 * against {@link #version()}, which changes when the file is replaced.
 */
public interface AsnNetworks {

    /** @return a value that changes when the ASN file is replaced; equal values mean the same file */
    long version();

    /** @return every network of the given autonomous systems; empty when the file is absent */
    List<IntelPrefix> networksOfAsns(Set<Long> asns);
}
