package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens.Surface;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentScreensTest {

    private static final UUID UUID_ID = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");
    /** The identifier as the backend stores it: the UUID as 22 character URL-safe Base64. */
    private static final String ID = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteBuffer.allocate(16).putLong(UUID_ID.getMostSignificantBits()).putLong(UUID_ID.getLeastSignificantBits()).array());
    private static final Instant NOW = Instant.parse("2026-10-21T20:06:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** 59 minutes after {@link #NOW}: 21:05 UTC, 23:05 in Berlin. */
    private static final long IN_59_MIN = NOW.plusSeconds(59 * 60).toEpochMilli();

    private static TranslatableComponentRenderer<Locale> renderer() {
        var store = MiniMessageTranslationStore.create(net.kyori.adventure.key.Key.key("blackhole", "test"), BlackholeTheme.miniMessage());
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
        return new PunishmentScreens(CLOCK, config, TestData.standardRegistry(), renderer());
    }

    private static PunishmentSyncMessage punishment(String section, EnforcementKind kind, Long expiresAt, String reason) {
        return new PunishmentSyncMessage(TestData.OWNER, section, kind.name(), PunishmentSyncMessage.STATE_SET, ID, null, reason, expiresAt, false);
    }

    /** Plain text with the narrow no-break spaces of newer JDK locale data normalised. */
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component).replace(' ', ' ').replace(' ', ' ');
    }

    private static Component screen(PunishmentScreens screens, PunishmentSyncMessage p, Locale locale, Surface surface, boolean moved) {
        return screens.screen(p, locale, surface, moved);
    }

    private static String text(PunishmentSyncMessage p, Locale locale, Surface surface) {
        return plain(screen(screens(config("", "")), p, locale, surface, false));
    }

    private static String textWithEvidence(Locale locale, Surface surface, long evidenceCount) {
        return plain(screens(config("", "")).screen(
                punishment("network", EnforcementKind.BAN, IN_59_MIN, "Cheating"), locale, surface, false, evidenceCount));
    }

    @Test
    void theEvidenceCountIsShownInGerman() {
        String text = textWithEvidence(Locale.GERMAN, Surface.DISCONNECT, 3);

        assertTrue(text.contains("Beweise: 3"), "the denial screen must tell the player how many pieces of evidence exist, got:\n" + text);
    }

    @Test
    void theEvidenceCountIsShownInEnglishInChat() {
        String text = textWithEvidence(Locale.ENGLISH, Surface.CHAT, 1);

        assertTrue(text.contains("Evidence: 1"), "the chat denial must show the count too, got:\n" + text);
    }

    @Test
    void aZeroEvidenceCountAddsNoLine() {
        String text = textWithEvidence(Locale.ENGLISH, Surface.DISCONNECT, 0);

        assertFalse(text.contains("Evidence"), "no evidence means no line, got:\n" + text);
    }

    @Test
    void aScreenBuiltWithoutACountMatchesOneWithZero() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, IN_59_MIN, "Cheating");

        assertEquals(
                plain(screens(config("", "")).screen(ban, Locale.ENGLISH, Surface.DISCONNECT, false)),
                plain(screens(config("", "")).screen(ban, Locale.ENGLISH, Surface.DISCONNECT, false, 0)),
                "callers that know no count must see the unchanged screen");
    }

    @Test
    void temporaryBanInGerman() {
        assertEquals("""
                Du wurdest vom Netzwerk ausgeschlossen

                Grund
                Unerlaubte Clientmodifikation

                Verbleibend: 59 Minuten
                bis 21.10.2026, 23:05 Uhr

                Bereich: Network  ·  Strafen-ID: #A1B2C3D4""",
                text(punishment("network", EnforcementKind.BAN, IN_59_MIN, "Unerlaubte Clientmodifikation"), Locale.GERMAN, Surface.DISCONNECT));
    }

    @Test
    void temporaryBanInEnglish() {
        assertEquals("""
                You have been banned from the network

                Reason
                Client modification

                Remaining: 59 minutes
                until Oct 21, 2026, 11:05 PM

                Area: Network  ·  Punishment ID: #A1B2C3D4""",
                text(punishment("network", EnforcementKind.BAN, IN_59_MIN, "Client modification"), Locale.ENGLISH, Surface.DISCONNECT));
    }

    @Test
    void germanScreenFormatsTheDateInGermanToo() {
        String text = text(punishment("network", EnforcementKind.BAN, IN_59_MIN, "x"), Locale.GERMAN, Surface.DISCONNECT);

        assertTrue(text.contains("21.10.2026"), text);
        assertFalse(text.contains("Oct"), text);
        assertFalse(text.contains("PM"), text);
    }

    private static PunishmentSyncMessage automatic(Long expiresAt) {
        return new PunishmentSyncMessage(TestData.OWNER, "network", EnforcementKind.BAN.name(), PunishmentSyncMessage.STATE_SET, ID, null,
                "Permanently banned after repeated violations", expiresAt, true);
    }

    @Test
    void automaticEscalationShowsTheTranslatedReasonInGerman() {
        assertEquals("""
                Du wurdest vom Netzwerk ausgeschlossen

                Grund
                Nach wiederholten Verstößen automatisch dauerhaft bestraft

                Dauer: dauerhaft

                Bereich: Network  ·  Strafen-ID: #A1B2C3D4""",
                text(automatic(null), Locale.GERMAN, Surface.DISCONNECT));
    }

    @Test
    void automaticEscalationShowsTheTranslatedReasonInEnglish() {
        String text = text(automatic(null), Locale.ENGLISH, Surface.DISCONNECT);

        assertTrue(text.contains("Reason\nPermanently punished by the automatic system after repeated violations\n"), text);
    }

    @Test
    void automaticTemporaryPunishmentShowsItsTranslatedReason() {
        PunishmentSyncMessage temporary = new PunishmentSyncMessage(TestData.OWNER, "network", EnforcementKind.BAN.name(),
                PunishmentSyncMessage.STATE_SET, ID, null, "Automatic temporary punishment (ELO system)", IN_59_MIN, true);

        assertTrue(text(temporary, Locale.GERMAN, Surface.DISCONNECT).contains("Automatische befristete Bestrafung (ELO-System)"));
    }

    @Test
    void automaticMuteNoticeShowsTheTranslatedReason() {
        PunishmentSyncMessage mute = new PunishmentSyncMessage(TestData.OWNER, "chat", EnforcementKind.MUTE.name(),
                PunishmentSyncMessage.STATE_SET, ID, null, "Permanently muted after repeated chat violations", null, true);

        String text = text(mute, Locale.GERMAN, Surface.CHAT);

        assertTrue(text.contains("Nach wiederholten Verstößen automatisch dauerhaft bestraft"), text);
        assertFalse(text.contains("Permanently muted"), text);
    }

    @Test
    void anAutomaticPunishmentWithACustomReasonShowsThatReason() {
        PunishmentSyncMessage custom = new PunishmentSyncMessage(TestData.OWNER, "network", EnforcementKind.BAN.name(),
                PunishmentSyncMessage.STATE_SET, ID, null, "Gesperrt wegen Betrugs", null, true);

        String text = text(custom, Locale.GERMAN, Surface.DISCONNECT);

        assertTrue(text.contains("Grund\nGesperrt wegen Betrugs\n"), text);
        assertFalse(text.contains("automatisch"), text);
    }

    @Test
    void aNormalTemplateKeepsItsOwnReasonEvenWhenPermanent() {
        String text = text(punishment("network", EnforcementKind.BAN, null, "Cheating"), Locale.GERMAN, Surface.DISCONNECT);

        assertTrue(text.contains("Grund\nCheating\n"), text);
        assertFalse(text.contains("automatisch"), text);
    }

    @Test
    void permanentBanSaysPermanentInBothLanguages() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "Cheating");

        assertTrue(text(ban, Locale.GERMAN, Surface.DISCONNECT).contains("Dauer: dauerhaft"));
        assertTrue(text(ban, Locale.ENGLISH, Surface.DISCONNECT).contains("Duration: permanent"));
    }

    @Test
    void remainingTimeUsesDaysAndHours() {
        long expires = NOW.plusSeconds(2 * 86400 + 3 * 3600 + 17 * 60).toEpochMilli();

        assertTrue(text(punishment("network", EnforcementKind.BAN, expires, "x"), Locale.ENGLISH, Surface.DISCONNECT)
                .contains("Remaining: 2 days 3 hours"));
    }

    @Test
    void lessThanAMinuteIsSaidSo() {
        long expires = NOW.plusSeconds(20).toEpochMilli();

        assertTrue(text(punishment("network", EnforcementKind.BAN, expires, "x"), Locale.ENGLISH, Surface.DISCONNECT)
                .contains("Remaining: less than a minute"));
    }

    @Test
    void appealIsShownOnlyWhenConfigured() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");

        String without = plain(screen(screens(config("", "")), ban, Locale.ENGLISH, Surface.DISCONNECT, false));
        String with = plain(screen(screens(config("https://example.org/appeal", "")), ban, Locale.ENGLISH, Surface.DISCONNECT, false));

        assertFalse(without.contains("Appeal"), without);
        assertTrue(with.endsWith("\n\nAppeal: https://example.org/appeal"), with);
    }

    @Test
    void appealOnDisconnectScreenIsPlainTextWithoutClick() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");
        Component screen = screen(screens(config("https://example.org/appeal", "our forum")), ban, Locale.ENGLISH, Surface.DISCONNECT, false);

        assertTrue(plain(screen).endsWith("Appeal: our forum (https://example.org/appeal)"));
        assertFalse(any(screen, c -> c.clickEvent() != null), "disconnect screens cannot click");
    }

    @Test
    void appealInChatIsClickable() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");
        Component screen = screen(screens(config("https://example.org/appeal", "our forum")), ban, Locale.ENGLISH, Surface.CHAT, false);

        assertTrue(any(screen, c -> c.clickEvent() != null));
    }

    @Test
    void nonHttpAppealLinkIsIgnored() {
        assertNull(config("javascript:alert(1)", "").getAppealUrl());
    }

    @Test
    void fullIdIsOnHoverInChatOnly() {
        PunishmentSyncMessage ban = punishment("network", EnforcementKind.BAN, null, "x");

        assertTrue(any(screen(screens(config("", "")), ban, Locale.ENGLISH, Surface.CHAT, false), c -> c.hoverEvent() != null));
        assertFalse(any(screen(screens(config("", "")), ban, Locale.ENGLISH, Surface.DISCONNECT, false), c -> c.hoverEvent() != null));
    }

    @Test
    void nothingIsBoldAndNoDarkGrayIsUsed() {
        for (Surface surface : Surface.values()) {
            for (Locale locale : new Locale[]{Locale.GERMAN, Locale.ENGLISH}) {
                Component screen = screen(screens(config("https://example.org/appeal", "")),
                        punishment("bedwars", EnforcementKind.SERVER, IN_59_MIN, "x"), locale, surface, true);

                assertFalse(any(screen, c -> c.style().decoration(TextDecoration.BOLD) == net.kyori.adventure.text.format.TextDecoration.State.TRUE),
                        "bold bleeds over every line of a centred screen");
                assertFalse(any(screen, c -> c.color() == NamedTextColor.DARK_GRAY), "dark gray is unreadable on the blurred background");
            }
        }
    }

    @Test
    void styleOfTheTitleDoesNotBleedIntoTheFollowingLines() {
        Component screen = screen(screens(config("", "")), punishment("network", EnforcementKind.BAN, null, "x"),
                Locale.ENGLISH, Surface.DISCONNECT, false);

        // every red text is exactly the title; later lines carry their own colours only
        StringBuilder red = new StringBuilder();
        collect(screen, NamedTextColor.RED, red);
        assertEquals("You have been banned from the network", red.toString());
    }

    @Test
    void serverSectionNamesTheExcludedServersAndTheMove() {
        String text = plain(screen(screens(config("", "")),
                punishment("bedwars", EnforcementKind.SERVER, null, "Griefing"), Locale.ENGLISH, Surface.DISCONNECT, true));

        assertEquals("""
                You have been excluded from certain servers

                Reason
                Griefing

                Duration: permanent

                Area: Bedwars  ·  Punishment ID: #A1B2C3D4
                Excluded servers: bedwars-*
                You have been moved to the fallback server.""", text);
    }

    @Test
    void serverSectionWithoutMoveOmitsTheMoveLine() {
        String text = text(punishment("bedwars", EnforcementKind.SERVER, null, "Griefing"), Locale.GERMAN, Surface.DISCONNECT);

        assertTrue(text.contains("Gesperrte Server: bedwars-*"), text);
        assertFalse(text.contains("Fallback"), text);
    }

    private String cloudNetScreen(net.onelitefeather.blackhole.client.model.ServerTargetKind kind, String name, Locale locale) {
        var section = new net.onelitefeather.blackhole.velocity.section.SectionInfo(
                "target", "Target", EnforcementKind.SERVER, java.util.List.of(name), kind);
        var screens = new PunishmentScreens(CLOCK, config("", ""), TestData.registry(section), renderer());
        return plain(screen(screens, punishment("target", EnforcementKind.SERVER, null, "x"), locale, Surface.DISCONNECT, false));
    }

    @Test
    void cloudNetTaskScreenNamesTheTaskInGerman() {
        String text = cloudNetScreen(net.onelitefeather.blackhole.client.model.ServerTargetKind.CLOUDNET_TASK, "BedWars", Locale.GERMAN);

        assertTrue(text.contains("Du bist auf BedWars gesperrt"), text);
        assertFalse(text.contains("Gesperrte Server"), "the pattern line is replaced: " + text);
    }

    @Test
    void cloudNetTaskScreenNamesTheTaskInEnglish() {
        String text = cloudNetScreen(net.onelitefeather.blackhole.client.model.ServerTargetKind.CLOUDNET_TASK, "BedWars", Locale.ENGLISH);

        assertTrue(text.contains("You are banned from BedWars"), text);
    }

    @Test
    void cloudNetGroupScreenNamesTheGroup() {
        var kind = net.onelitefeather.blackhole.client.model.ServerTargetKind.CLOUDNET_GROUP;

        assertTrue(cloudNetScreen(kind, "Minigames", Locale.GERMAN).contains("Du bist auf der Gruppe Minigames gesperrt"));
        assertTrue(cloudNetScreen(kind, "Minigames", Locale.ENGLISH).contains("You are banned from the group Minigames"));
    }

    @Test
    void cloudNetServiceScreenNamesTheService() {
        var kind = net.onelitefeather.blackhole.client.model.ServerTargetKind.CLOUDNET_SERVICE;

        assertTrue(cloudNetScreen(kind, "BedWars-3", Locale.GERMAN).contains("Du bist auf dem Server BedWars-3 gesperrt"));
        assertTrue(cloudNetScreen(kind, "BedWars-3", Locale.ENGLISH).contains("You are banned from the server BedWars-3"));
    }

    @Test
    void muteInChatIsCompact() {
        assertEquals("""
                Blackhole » Du wurdest stummgeschaltet
                Grund
                Beleidigung
                Verbleibend: 59 Minuten
                bis 21.10.2026, 23:05 Uhr
                Bereich: Chat  ·  Strafen-ID: #A1B2C3D4""",
                text(punishment("chat", EnforcementKind.MUTE, IN_59_MIN, "Beleidigung"), Locale.GERMAN, Surface.CHAT));
    }

    @Test
    void temporaryMuteNoticeInEnglishShowsReasonRemainingEndIdAndAppeal() {
        String text = plain(screens(config("https://example.org/appeal", "our forum")).screen(
                punishment("chat", EnforcementKind.MUTE, IN_59_MIN, "Insults"), Locale.ENGLISH, Surface.CHAT, false));

        assertEquals("""
                Blackhole » You have been muted
                Reason
                Insults
                Remaining: 59 minutes
                until Oct 21, 2026, 11:05 PM
                Area: Chat  ·  Punishment ID: #A1B2C3D4
                Appeal: our forum""", text);
    }

    @Test
    void temporaryMuteNoticeInGermanEndsInTheConfiguredTimeZone() {
        String text = plain(screens(config("https://example.org/appeal", "")).screen(
                punishment("chat", EnforcementKind.MUTE, IN_59_MIN, "Beleidigung"), Locale.GERMAN, Surface.CHAT, false));

        assertEquals("""
                Blackhole » Du wurdest stummgeschaltet
                Grund
                Beleidigung
                Verbleibend: 59 Minuten
                bis 21.10.2026, 23:05 Uhr
                Bereich: Chat  ·  Strafen-ID: #A1B2C3D4
                Einspruch: https://example.org/appeal""", text);
    }

    @Test
    void permanentMuteNoticeSaysPermanentAndHasNoEndTime() {
        String text = text(punishment("chat", EnforcementKind.MUTE, null, "Spam"), Locale.GERMAN, Surface.CHAT);

        assertEquals("""
                Blackhole » Du wurdest stummgeschaltet
                Grund
                Spam
                Dauer: dauerhaft
                Bereich: Chat  ·  Strafen-ID: #A1B2C3D4""", text);
    }

    @Test
    void muteNoticeWithoutReasonUsesTheDefaultReason() {
        String text = text(punishment("chat", EnforcementKind.MUTE, null, null), Locale.ENGLISH, Surface.CHAT);

        assertTrue(text.contains("Reason\nYou have been punished."), text);
    }

    @Test
    void muteNoticeWithoutAppealUrlHasNoAppealLine() {
        String text = text(punishment("chat", EnforcementKind.MUTE, null, "Spam"), Locale.ENGLISH, Surface.CHAT);

        assertFalse(text.contains("Appeal"), text);
    }

    @Test
    void muteNoticeStartsWithTheBlackholePrefix() {
        String text = text(punishment("chat", EnforcementKind.MUTE, null, "Spam"), Locale.ENGLISH, Surface.CHAT);

        assertTrue(text.startsWith("Blackhole » You have been muted"), "the header line carries the prefix, got:\n" + text);
    }

    @Test
    void banScreenStaysWithoutThePrefix() {
        String text = text(punishment("network", EnforcementKind.BAN, null, "Cheating"), Locale.ENGLISH, Surface.DISCONNECT);

        assertFalse(text.contains("Blackhole"), "a disconnect screen has no chat prefix, got:\n" + text);
    }

    @Test
    void muteReminderIsOneLineWithRemainingTimeAndId() {
        Component reminder = screens(config("https://example.org/appeal", "")).muteReminder(
                punishment("chat", EnforcementKind.MUTE, IN_59_MIN, "Insults"), Locale.GERMAN);

        assertEquals("Blackhole » Du wurdest stummgeschaltet · Verbleibend: 59 Minuten · Strafen-ID: #A1B2C3D4", plain(reminder));
    }

    @Test
    void permanentMuteReminderSaysPermanent() {
        Component reminder = screens(config("", "")).muteReminder(
                punishment("chat", EnforcementKind.MUTE, null, "Insults"), Locale.ENGLISH);

        assertEquals("Blackhole » You have been muted · Duration: permanent · Punishment ID: #A1B2C3D4", plain(reminder));
    }

    @Test
    void reasonCanUseTheDurationTag() {
        assertTrue(text(punishment("network", EnforcementKind.BAN, IN_59_MIN, "Banned <duration>"), Locale.ENGLISH, Surface.DISCONNECT)
                .contains("Banned in 59 minutes"));
    }

    @Test
    void missingReasonFallsBackToTheDefault() {
        assertTrue(text(punishment("network", EnforcementKind.BAN, null, " "), Locale.ENGLISH, Surface.DISCONNECT)
                .contains("You have been punished."));
    }

    @Test
    void screenNeverContainsTheOwnerHash() {
        assertFalse(text(punishment("network", EnforcementKind.BAN, null, "x"), Locale.ENGLISH, Surface.CHAT).contains(TestData.OWNER));
    }

    @Test
    void shortIdIsTheHexOfTheFirstFourBytesOfTheBackendIdentifier() {
        assertEquals("#A1B2C3D4", PunishmentScreens.shortId(ID));
    }

    @Test
    void shortIdOfAPlainUuidIsTheSame() {
        assertEquals("#A1B2C3D4", PunishmentScreens.shortId(UUID_ID.toString()));
    }

    @Test
    void shortIdOfAnUnknownFormatIsAbsent() {
        assertNull(PunishmentScreens.shortId(null));
        assertNull(PunishmentScreens.shortId("abc"));
        assertNull(PunishmentScreens.shortId("!".repeat(22)));
    }

    private static boolean any(Component component, Predicate<Component> test) {
        return test.test(component) || component.children().stream().anyMatch(child -> any(child, test))
                || (component instanceof net.kyori.adventure.text.TranslatableComponent t
                && t.arguments().stream().anyMatch(arg -> any(arg.asComponent(), test)));
    }

    /** Appends the text of every component whose own colour is {@code color}. */
    private static void collect(Component component, NamedTextColor color, StringBuilder out) {
        if (component.color() == color && component instanceof net.kyori.adventure.text.TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> collect(child, color, out));
    }
}
