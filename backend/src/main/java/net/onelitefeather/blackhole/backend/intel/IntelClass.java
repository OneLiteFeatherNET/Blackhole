package net.onelitefeather.blackhole.backend.intel;

/** What a login address is, as far as the offline lists and the hosting ASN list can tell. */
public enum IntelClass {
    /** Known VPN provider network. */
    VPN,
    /** Datacenter or hosting network, from a configured ASN or a list of datacenter ranges. */
    HOSTING,
    /** Open proxy network. Reserved until a source for it is chosen (openspec design, open question 4). */
    PROXY
}
