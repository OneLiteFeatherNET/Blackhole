package net.onelitefeather.blackhole.backend.ipban;

import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.util.Optional;

/**
 * A single address or a CIDR range as typed by staff, normalised to its network. A single address takes the full
 * prefix ({@code /32} or {@code /128}). Host bits of a CIDR are cleared; {@link #hostBitsCleared()} tells the caller
 * so it can warn. IPv4-mapped IPv6 literals are refused: the firewall and the proxy compare plain IPv4 addresses.
 */
public record IpBanNetwork(IntelPrefix network, boolean hostBitsCleared) {

    private static final String LENGTH = "\\d{1,3}";

    /**
     * @return the parsed range, or empty when the text is neither an address nor an address with a valid prefix
     * length. A host name is never resolved.
     */
    public static Optional<IpBanNetwork> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.strip();
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        boolean ipv6 = address.contains(":");
        Optional<IntelPrefix> literal = IntelPrefix.parseCidr(address + "/" + (ipv6 ? 128 : 32));
        if (literal.isEmpty() || (ipv6 && literal.get().ipv4())) {
            return Optional.empty();
        }
        IntelPrefix whole = literal.get();
        if (slash < 0) {
            return Optional.of(new IpBanNetwork(whole, false));
        }
        String lengthText = value.substring(slash + 1);
        if (!lengthText.matches(LENGTH)) {
            return Optional.empty();
        }
        int length = Integer.parseInt(lengthText);
        if (length > (whole.ipv4() ? 32 : 128)) {
            return Optional.empty();
        }
        IntelPrefix network = whole.withLength(length);
        boolean cleared = whole.hi() != network.hi() || whole.lo() != network.lo();
        return Optional.of(new IpBanNetwork(network, cleared));
    }

    /** @return the canonical text stored and compared, for example {@code 203.0.113.0/24} */
    public String range() {
        return this.network.toString();
    }

    /** @return the shortest prefix this range may have: {@code /16} for IPv4, {@code /32} for IPv6 */
    public static int minimumLength(IntelPrefix network) {
        return network.ipv4() ? 16 : 32;
    }
}
