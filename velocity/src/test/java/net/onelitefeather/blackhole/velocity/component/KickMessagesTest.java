package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kick feedback that staff read in chat carries the Blackhole prefix, like every other chat message. */
class KickMessagesTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String blackholePrefix() {
        return plain(BlackholeTheme.miniMessage().deserialize("<bh:prefix>"));
    }

    @Test
    void theKickingLineStartsWithThePrefix() {
        assertTrue(plain(KickMessages.sent("Alex")).startsWith(blackholePrefix()), "sent");
    }

    @Test
    void theTimedOutLineStartsWithThePrefix() {
        assertTrue(plain(KickMessages.timedOut("Alex")).startsWith(blackholePrefix()), "timed out");
    }

    @Test
    void theRefusedKickLineStartsWithThePrefix() {
        assertTrue(plain(KickMessages.failed(403)).startsWith(blackholePrefix()), "failed");
    }

    @Test
    void theInvalidArgumentLineStartsWithThePrefix() {
        assertTrue(plain(KickMessages.invalid("Unknown server")).startsWith(blackholePrefix()), "invalid");
    }
}
