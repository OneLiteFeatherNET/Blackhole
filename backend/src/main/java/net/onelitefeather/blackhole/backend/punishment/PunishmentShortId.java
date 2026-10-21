package net.onelitefeather.blackhole.backend.punishment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The short ID players quote in an appeal, for example {@code #A1B2C3D4}. It is {@code #} followed by
 * the upper case hex of the first four bytes of the punishment's UUID. The backend identifier is that
 * UUID as 22 character URL-safe Base64 ({@link net.onelitefeather.blackhole.backend.utils.IdGenerator});
 * a plain UUID string yields the same short ID. The Velocity plugin derives it the same way
 * ({@code PunishmentScreens.shortId}), so both sides must agree on the shared test vectors.
 *
 * <p>Short IDs are four bytes, so two punishments can share one; a lookup returns every match.
 *
 * <p>The <em>key</em> form (eight upper case hex characters, no {@code #}) is what is stored in the
 * {@code punishments.short_id} column and what {@link #parse(String)} returns.
 */
public final class PunishmentShortId {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final Pattern BASE64_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern UUID_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern KEY = Pattern.compile("[0-9a-fA-F]{8}");

    private PunishmentShortId() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * The key of an identifier in the backend's Base64 form or as a plain UUID.
     *
     * @return the eight hex characters, empty if the identifier is in neither format
     */
    public static Optional<String> keyOf(String identifier) {
        if (identifier == null) {
            return Optional.empty();
        }
        try {
            if (UUID_ID.matcher(identifier).matches()) {
                UUID uuid = UUID.fromString(identifier);
                byte[] bytes = ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                        .putLong(uuid.getLeastSignificantBits()).array();
                return Optional.of(HEX.formatHex(bytes, 0, 4));
            }
            if (BASE64_ID.matcher(identifier).matches()) {
                byte[] bytes = Base64.getUrlDecoder().decode(identifier);
                return bytes.length == 16 ? Optional.of(HEX.formatHex(bytes, 0, 4)) : Optional.empty();
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The key to store for an identifier. An identifier in neither format (not produced by this
     * backend) gets the first four bytes of its MD5 instead, so the column is never empty; the V7
     * migration does the same.
     */
    public static String storageKey(String identifier) {
        return keyOf(identifier).orElseGet(() -> {
            try {
                byte[] digest = MessageDigest.getInstance("MD5")
                        .digest(String.valueOf(identifier).getBytes(StandardCharsets.UTF_8));
                return HEX.formatHex(digest, 0, 4);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("MD5 is required by the Java platform", e);
            }
        });
    }

    /** The form shown to players and in the API: {@code #} and the key. */
    public static String display(String key) {
        return "#" + key;
    }

    /**
     * Normalises what a user typed: trims, drops one leading {@code #}, upper-cases. Accepts exactly
     * eight hex characters.
     *
     * @return the key, empty if the input is not a short ID
     */
    public static Optional<String> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String trimmed = input.strip();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        return KEY.matcher(trimmed).matches() ? Optional.of(trimmed.toUpperCase(Locale.ROOT)) : Optional.empty();
    }
}
