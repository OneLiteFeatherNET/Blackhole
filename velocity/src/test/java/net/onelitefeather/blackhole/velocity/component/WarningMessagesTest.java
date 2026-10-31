package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warning texts are English plain text, and the acknowledge line runs the acknowledge command. */
class WarningMessagesTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<ClickEvent> clicks(Component component) {
        List<ClickEvent> found = new ArrayList<>();
        collect(component, found);
        return found;
    }

    private static void collect(Component component, List<ClickEvent> found) {
        if (component.clickEvent() != null) {
            found.add(component.clickEvent());
        }
        for (Component child : component.children()) {
            collect(child, found);
        }
    }

    @Test
    void theWarningNamesItsReason() {
        String text = plain(WarningMessages.warning("Spam in chat", "7K3M9QXA"));

        assertTrue(text.contains("Spam in chat"), text);
    }

    @Test
    void aWarningWithoutAReadableReasonShowsTheDefaultText() {
        String text = plain(WarningMessages.warning(null, "7K3M9QXA"));

        assertTrue(text.contains("You received a warning"), text);
    }

    @Test
    void theAcknowledgeLineRunsTheAcknowledgeCommandOfThatWarning() {
        List<ClickEvent> clicks = clicks(WarningMessages.warning("Spam in chat", "7K3M9QXA"));

        assertEquals(1, clicks.size(), "one acknowledge line");
        assertEquals(ClickEvent.runCommand("/blackhole warning ack 7K3M9QXA"), clicks.getFirst());
    }

    @Test
    void theWarningTextIsEnglishPlainText() {
        String text = plain(WarningMessages.warning("Spam in chat", "7K3M9QXA"));

        assertTrue(text.contains("warning"), text);
        assertTrue(text.contains("acknowledge"), "the reader is asked to acknowledge it: " + text);
    }

    /** The plain text the {@code <bh:prefix>} tag of the bundles renders to, the same mechanism every chat message uses. */
    private static String blackholePrefix() {
        return plain(BlackholeTheme.miniMessage().deserialize("<bh:prefix>"));
    }

    @Test
    void theWarningStartsWithTheBlackholePrefix() {
        String text = plain(WarningMessages.warning("Spam in chat", "7K3M9QXA"));

        assertTrue(text.startsWith(blackholePrefix()), "the warning starts with the prefix of the other chat messages: " + text);
    }

    @Test
    void theWarningShowsThePrefixOnce() {
        String text = plain(WarningMessages.warning("Spam in chat", "7K3M9QXA"));

        assertEquals(1, text.split("Blackhole", -1).length - 1, "one prefix on the warning: " + text);
    }

    @Test
    void theAcknowledgeLineHasNoPrefixOfItsOwn() {
        String acknowledgeLine = plain(WarningMessages.warning("Spam in chat", "7K3M9QXA")).split("\n", -1)[1];

        assertFalse(acknowledgeLine.contains("Blackhole"), "the prefix is on the warning line only: " + acknowledgeLine);
    }

    @Test
    void theWarningWithoutAReadableReasonStartsWithThePrefixToo() {
        String text = plain(WarningMessages.warning(null, "7K3M9QXA"));

        assertTrue(text.startsWith(blackholePrefix()), text);
    }

    @Test
    void everySingleLineWarningReplyStartsWithThePrefix() {
        List<Component> replies = List.of(
                WarningMessages.acknowledged(),
                WarningMessages.notYours(),
                WarningMessages.unknown(),
                WarningMessages.failed(),
                WarningMessages.usage(),
                WarningMessages.applied("Alex", "spam"),
                WarningMessages.stepRequired("spam"),
                WarningMessages.failedApply());

        for (Component reply : replies) {
            String text = plain(reply);
            assertTrue(text.startsWith(blackholePrefix()), "a reply without the prefix: " + text);
        }
    }
}
