package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens.Surface;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentScreensTest {

    private static final String ID = "a1b2c3d4-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-10-21T20:06:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** One hour minus one minute after {@link #NOW}: 21:05 UTC, 23:05 in Berlin. */
    private static final long IN_59_MIN = NOW.plusSeconds(59 * 60).toEpochMilli();

    private static TranslatableComponentRenderer<Locale> renderer() {
        var store = MiniMessageTranslationStore.create(net.kyori.adventure.key.Key.key("blackhole", "test"));
        store.defaultLocale(Locale.ENGLISH);
        ClassLoader loader = PunishmentScreensTest.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, loader), false);
        store.registerAll(Locale.GERMAN, ResourceBundle.getBundle("blackhole", Locale.GERMAN, loader), false);
        return TranslatableComponentRenderer.usingTranslationSource(store);
    }

    private static BlackholeConfig config(String appealUrl, String appealText) {
        BlackholeConfig config = new BlackholeConfig();
        config.setAppealUrl(appealUrl);
        config.setAppealText(appealText);
        config.setTimeZone("Europe/Berlin");
        return config;
    }

    private static PunishmentScreens screens(BlackholeConfig config) {
        return new PunishmentScreens(CLOCK, config, TestData.standardRegistry());
    }

    private static PunishmentSyncMessage punishment(String section, EnforcementKind kind, Long expiresAt, String reason) {
        return new PunishmentSyncMessage(TestData.OWNER, section, kind.name(), PunishmentSyncMessage.STATE_SET, ID, null, reason, expiresAt);
    }

    /** Plain text with the narrow no-break spaces of newer JDK locale data normalised. */
    private static String plain(Component component, Locale locale) {
        return PlainTextComponentSerializer.plainText().serialize(renderer().render(component, locale))
                .replace(' ', ' ').replace(' ', ' ');
    }

    private static Component rendered(Component component, Locale locale) {
        return renderer().render(component, locale);
    }

    @Test
    void temporaryBanInGerman() {
        Component screen = screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, IN_59_MIN, "Unerlaubte Clientmodifikation"),
                Locale.GERMAN, Surface.DISCONNECT, false);

        assertEquals("""
                Du wurdest vom Netzwerk ausgeschlossen
                Grund: Unerlaubte Clientmodifikation
                Dauer: noch 59 Minuten (bis 21.10.2026, 23:05 MESZ)
                Bereich: Network
                Strafen-ID: #A1B2C3D4""", plain(screen, Locale.GERMAN));
    }

    @Test
    void temporaryBanInEnglish() {
        Component screen = screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, IN_59_MIN, "Client modification"),
                Locale.ENGLISH, Surface.DISCONNECT, false);

        assertEquals("""
                You have been banned from the network
                Reason: Client modification
                Duration: 59 minutes remaining (until Oct 21, 2026, 11:05 PM CEST)
                Area: Network
                Punishment ID: #A1B2C3D4""", plain(screen, Locale.ENGLISH));
    }

    @Test
    void permanentBanSaysPermanentInBothLanguages() {
        PunishmentScreens screens = screens(config("", ""));
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "Cheating");

        assertTrue(plain(screens.screen(ban, Locale.GERMAN, Surface.DISCONNECT, false), Locale.GERMAN).contains("Dauer: dauerhaft"));
        assertTrue(plain(screens.screen(ban, Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH).contains("Duration: permanent"));
    }

    @Test
    void remainingTimeUsesDaysAndHours() {
        long expires = NOW.plusSeconds(2 * 86400 + 3 * 3600 + 17 * 60).toEpochMilli();
        String text = plain(screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, expires, "x"), Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH);

        assertTrue(text.contains("2 days 3 hours remaining"), text);
    }

    @Test
    void lessThanAMinuteIsSaidSo() {
        long expires = NOW.plusSeconds(20).toEpochMilli();
        String text = plain(screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, expires, "x"), Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH);

        assertTrue(text.contains("less than a minute remaining"), text);
    }

    @Test
    void appealIsShownOnlyWhenConfigured() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");

        String without = plain(screens(config("", "")).screen(ban, Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH);
        String with = plain(screens(config("https://example.org/appeal", "")).screen(ban, Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH);

        assertFalse(without.contains("Appeal"), without);
        assertTrue(with.endsWith("Appeal: https://example.org/appeal"), with);
    }

    @Test
    void appealOnDisconnectScreenIsPlainTextWithoutClick() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");
        Component screen = screens(config("https://example.org/appeal", "our forum")).screen(ban, Locale.ENGLISH, Surface.DISCONNECT, false);

        assertTrue(plain(screen, Locale.ENGLISH).endsWith("Appeal: our forum (https://example.org/appeal)"));
        assertFalse(hasEvent(rendered(screen, Locale.ENGLISH), true), "disconnect screens cannot click");
    }

    @Test
    void appealInChatIsClickable() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");
        Component screen = rendered(screens(config("https://example.org/appeal", "our forum")).screen(ban, Locale.ENGLISH, Surface.CHAT, false), Locale.ENGLISH);

        assertTrue(hasEvent(screen, true));
    }

    @Test
    void nonHttpAppealLinkIsIgnored() {
        assertNull(config("javascript:alert(1)", "").getAppealUrl());
    }

    @Test
    void fullIdIsOnHoverInChatOnly() {
        PunishmentScreens screens = screens(config("", ""));
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");

        assertTrue(hasEvent(rendered(screens.screen(ban, Locale.ENGLISH, Surface.CHAT, false), Locale.ENGLISH), false));
        assertFalse(hasEvent(rendered(screens.screen(ban, Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH), false));
    }

    @Test
    void serverSectionNamesTheExcludedServersAndTheMove() {
        String text = plain(screens(config("", "")).screen(
                punishment("bedwars", EnforcementKind.SERVER, null, "Griefing"), Locale.ENGLISH, Surface.CHAT, true), Locale.ENGLISH);

        assertEquals("""
                You have been excluded from certain servers
                Reason: Griefing
                Duration: permanent
                Area: Bedwars
                Excluded servers: bedwars-*
                You have been moved to the fallback server.
                Punishment ID: #A1B2C3D4""", text);
    }

    @Test
    void serverSectionWithoutMoveOmitsTheMoveLine() {
        String text = plain(screens(config("", "")).screen(
                punishment("bedwars", EnforcementKind.SERVER, null, "Griefing"), Locale.GERMAN, Surface.DISCONNECT, false), Locale.GERMAN);

        assertTrue(text.contains("Gesperrte Server: bedwars-*"), text);
        assertFalse(text.contains("Fallback"), text);
    }

    @Test
    void muteShowsReasonRemainingTimeAndId() {
        String text = plain(screens(config("", "")).screen(
                punishment("chat", EnforcementKind.MUTE, IN_59_MIN, "Beleidigung"), Locale.GERMAN, Surface.CHAT, false), Locale.GERMAN);

        assertEquals("""
                Du wurdest stummgeschaltet
                Grund: Beleidigung
                Dauer: noch 59 Minuten (bis 21.10.2026, 23:05 MESZ)
                Bereich: Chat
                Strafen-ID: #A1B2C3D4""", text);
    }

    @Test
    void reasonCanUseTheDurationTag() {
        String text = plain(screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, IN_59_MIN, "Banned <duration>"), Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH);

        assertTrue(text.contains("Reason: Banned in 59 minutes"), text);
    }

    @Test
    void missingReasonFallsBackToTheDefault() {
        String text = plain(screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, null, " "), Locale.ENGLISH, Surface.DISCONNECT, false), Locale.ENGLISH);

        assertTrue(text.contains("Reason: You have been punished."), text);
    }

    @Test
    void screenNeverContainsTheOwnerHash() {
        String text = plain(screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, null, "x"), Locale.ENGLISH, Surface.CHAT, false), Locale.ENGLISH);

        assertFalse(text.contains(TestData.OWNER));
    }

    @Test
    void shortIdIsTheFirstEightHexCharactersUpperCase() {
        assertEquals("#A1B2C3D4", PunishmentScreens.shortId(ID));
        assertNull(PunishmentScreens.shortId(null));
        assertNotNull(PunishmentScreens.shortId("abc"));
    }

    private static boolean hasEvent(Component component, boolean click) {
        boolean here = click ? component.clickEvent() instanceof ClickEvent
                : component.hoverEvent() instanceof HoverEvent<?>;
        return here || component.children().stream().anyMatch(child -> hasEvent(child, click))
                || (component instanceof net.kyori.adventure.text.TranslatableComponent t
                && t.arguments().stream().anyMatch(arg -> hasEvent(arg.asComponent(), click)));
    }
}
