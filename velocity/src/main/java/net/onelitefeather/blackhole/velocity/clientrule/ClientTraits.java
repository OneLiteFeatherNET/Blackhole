package net.onelitefeather.blackhole.velocity.clientrule;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the proxy knows about one connected client, as an immutable snapshot. Everything but the
 * protocol version is announced by the client and can be faked. Nothing here is ever persisted on the
 * proxy; it lives in the {@link TraitCollector} while the player is online.
 *
 * @param protocol the protocol version the proxy observed, {@code -1} while unknown
 * @param brand    the {@code minecraft:brand} string, null until it arrives (or if the client never sends one)
 * @param channels the plugin channel ids the client registered
 * @param mods     the legacy Forge mods as {@code id@version}
 */
public record ClientTraits(int protocol, @Nullable String brand, @NotNull Set<String> channels, @NotNull Set<String> mods) {

    /** A client cannot make the proxy hold more channels or mods than this per player. */
    static final int MAX_ENTRIES = 512;
    static final int MAX_LENGTH = 128;

    public ClientTraits {
        channels = Set.copyOf(channels);
        mods = Set.copyOf(mods);
    }

    /** A client of which nothing is known yet. */
    public static @NotNull ClientTraits unknown() {
        return new ClientTraits(-1, null, Set.of(), Set.of());
    }

    public @NotNull ClientTraits withProtocol(int protocol) {
        return new ClientTraits(protocol, this.brand, this.channels, this.mods);
    }

    public @NotNull ClientTraits withBrand(@Nullable String brand) {
        String bounded = brand == null || brand.length() <= MAX_LENGTH ? brand : brand.substring(0, MAX_LENGTH);
        return new ClientTraits(this.protocol, bounded, this.channels, this.mods);
    }

    public @NotNull ClientTraits withChannels(@NotNull Collection<String> added) {
        return new ClientTraits(this.protocol, this.brand, merged(this.channels, added), this.mods);
    }

    public @NotNull ClientTraits withMods(@NotNull Collection<String> added) {
        return new ClientTraits(this.protocol, this.brand, this.channels, merged(this.mods, added));
    }

    private static Set<String> merged(Set<String> current, Collection<String> added) {
        Set<String> all = new LinkedHashSet<>(current);
        for (String value : added) {
            if (all.size() >= MAX_ENTRIES) {
                break;
            }
            if (value != null && !value.isEmpty() && value.length() <= MAX_LENGTH) {
                all.add(value);
            }
        }
        return all;
    }

    /** Never prints a trait: it is a log-safe summary. */
    @Override
    public String toString() {
        return "ClientTraits[protocol=" + this.protocol + ", brand=" + (this.brand == null ? "unknown" : "set")
                + ", channels=" + this.channels.size() + ", mods=" + this.mods.size() + "]";
    }
}
