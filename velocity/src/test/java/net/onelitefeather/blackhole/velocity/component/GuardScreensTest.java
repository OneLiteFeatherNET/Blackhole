package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.model.LoginDenialReason;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The login denial screens: one text per guard reason, the VPN text without a reason, no identity data. */
class GuardScreensTest {

    private static final String ADDRESS = "203.0.113.7";
    private static final String BAN_REASON = "botnet wave from provider X";

    private static PunishmentScreens screens() {
        return new PunishmentScreens(Clock.systemUTC(), new BlackholeConfig(), TestData.standardRegistry(), (component, locale) -> component);
    }

    private static String english(LoginDenialReason reason) {
        return Rendered.english(screens().loginDenial(Locale.ENGLISH, reason)).text();
    }

    @Test
    void anAreaBanShowsTheGenericAreaBanText() {
        String text = english(LoginDenialReason.AREA_BAN);

        assertTrue(text.contains("blocked from this network"), "the area ban screen is shown: " + text);
    }

    @Test
    void aStrictRefusalShowsTheVpnAndHostingText() {
        String text = english(LoginDenialReason.GUARD_STRICT);

        assertTrue(text.contains("VPN, proxy or hosting"), "the strict screen is shown: " + text);
    }

    @Test
    void aLockdownRefusalSaysTheNetworkIsOnlyOpenToKnownPlayers() {
        String text = english(LoginDenialReason.GUARD_LOCKDOWN);

        assertTrue(text.contains("only open to known players"), "the lockdown screen is shown: " + text);
    }

    @Test
    void aDenialWithoutAReasonShowsTheVpnScreen() {
        String text = Rendered.english(screens().loginDenial(Locale.ENGLISH, null)).text();

        assertTrue(text.contains("VPN and hosting connections are not allowed"), "a DENY without a reason is the VPN screen: " + text);
    }

    @Test
    void noDenialScreenShowsTheAddressOrTheAreaBanReason() {
        for (LoginDenialReason reason : LoginDenialReason.values()) {
            String text = english(reason);

            assertFalse(text.contains(ADDRESS), reason + " must not show the address: " + text);
            assertFalse(text.contains(BAN_REASON), reason + " must not show the area ban reason: " + text);
        }
    }

    @Test
    void aGermanClientGetsTheGermanLockdownText() {
        String text = Rendered.german(screens().loginDenial(Locale.GERMAN, LoginDenialReason.GUARD_LOCKDOWN)).text();

        assertTrue(text.contains("bekannte Spieler"), "the German lockdown text is shown: " + text);
    }

    @Test
    void aMissingTranslationFallsBackToTheEnglishLockdownText() {
        MiniMessageTranslationStore empty = MiniMessageTranslationStore.create(Key.key("blackhole", "empty"), BlackholeTheme.miniMessage());
        PunishmentScreens screens = new PunishmentScreens(Clock.systemUTC(), new BlackholeConfig(), TestData.standardRegistry(),
                TranslatableComponentRenderer.usingTranslationSource(empty));

        String text = PlainTextComponentSerializer.plainText().serialize(screens.loginDenial(Locale.GERMAN, LoginDenialReason.GUARD_LOCKDOWN));

        assertTrue(text.contains("only open to known players"), "the English fallback is shown: " + text);
    }
}
