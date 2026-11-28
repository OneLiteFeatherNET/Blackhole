package net.onelitefeather.blackhole.backend.intel;

import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Accepts only what is written as an IPv4 or IPv6 address, so parsing never resolves a host name.
 */
final class IpLiteral {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private IpLiteral() {
    }

    static Optional<InetAddress> parse(String ip) {
        if (ip == null || !(IPV4.matcher(ip).matches() || (ip.contains(":") && IPV6.matcher(ip).matches()))) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(ip));
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
