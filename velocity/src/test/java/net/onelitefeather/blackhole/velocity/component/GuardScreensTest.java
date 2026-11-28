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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The login denial screens: one text per guard reason, the VPN text without a reason, no identity data. */
class GuardScreensTest {

    private static final String ADDRESS = "203.0.113.7";
    private static final String BAN_REASON = "botnet wave from provider X";
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private static PunishmentScreens screens() {
        return screensAt(NOW);
    }

    private static PunishmentScreens screensAt(Instant now) {
        return new PunishmentScreens(Clock.fixed(now, ZoneOffset.UTC), new BlackholeConfig(), TestData.standardRegistry(), (component, locale) -> component);
    }

    private static Long inTwoHours() {
        return NOW.plusSeconds(2 * 3600).toEpochMilli();
    }

    private static String english(LoginDenialReason reason) {
        return Rendered.english(screens().loginDenial(Locale.ENGLISH, reason, null)).text();
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
        String text = Rendered.english(screens().loginDenial(Locale.ENGLISH, null, null)).text();

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
        String text = Rendered.german(screens().loginDenial(Locale.GERMAN, LoginDenialReason.GUARD_LOCKDOWN, null)).text();

        assertTrue(text.contains("bekannte Spieler"), "the German lockdown text is shown: " + text);
    }

    @Test
    void aMissingTranslationFallsBackToTheEnglishLockdownText() {
        MiniMessageTranslationStore empty = MiniMessageTranslationStore.create(Key.key("blackhole", "empty"), BlackholeTheme.miniMessage());
        PunishmentScreens screens = new PunishmentScreens(Clock.systemUTC(), new BlackholeConfig(), TestData.standardRegistry(),
                TranslatableComponentRenderer.usingTranslationSource(empty));

        String text = PlainTextComponentSerializer.plainText().serialize(screens.loginDenial(Locale.GERMAN, LoginDenialReason.GUARD_LOCKDOWN, null));

        assertTrue(text.contains("only open to known players"), "the English fallback is shown: " + text);
    }

    @Test
    void anAreaBanWithAnExpiryShowsTheTimeLeft() {
        String text = Rendered.english(screensAt(NOW).loginDenial(Locale.ENGLISH, LoginDenialReason.AREA_BAN, inTwoHours())).text();

        assertTrue(text.contains("Remaining: 2 hours"), "the time left is shown: " + text);
    }

    @Test
    void anAreaBanWithAnExpiryShowsTheDateItEnds() {
        String text = Rendered.english(screensAt(NOW).loginDenial(Locale.ENGLISH, LoginDenialReason.AREA_BAN, inTwoHours())).text();

        assertTrue(text.contains("until Oct 30, 2026"), "the end date is shown in the punishment screen format: " + text);
    }

    @Test
    void anAreaBanWithoutAnExpiryIsShownAsPermanent() {
        String text = Rendered.english(screensAt(NOW).loginDenial(Locale.ENGLISH, LoginDenialReason.AREA_BAN, null)).text();

        assertTrue(text.contains("Duration: permanent"), "a permanent area ban says so: " + text);
    }

    @Test
    void aGermanAreaBanWithoutAnExpiryIsShownAsPermanent() {
        String text = Rendered.german(screensAt(NOW).loginDenial(Locale.GERMAN, LoginDenialReason.AREA_BAN, null)).text();

        assertTrue(text.contains("Dauer: dauerhaft"), "a permanent area ban says so in German: " + text);
    }

    @Test
    void aGuardRefusalShowsNoDurationEvenWhenAnExpiryIsGiven() {
        String text = Rendered.english(screensAt(NOW).loginDenial(Locale.ENGLISH, LoginDenialReason.GUARD_STRICT, inTwoHours())).text();

        assertFalse(text.contains("Remaining:") || text.contains("Duration:"), "only an area ban has a duration: " + text);
    }
}
