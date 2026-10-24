package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueEntry;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Counts how many distinct players showed each brand and channel, for the rule editor's suggestions.
 * A value is listed only when at least {@code minPlayers} different players showed it inside the window,
 * so a rare brand or channel that could identify one person is never reported. The counting needs the
 * owner hashes; they live in this proxy's memory for at most the window and are never sent anywhere:
 * the snapshot holds names and counts only. Values that the backend would refuse (characters outside
 * {@code a-z 0-9 . _ : / @ + -}) and the {@code minecraft:} channels (every client has them) are never counted.
 */
public final class TraitTally {

    private static final Pattern NAME = Pattern.compile("[a-z0-9._:/@+-]{1,128}");

    /** What is reported: the brands and channels that passed the minimum. */
    public record Snapshot(@NotNull List<ClientRuleCatalogueEntry> brands, @NotNull List<ClientRuleCatalogueEntry> channels) {
    }

    private final Clock clock;
    private final Duration window;
    private final Map<String, Map<String, Instant>> brands = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Instant>> channels = new ConcurrentHashMap<>();

    public TraitTally(@NotNull Clock clock, @NotNull Duration window) {
        this.clock = clock;
        this.window = window;
    }

    /** Notes the traits one player currently shows. */
    public void seen(@NotNull String owner, @NotNull ClientTraits traits) {
        Instant now = this.clock.instant();
        if (traits.brand() != null) {
            note(this.brands, traits.brand(), owner, now);
        }
        traits.channels().stream().filter(channel -> !channel.toLowerCase(Locale.ROOT).startsWith("minecraft:"))
                .forEach(channel -> note(this.channels, channel, owner, now));
    }

    /** Drops what is older than the window and returns the values shown by at least {@code minPlayers} players. */
    public @NotNull Snapshot snapshot(int minPlayers) {
        Instant oldest = this.clock.instant().minus(this.window);
        return new Snapshot(count(this.brands, oldest, minPlayers), count(this.channels, oldest, minPlayers));
    }

    private static void note(Map<String, Map<String, Instant>> tally, String value, String owner, Instant now) {
        String name = value.strip().toLowerCase(Locale.ROOT);
        if (NAME.matcher(name).matches()) {
            tally.computeIfAbsent(name, key -> new ConcurrentHashMap<>()).put(owner, now);
        }
    }

    private static List<ClientRuleCatalogueEntry> count(Map<String, Map<String, Instant>> tally, Instant oldest, int minPlayers) {
        Map<String, Integer> counted = new TreeMap<>();
        tally.entrySet().removeIf(entry -> {
            entry.getValue().values().removeIf(seen -> seen.isBefore(oldest));
            return entry.getValue().isEmpty();
        });
        tally.forEach((name, owners) -> {
            if (owners.size() >= minPlayers) {
                counted.put(name, owners.size());
            }
        });
        List<ClientRuleCatalogueEntry> entries = new ArrayList<>();
        counted.forEach((name, players) -> entries.add(new ClientRuleCatalogueEntry().name(name).players(players)));
        return entries;
    }
}
