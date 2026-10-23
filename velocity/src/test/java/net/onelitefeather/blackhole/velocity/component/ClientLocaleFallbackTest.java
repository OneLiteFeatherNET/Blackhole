package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A player whose client language has no bundle must see the English texts, not German or raw keys.
 */
class ClientLocaleFallbackTest {

    private static final Component PUNISHED = Component.translatable("punishment.reason.default");

    @Test
    void aClientLocaleWithoutABundleGetsTheEnglishText() {
        assertEquals("You have been punished.", Rendered.of(PUNISHED, Locale.FRENCH).text(), "french client");
    }

    @Test
    void aClientWithoutAnyLocaleGetsTheEnglishText() {
        assertEquals("You have been punished.", Rendered.of(PUNISHED, Locale.ROOT).text(), "root locale client");
    }

    @Test
    void aGermanClientStillGetsTheGermanText() {
        assertEquals("Du wurdest bestraft.", Rendered.german(PUNISHED).text(), "german client");
    }
}
