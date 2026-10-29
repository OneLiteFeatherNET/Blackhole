package net.onelitefeather.blackhole.backend.firewall;

import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Turns entries into the text of a feed. It sorts them (IPv4 before IPv6, then by address), writes each network once,
 * drops every line the protected check refuses, and writes an optional comment line before each entry. The same entries
 * always give the same bytes: LF line endings, ASCII only, no trailing spaces.
 */
public final class FeedRenderer {

    /**
     * One entry of a feed.
     *
     * @param network   the canonical network
     * @param kind      the category shown in the comment, such as {@code PANEL} or {@code VPN}
     * @param banId     the ban identifier, or null for a list entry
     * @param expiresAt epoch milliseconds, or null for an entry that never expires
     */
    public record Entry(IntelPrefix network, String kind, String banId, Long expiresAt) {
    }

    /**
     * @param text    the feed body
     * @param refused the number of entries the protected check refused
     */
    public record Output(String text, int refused) {
    }

    private static final Comparator<IntelPrefix> ORDER = (a, b) -> {
        int family = Boolean.compare(!a.ipv4(), !b.ipv4());
        if (family != 0) {
            return family;
        }
        int hi = Long.compareUnsigned(a.hi(), b.hi());
        if (hi != 0) {
            return hi;
        }
        int lo = Long.compareUnsigned(a.lo(), b.lo());
        return lo != 0 ? lo : Integer.compare(a.length(), b.length());
    };

    private FeedRenderer() {
    }

    /**
     * @param entries      the entries, in any order
     * @param singles      true to write bare addresses (the single-address feed), false to write CIDR networks
     * @param comments     true to write a comment line before each entry
     * @param isProtected  true for a network that must never reach a feed
     */
    public static Output render(Collection<Entry> entries, boolean singles, boolean comments, Predicate<IntelPrefix> isProtected) {
        TreeMap<IntelPrefix, Entry> byNetwork = new TreeMap<>(ORDER);
        int refused = 0;
        for (Entry entry : entries) {
            if (isProtected.test(entry.network())) {
                refused++;
            } else {
                byNetwork.putIfAbsent(entry.network(), entry);
            }
        }
        StringBuilder text = new StringBuilder();
        for (Entry entry : byNetwork.values()) {
            if (comments) {
                text.append("# ").append(comment(entry)).append('\n');
            }
            IntelPrefix network = entry.network();
            text.append(singles ? network.address() : network.address() + "/" + network.length()).append('\n');
        }
        return new Output(text.toString(), refused);
    }

    /** The body as bytes; ASCII only, so the length and the ETag are the same on every platform. */
    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** The comment text without the leading {@code # }: the id (when there is one), the kind and the expiry. Never a name. */
    static String comment(Entry entry) {
        String expiry = entry.expiresAt() == null ? "never" : Instant.ofEpochMilli(entry.expiresAt()).toString();
        String id = entry.banId() == null ? "" : "id=" + entry.banId() + " ";
        return id + "kind=" + entry.kind() + " expires=" + expiry;
    }
}
