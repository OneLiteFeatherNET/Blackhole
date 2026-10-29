package net.onelitefeather.blackhole.backend.publicbans.auth;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decides whether a ban-list request arrived over HTTPS. The scheme is the request's own, or {@code X-Forwarded-Proto} when
 * the request comes from a configured ingress address or CIDR ({@code BLACKHOLE_BAN_LIST_TRUSTED_INGRESS}). Any other
 * address cannot speak for the scheme.
 */
@Singleton
public class BanListHttpsGuard {

    private final List<Network> trusted = new ArrayList<>();

    @Inject
    public BanListHttpsGuard(@Value("${blackhole.ban-list.trusted-ingress:}") String trustedIngress) {
        if (trustedIngress != null) {
            for (String entry : trustedIngress.split(",")) {
                if (!entry.isBlank()) {
                    this.trusted.add(Network.parse(entry.strip()));
                }
            }
        }
    }

    public boolean isSecure(String scheme, String forwardedProto, InetAddress remote) {
        if ("https".equalsIgnoreCase(scheme)) {
            return true;
        }
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.strip()) && remote != null && trusts(remote);
    }

    private boolean trusts(InetAddress remote) {
        byte[] address = unmapped(remote.getAddress());
        return this.trusted.stream().anyMatch(network -> network.contains(address));
    }

    /** An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is the IPv4 address it stands for. */
    private static byte[] unmapped(byte[] address) {
        if (address.length == 16) {
            boolean mapped = address[10] == (byte) 0xff && address[11] == (byte) 0xff;
            for (int i = 0; i < 10; i++) {
                mapped &= address[i] == 0;
            }
            if (mapped) {
                return Arrays.copyOfRange(address, 12, 16);
            }
        }
        return address;
    }

    /** An address or a CIDR block of one address family. */
    private record Network(byte[] address, int prefix) {

        static Network parse(String text) {
            int slash = text.indexOf('/');
            String literal = slash < 0 ? text : text.substring(0, slash);
            try {
                byte[] address = unmapped(InetAddress.getByName(literal).getAddress());
                int prefix = slash < 0 ? address.length * 8 : Integer.parseInt(text.substring(slash + 1));
                if (prefix < 0 || prefix > address.length * 8) {
                    throw new IllegalStateException("the trusted ingress entry has a prefix out of range");
                }
                return new Network(address, prefix);
            } catch (java.net.UnknownHostException | NumberFormatException e) {
                throw new IllegalStateException("BLACKHOLE_BAN_LIST_TRUSTED_INGRESS must hold IP addresses or CIDR blocks", e);
            }
        }

        boolean contains(byte[] other) {
            if (other.length != this.address.length) {
                return false;
            }
            int bits = this.prefix;
            for (int i = 0; i < this.address.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if (((this.address[i] ^ other[i]) & mask) != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
