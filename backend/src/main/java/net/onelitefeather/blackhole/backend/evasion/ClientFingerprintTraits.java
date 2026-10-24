package net.onelitefeather.blackhole.backend.evasion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The client traits behind a fingerprint, normalised, and their canonical string. Pure: no key, no
 * storage. The protocol version is deliberately not part of it (it changes with every client update and
 * would split one person into many).
 *
 * <p>Canonical form, versioned and unambiguous (every value is length-prefixed in UTF-8 bytes, so values
 * cannot run into each other):</p>
 * <pre>
 * v1
 * brand:&lt;len&gt;:&lt;brand&gt;
 * channels:&lt;count&gt;:(&lt;len&gt;:&lt;channel&gt;)*
 * mods:&lt;count&gt;:(&lt;len&gt;:&lt;id@version&gt;)*
 * </pre>
 * Lines are joined with {@code \n}, there is no trailing newline.
 */
public final class ClientFingerprintTraits {

    /** The canonical form and key generation; stored next to every fingerprint. */
    public static final String SCHEME = "v1";

    public static final int MAX_BRAND_LENGTH = 64;
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_ENTRY_LENGTH = 128;

    /** Brands that, with no channel and no mod, say nothing about the client. */
    private static final Set<String> GENERIC_BRANDS = Set.of("", "vanilla", "fabric", "forge", "neoforge");

    private final String brand;
    private final List<String> channels;
    private final List<String> mods;

    private ClientFingerprintTraits(String brand, List<String> channels, List<String> mods) {
        this.brand = brand;
        this.channels = channels;
        this.mods = mods;
    }

    /**
     * Normalises raw traits: the brand is trimmed, lower-cased and stripped of control characters; channels
     * and mods are trimmed, lower-cased, de-duplicated and sorted, entries that are empty, too long or hold
     * control characters are dropped, and the {@code minecraft:} namespace is dropped from the channels
     * (every client registers those).
     *
     * @return the traits, or empty when a list or the brand is oversized
     */
    public static Optional<ClientFingerprintTraits> of(String brand, List<String> channels, List<String> mods) {
        String normalisedBrand = brand == null ? "" : stripControl(brand).strip().toLowerCase(Locale.ROOT);
        if (normalisedBrand.length() > MAX_BRAND_LENGTH
                || (channels != null && channels.size() > MAX_ENTRIES) || (mods != null && mods.size() > MAX_ENTRIES)) {
            return Optional.empty();
        }
        List<String> normalisedChannels = clean(channels).stream().filter(channel -> !channel.startsWith("minecraft:")).toList();
        return Optional.of(new ClientFingerprintTraits(normalisedBrand, normalisedChannels, clean(mods)));
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String entry = value.strip().toLowerCase(Locale.ROOT);
            if (!entry.isEmpty() && entry.length() <= MAX_ENTRY_LENGTH && stripControl(entry).equals(entry)) {
                sorted.add(entry);
            }
        }
        return List.copyOf(sorted);
    }

    private static String stripControl(String value) {
        return value.replaceAll("\\p{Cntrl}", "");
    }

    /**
     * Whether the traits carry no information: no channel, no mod, and a brand that every plain client of
     * that family sends (or none). Generic fingerprints are never stored.
     */
    public boolean isGeneric() {
        return this.channels.isEmpty() && this.mods.isEmpty() && GENERIC_BRANDS.contains(this.brand);
    }

    public String canonical() {
        StringBuilder text = new StringBuilder("v1\nbrand:");
        append(text, this.brand);
        text.append("\nchannels:").append(this.channels.size()).append(':');
        this.channels.forEach(channel -> append(text, channel));
        text.append("\nmods:").append(this.mods.size()).append(':');
        this.mods.forEach(mod -> append(text, mod));
        return text.toString();
    }

    private static void append(StringBuilder text, String value) {
        text.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    // The accessors exist for tests and the sightings; nothing returns them over the API.
    String brand() {
        return this.brand;
    }

    List<String> channels() {
        return this.channels;
    }

    List<String> mods() {
        return this.mods;
    }

    @Override
    public String toString() {
        return "ClientFingerprintTraits[***]";
    }
}
