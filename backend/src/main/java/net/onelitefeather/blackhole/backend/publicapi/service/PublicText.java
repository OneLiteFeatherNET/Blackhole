package net.onelitefeather.blackhole.backend.publicapi.service;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;

/** What a player may write: a message or an appeal statement. Plain text, trimmed, and nothing that identifies anyone. */
final class PublicText {

    /** The text to store, or why it is not acceptable. Exactly one of the two is set. */
    record Checked(@Nullable String text, @Nullable PublicErrorKind failure) {

        boolean ok() {
            return this.failure == null;
        }

        String code() {
            return this.failure == PublicErrorKind.PII_REJECTED ? "PII_REJECTED" : "VALIDATION";
        }
    }

    private PublicText() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Trims the text and checks it: 1 to {@code maxLength} characters, no control character other than a
     * newline (a carriage return before one is dropped), no UUID, IP address or Ban ID.
     *
     * @return the text to store; {@code VALIDATION} (400) for an empty, too long or malformed text,
     *         {@code PII_REJECTED} (422) for a lookalike of personal data
     */
    static Checked check(@Nullable String raw, int maxLength, BanIdService banIds) {
        if (raw == null) {
            return new Checked(null, PublicErrorKind.VALIDATION);
        }
        String text = raw.replace("\r\n", "\n").strip();
        if (text.isEmpty() || text.length() > maxLength || hasControlCharacter(text)) {
            return new Checked(null, PublicErrorKind.VALIDATION);
        }
        if (PiiGuard.containsUuid(text) || PiiGuard.containsIp(text) || banIds.containsBanId(text)) {
            return new Checked(null, PublicErrorKind.PII_REJECTED);
        }
        return new Checked(text, null);
    }

    private static boolean hasControlCharacter(String text) {
        return text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n');
    }
}
