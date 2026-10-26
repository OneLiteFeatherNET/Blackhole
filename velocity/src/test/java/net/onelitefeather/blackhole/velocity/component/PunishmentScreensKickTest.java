package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens.Surface;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kick screen is English whatever the locale; the German bundle is loaded to prove it is not used. */
class PunishmentScreensKickTest {

    private static PunishmentScreens screens() {
        var store = MiniMessageTranslationStore.create(net.kyori.adventure.key.Key.key("blackhole", "test"), BlackholeTheme.miniMessage());
        store.defaultLocale(Locale.ENGLISH);
        ClassLoader loader = PunishmentScreensKickTest.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, loader), false);
        store.registerAll(Locale.GERMAN, ResourceBundle.getBundle("blackhole", Locale.GERMAN, loader), false);
        return new PunishmentScreens(Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC), new BlackholeConfig(),
                TestData.standardRegistry(), TranslatableComponentRenderer.usingTranslationSource(store));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void aGermanClientGetsTheEnglishKickScreen() {
        String german = plain(screens().kick(Locale.GERMAN, Surface.KICK, "Bitte ruhig bleiben", false));
        String english = plain(screens().kick(Locale.ENGLISH, Surface.KICK, "Bitte ruhig bleiben", false));

        assertEquals(english, german, "the locale changes nothing");
        assertTrue(german.contains("You were removed from the network"), german);
    }

    @Test
    void theReasonIsShownAsGiven() {
        String text = plain(screens().kick(Locale.ENGLISH, Surface.KICK, "Please calm down", false));

        assertTrue(text.contains("Please calm down"), text);
    }

    @Test
    void aReasonIsShownAsPlainTextNotAsMarkup() {
        String text = plain(screens().kick(Locale.ENGLISH, Surface.KICK, "<red>loud</red>", false));

        assertTrue(text.contains("<red>loud</red>"), "staff text is not parsed as MiniMessage: " + text);
    }

    @Test
    void noReasonShowsOnlyTheDefaultText() {
        String text = plain(screens().kick(Locale.ENGLISH, Surface.KICK, null, false));

        assertEquals("You were removed from the network", text);
    }

    @Test
    void aMoveOffAServerSaysSoInsteadOfNamingTheNetwork() {
        String text = plain(screens().kick(Locale.ENGLISH, Surface.CHAT, "Please calm down", true));

        assertTrue(text.contains("You were moved to the fallback server"), text);
        assertFalse(text.contains("removed from the network"), text);
    }

    @Test
    void aKickScreenCarriesNoAppealHint() {
        BlackholeConfig config = new BlackholeConfig();
        config.setAppealUrl("https://appeal.example");

        var screens = new PunishmentScreens(Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC), config, TestData.standardRegistry(),
                TranslatableComponentRenderer.usingTranslationSource(MiniMessageTranslationStore.create(net.kyori.adventure.key.Key.key("blackhole", "x"), BlackholeTheme.miniMessage())));

        assertFalse(plain(screens.kick(Locale.ENGLISH, Surface.KICK, "x", false)).contains("appeal.example"), "a kick is not a punishment");
    }
}
