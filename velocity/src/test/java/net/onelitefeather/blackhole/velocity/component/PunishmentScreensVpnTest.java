package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The VPN denial screen: no address, no account detail, English text where no translation exists. */
class PunishmentScreensVpnTest {

    private static final String ADDRESS = "203.0.113.7";

    private static PunishmentScreens screens() {
        return new PunishmentScreens(Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC), new BlackholeConfig(),
                TestData.standardRegistry(), (component, locale) -> component);
    }

    @Test
    void theDenialSaysThatVpnAndHostingConnectionsAreNotAllowed() {
        String text = Rendered.english(screens().vpnDenial(Locale.ENGLISH)).text();

        assertTrue(text.contains("VPN and hosting connections are not allowed on this network"), text);
    }

    @Test
    void theDenialHasNoAddressAndNoAccountDetail() {
        String text = Rendered.english(screens().vpnDenial(Locale.ENGLISH)).text();

        assertFalse(text.contains(ADDRESS), "no address on the screen: " + text);
        assertFalse(text.contains("Ban ID"), "no account detail on the screen: " + text);
    }

    @Test
    void aGermanClientGetsTheGermanTextWhereItExists() {
        String text = Rendered.german(screens().vpnDenial(Locale.GERMAN)).text();

        assertTrue(text.contains("nicht erlaubt"), "the German text is shown: " + text);
    }

    @Test
    void aMissingTranslationFallsBackToTheEnglishText() {
        MiniMessageTranslationStore empty = MiniMessageTranslationStore.create(Key.key("blackhole", "empty"), BlackholeTheme.miniMessage());
        PunishmentScreens screens = new PunishmentScreens(Clock.systemUTC(), new BlackholeConfig(), TestData.standardRegistry(),
                TranslatableComponentRenderer.usingTranslationSource(empty));

        String text = PlainTextComponentSerializer.plainText().serialize(screens.vpnDenial(Locale.GERMAN));

        assertTrue(text.contains("VPN and hosting connections are not allowed on this network"), "English fallback: " + text);
    }
}
