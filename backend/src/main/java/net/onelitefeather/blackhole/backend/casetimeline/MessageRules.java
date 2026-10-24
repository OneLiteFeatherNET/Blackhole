package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;

/** What a conversation message may say, for staff replies, player messages and the player message of an appeal decision. */
public final class MessageRules {

    /** The longest message, in characters. */
    public static final int MAX_MESSAGE = 2000;

    private MessageRules() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Checks a message text.
     *
     * @return why the text is not acceptable, or {@code null} when it is
     */
    public static @Nullable String check(@Nullable String text, BanIdService banIds) {
        if (text == null || text.isBlank() || text.length() > MAX_MESSAGE) {
            return "a message is 1 to " + MAX_MESSAGE + " characters";
        }
        if (PiiGuard.containsUuid(text) || PiiGuard.containsIp(text)) {
            return "a message must not contain a UUID or an IP address";
        }
        if (banIds.containsBanId(text)) {
            return "a message must not contain a Ban ID";
        }
        return null;
    }
}
