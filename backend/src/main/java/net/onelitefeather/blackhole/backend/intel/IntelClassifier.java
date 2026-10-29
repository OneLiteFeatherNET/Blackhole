package net.onelitefeather.blackhole.backend.intel;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An immutable, in-memory classification of login addresses: the hosting ASNs and the networks of every enabled list
 * source. It does no I/O, so a login check never reads the database. Build a new one to change its data.
 */
public final class IntelClassifier {

    /** The source key of a match made by the hosting ASN list. */
    public static final String HOSTING_ASN_KEY = "asn:hosting";

    /** One listed network and the source it came from. */
    public record ListedPrefix(String sourceKey, IntelClass kind, IntelPrefix prefix) {
    }

    /** One class an address matched, and the source (or {@link #HOSTING_ASN_KEY}) that matched it. */
    public record Match(IntelClass kind, String sourceKey) {
    }

    private static final IntelClassifier EMPTY = new IntelClassifier(Set.of(), Map.of(), new int[0], new int[0]);

    private final Set<Long> hostingAsns;
    private final Map<IntelPrefix, List<Match>> networks;
    /** The prefix lengths present, longest first, so the first hit is the most specific one. */
    private final int[] ipv4Lengths;
    private final int[] ipv6Lengths;

    private IntelClassifier(Set<Long> hostingAsns, Map<IntelPrefix, List<Match>> networks, int[] ipv4Lengths, int[] ipv6Lengths) {
        this.hostingAsns = hostingAsns;
        this.networks = networks;
        this.ipv4Lengths = ipv4Lengths;
        this.ipv6Lengths = ipv6Lengths;
    }

    /** @return a classifier with no hosting ASNs and no networks: every address is clean */
    public static IntelClassifier empty() {
        return EMPTY;
    }

    /**
     * @param hostingAsns the AS numbers that are hosting networks
     * @param prefixes    every listed network of every enabled source
     */
    public static IntelClassifier of(Set<Long> hostingAsns, Collection<ListedPrefix> prefixes) {
        Map<IntelPrefix, List<Match>> networks = new HashMap<>();
        TreeSet<Integer> v4 = new TreeSet<>();
        TreeSet<Integer> v6 = new TreeSet<>();
        for (ListedPrefix listed : prefixes) {
            IntelPrefix prefix = listed.prefix();
            networks.computeIfAbsent(prefix, k -> new ArrayList<>()).add(new Match(listed.kind(), listed.sourceKey()));
            (prefix.ipv4() ? v4 : v6).add(prefix.length());
        }
        networks.replaceAll((prefix, matches) -> List.copyOf(new LinkedHashSet<>(matches)));
        return new IntelClassifier(Set.copyOf(hostingAsns), Map.copyOf(networks),
                v4.descendingSet().stream().mapToInt(Integer::intValue).toArray(),
                v6.descendingSet().stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * @param address the login address
     * @param asn     its autonomous system, or null when unknown
     * @return every class the address matched, each once; empty for a clean address. The hosting ASN match comes first.
     */
    public List<Match> classify(InetAddress address, Long asn) {
        Set<Match> matches = new LinkedHashSet<>();
        if (asn != null && this.hostingAsns.contains(asn)) {
            matches.add(new Match(IntelClass.HOSTING, HOSTING_ASN_KEY));
        }
        IntelPrefix full = IntelPrefix.ofAddress(address);
        int[] lengths = full.ipv4() ? this.ipv4Lengths : this.ipv6Lengths;
        for (int length : lengths) {
            List<Match> found = this.networks.get(full.withLength(length));
            if (found != null) {
                matches.addAll(found);
            }
        }
        return List.copyOf(matches);
    }
}
