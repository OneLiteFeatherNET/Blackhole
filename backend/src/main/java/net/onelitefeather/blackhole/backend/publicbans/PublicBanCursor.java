package net.onelitefeather.blackhole.backend.publicbans;

import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * The signed keyset cursor of the public ban list: {@code base64url(day:hash:filters) "." base64url(HMAC)}. The payload holds
 * the UTC day and the entry hash of the last entry of a page, and the filters the page was issued under. The signature is
 * made with a key derived from {@code BLACKHOLE_PUBLIC_BAN_KEY}, so a client cannot change the position it resumes from or
 * replay a cursor under other filters.
 */
@Singleton
public class PublicBanCursor {

    /**
     * The sort key of the last entry of a page.
     *
     * @param filters the filter key the page was issued under (see {@link #filtersOf})
     */
    public record Position(long day, String hash, String filters) {
    }

    private final PublicBanHasher hasher;

    public PublicBanCursor(PublicBanHasher hasher) {
        this.hasher = hasher;
    }

    /** The filter key of a query: kind, status and player hash, each empty when absent. Contains no colon. */
    public static String filtersOf(String kind, String status, String playerHash) {
        return Objects.toString(kind, "") + "|" + Objects.toString(status, "") + "|" + Objects.toString(playerHash, "");
    }

    public String encode(long day, String hash, String filters) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((day + ":" + hash + ":" + filters).getBytes(StandardCharsets.UTF_8));
        return payload + "." + this.hasher.signCursor(payload);
    }

    /** @return the position, or empty when the cursor is not one this backend signed */
    public Optional<Position> decode(String cursor) {
        int dot = cursor.indexOf('.');
        if (dot <= 0 || dot == cursor.length() - 1) {
            return Optional.empty();
        }
        String payload = cursor.substring(0, dot);
        byte[] expected = this.hasher.signCursor(payload).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, cursor.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        return parse(payload);
    }

    private static Optional<Position> parse(String payload) {
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String[] parts = text.split(":", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Position(Long.parseLong(parts[0]), parts[1], parts[2]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
