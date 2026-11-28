package net.onelitefeather.blackhole.backend.intel;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A network, IPv4 or IPv6, with its host bits cleared. IPv4 uses the low 32 bits of {@code lo}; IPv6 uses both halves.
 * IPv4-mapped IPv6 addresses (::ffff:a.b.c.d) are stored as the IPv4 network they map to.
 */
public record IntelPrefix(boolean ipv4, long hi, long lo, int length) {

    private static final Pattern PREFIX_LENGTH = Pattern.compile("\\d{1,3}");

    public IntelPrefix {
        int max = ipv4 ? 32 : 128;
        if (length < 0 || length > max) {
            throw new IllegalArgumentException("prefix length must be between 0 and " + max + ": " + length);
        }
        if (ipv4) {
            hi = 0;
            lo = lo & ipv4Mask(length) & 0xFFFF_FFFFL;
        } else {
            hi = hi & highMask(length);
            lo = lo & lowMask(length);
        }
    }

    /**
     * @return the network of a CIDR line such as {@code 198.51.100.0/24} or {@code 2001:db8::/32}, or empty when the
     * text is not an address followed by a prefix length in range. A host name is never resolved.
     */
    public static Optional<IntelPrefix> parseCidr(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int slash = text.indexOf('/');
        if (slash < 0 || text.indexOf('/', slash + 1) >= 0) {
            return Optional.empty();
        }
        String prefixText = text.substring(slash + 1);
        if (!PREFIX_LENGTH.matcher(prefixText).matches()) {
            return Optional.empty();
        }
        Optional<InetAddress> address = IpLiteral.parse(text.substring(0, slash));
        if (address.isEmpty()) {
            return Optional.empty();
        }
        int length = Integer.parseInt(prefixText);
        InetAddress literal = address.get();
        byte[] bytes = literal.getAddress();
        if (bytes.length == 4) {
            return length <= 32 ? Optional.of(new IntelPrefix(true, 0, toLong(bytes, 0, 4), length)) : Optional.empty();
        }
        if (isMapped(literal)) {
            // ::ffff:a.b.c.d/(96 + n) is the IPv4 network a.b.c.d/n; a shorter mapped prefix is not an IPv4 network.
            return length >= 96 ? Optional.of(new IntelPrefix(true, 0, toLong(bytes, 12, 4), length - 96)) : Optional.empty();
        }
        return length <= 128 ? Optional.of(new IntelPrefix(false, toLong(bytes, 0, 8), toLong(bytes, 8, 8), length)) : Optional.empty();
    }

    /** @return the full-length network of one address: a /32 for IPv4 (and IPv4-mapped) addresses, a /128 otherwise */
    public static IntelPrefix ofAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) {
            return new IntelPrefix(true, 0, toLong(bytes, 0, 4), 32);
        }
        if (isMapped(address)) {
            return new IntelPrefix(true, 0, toLong(bytes, 12, 4), 32);
        }
        return new IntelPrefix(false, toLong(bytes, 0, 8), toLong(bytes, 8, 8), 128);
    }

    /** @return the network of the same address and family with another prefix length */
    public IntelPrefix withLength(int newLength) {
        return new IntelPrefix(this.ipv4, this.hi, this.lo, newLength);
    }

    /** @return the canonical text: dotted IPv4, or eight four-digit IPv6 groups, each followed by the length */
    @Override
    public String toString() {
        if (this.ipv4) {
            return ((this.lo >>> 24) & 0xFF) + "." + ((this.lo >>> 16) & 0xFF) + "." + ((this.lo >>> 8) & 0xFF) + "."
                    + (this.lo & 0xFF) + "/" + this.length;
        }
        StringBuilder text = new StringBuilder();
        for (int group = 0; group < 4; group++) {
            text.append(String.format("%04x:", (this.hi >>> (48 - 16 * group)) & 0xFFFF));
        }
        for (int group = 0; group < 4; group++) {
            text.append(String.format("%04x", (this.lo >>> (48 - 16 * group)) & 0xFFFF));
            if (group < 3) {
                text.append(':');
            }
        }
        return text.append('/').append(this.length).toString();
    }

    /**
     * @return the address alone, without the length: dotted IPv4, or IPv6 in the RFC 5952 compressed form (the longest run
     * of two or more zero groups becomes {@code ::}), for example {@code 2001:db8::7}
     */
    public String address() {
        if (this.ipv4) {
            return ((this.lo >>> 24) & 0xFF) + "." + ((this.lo >>> 16) & 0xFF) + "." + ((this.lo >>> 8) & 0xFF) + "."
                    + (this.lo & 0xFF);
        }
        int[] groups = new int[8];
        for (int group = 0; group < 4; group++) {
            groups[group] = (int) ((this.hi >>> (48 - 16 * group)) & 0xFFFF);
            groups[group + 4] = (int) ((this.lo >>> (48 - 16 * group)) & 0xFFFF);
        }
        int runStart = -1;
        int runLength = 0;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int end = i;
            while (end < 8 && groups[end] == 0) {
                end++;
            }
            if (end - i > runLength) {
                runStart = i;
                runLength = end - i;
            }
            i = end;
        }
        if (runLength < 2) {
            runStart = -1;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == runStart) {
                text.append("::");
                i += runLength - 1;
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
    }

    private static boolean isMapped(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static long toLong(byte[] bytes, int from, int count) {
        long value = 0;
        for (int i = from; i < from + count; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }

    private static long ipv4Mask(int length) {
        return length == 0 ? 0 : 0xFFFF_FFFFL << (32 - length);
    }

    private static long highMask(int length) {
        if (length == 0) {
            return 0;
        }
        return length >= 64 ? -1L : -1L << (64 - length);
    }

    private static long lowMask(int length) {
        if (length <= 64) {
            return 0;
        }
        return length == 128 ? -1L : -1L << (128 - length);
    }
}
