package net.onelitefeather.blackhole.backend.intel;

/** Looks up what network an IP address belongs to. Implementations never make a network request. */
public interface IpEnricher {

    IpEnrichment enrich(String ip);

    /** The autonomous system and the country of an address; every field is null when unknown. */
    record IpEnrichment(Long asn, String asOrg, String countryCode) {

        public static final IpEnrichment NONE = new IpEnrichment(null, null, null);
    }
}
