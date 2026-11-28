package net.onelitefeather.blackhole.backend.ipban;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ranges that may never be banned: loopback, link-local, private and unique-local networks, plus the ranges the
 * operator lists in {@code blackhole.ipban.protected-networks}. Shared by the service and the firewall adapter, so a
 * bad row cannot reach the firewall either.
 */
@Singleton
public class IpBanProtection {

    private static final List<String> BUILT_IN = List.of(
            "127.0.0.0/8", "169.254.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "::1/128", "fe80::/10", "fc00::/7");

    private final List<IntelPrefix> networks;

    /**
     * @param configured comma-separated CIDR ranges from configuration; empty adds nothing
     * @throws IllegalStateException when a configured entry is not a CIDR range (the application fails to start)
     */
    public IpBanProtection(@Value("${blackhole.ipban.protected-networks:}") String configured) {
        List<IntelPrefix> all = new ArrayList<>();
        for (String text : BUILT_IN) {
            all.add(IntelPrefix.parseCidr(text).orElseThrow());
        }
        if (configured != null) {
            for (String entry : configured.split(",")) {
                String trimmed = entry.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                IntelPrefix parsed = IntelPrefix.parseCidr(trimmed).orElseThrow(() ->
                        new IllegalStateException("blackhole.ipban.protected-networks has an entry that is not a CIDR range"));
                all.add(parsed);
            }
        }
        this.networks = List.copyOf(all);
    }

    /** @return the protected network the candidate overlaps, or empty when it overlaps none */
    public Optional<IntelPrefix> overlapping(IntelPrefix candidate) {
        return this.networks.stream().filter(protectedNetwork -> overlaps(protectedNetwork, candidate)).findFirst();
    }

    /** Two networks overlap when one contains the other. */
    static boolean overlaps(IntelPrefix a, IntelPrefix b) {
        return contains(a, b) || contains(b, a);
    }

    /** @return true when {@code outer} contains every address of {@code inner} */
    static boolean contains(IntelPrefix outer, IntelPrefix inner) {
        return outer.ipv4() == inner.ipv4() && outer.length() <= inner.length()
                && inner.withLength(outer.length()).equals(outer);
    }
}
