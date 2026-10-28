package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
