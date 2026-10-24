package net.onelitefeather.blackhole.velocity.clientrule;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The traits of the players online on this proxy, keyed by the hashed owner. The brand, channel and mod
 * events arrive in any order and at any time (also after the first server connect), and may even come
 * before the login event has been seen, so every update creates the entry when it is missing. An entry
 * is dropped when the player disconnects; nothing is persisted.
 */
public final class TraitCollector {

    private final Map<String, ClientTraits> traits = new ConcurrentHashMap<>();

    /** Records the protocol version the proxy observed at login. */
    public @NotNull ClientTraits loggedIn(@NotNull String owner, int protocol) {
        return update(owner, current -> current.withProtocol(protocol));
    }

    public @NotNull ClientTraits brand(@NotNull String owner, String brand) {
        return update(owner, current -> current.withBrand(brand));
    }

    public @NotNull ClientTraits channels(@NotNull String owner, @NotNull Collection<String> channels) {
        return update(owner, current -> current.withChannels(channels));
    }

    public @NotNull ClientTraits mods(@NotNull String owner, @NotNull Collection<String> mods) {
        return update(owner, current -> current.withMods(mods));
    }

    /** @return the traits known so far, empty if the player is unknown or already gone */
    public @NotNull Optional<ClientTraits> traits(@NotNull String owner) {
        return Optional.ofNullable(this.traits.get(owner));
    }

    /** Drops everything known about the player. */
    public void forget(@NotNull String owner) {
        this.traits.remove(owner);
    }

    /** @return how many players have an entry */
    public int size() {
        return this.traits.size();
    }

    private ClientTraits update(String owner, UnaryOperator<ClientTraits> change) {
        return this.traits.compute(owner, (key, current) -> change.apply(current == null ? ClientTraits.unknown() : current));
    }
}
