package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.punish.EloReport;
import net.onelitefeather.phoca.metadata.Expirable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /elo, info and history views, for players and for the console. */
class ProfileMessagesTest {

    private static final UUID PUNISHMENT = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");
    private static final String ID = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteBuffer.allocate(16).putLong(PUNISHMENT.getMostSignificantBits()).putLong(PUNISHMENT.getLeastSignificantBits()).array());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T12:15:00Z"), ZoneOffset.UTC);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final long CREATED = Instant.parse("2026-10-22T12:14:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-10-22T18:14:00Z").toEpochMilli();

    private static EloReport.Row row(String name, int score, Integer soft, int permanent, boolean permanentActive) {
        return new EloReport.Row(name.toLowerCase(), name, score, soft, permanent, permanentActive);
    }

    private static PunishEntryDTO entry(boolean permanent, boolean lifted) {
        return new PunishEntryDTO().identifier(ID).section("network").permanent(permanent).lifted(lifted).createdAt(CREATED)
                .template(new PunishTemplateResponse().key("spam_1").reason("Spam"))
                .metaData(permanent ? Map.of() : Map.of(Expirable.META_DATA_KEY_EXPIRATION_DATE, END));
    }

    private static PunishProfileDTO profile() {
        return new PunishProfileDTO().owner(OWNER).active(Map.of()).history(List.of()).metaData(Map.of());
    }

    private static Component info(PunishProfileDTO profile, Mode mode) {
        return ProfileMessages.info("TheMeinerDev", profile, CLOCK, ZONE, mode);
    }

    private static Component history(PunishProfileDTO profile, Mode mode) {
        return ProfileMessages.history("TheMeinerDev", profile, CLOCK, ZONE, mode);
    }

    // ---- /elo ----------------------------------------------------------------------------------

    @Test
    void aScoreAboveTheSoftThresholdIsGreen() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Network", 900, 800, 500, false)), Mode.RICH));

        assertEquals(NamedTextColor.GREEN, rendered.colorOf("900"), "healthy score");
    }

    @Test
    void aScoreAtTheSoftThresholdIsYellow() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Network", 800, 800, 500, false)), Mode.RICH));

        assertEquals(NamedTextColor.YELLOW, rendered.colorOf("800"), "at the soft threshold");
    }

    @Test
    void aScoreBelowThePermanentThresholdIsRed() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Network", 400, 800, 500, false)), Mode.RICH));

        assertEquals(NamedTextColor.RED, rendered.colorOf("400"), "below the permanent threshold");
    }

    @Test
    void aSectionWithoutSoftThresholdIsGreenUntilThePermanentThreshold() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 600, null, 500, false)), Mode.RICH));

        assertEquals(NamedTextColor.GREEN, rendered.colorOf("600"), "no soft threshold, so nothing to be yellow at");
    }

    @Test
    void anActivePermanentPunishmentMakesTheScoreRedAndIsMarked() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 900, 800, 500, true)), Mode.RICH));

        assertEquals(NamedTextColor.RED, rendered.colorOf("900"), "permanent beats a good score");
        assertTrue(rendered.text().contains("✖ permanently punished"), "marker, was: " + rendered.text());
    }

    @Test
    void theSectionHoverShowsBothThresholds() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Network", 900, 800, 500, false)), Mode.RICH));

        assertTrue(rendered.hoverTexts().contains("Soft threshold: 800\nPermanent below: 500"), "hovers: " + rendered.hoverTexts());
    }

    @Test
    void theEloBlockInEnglish() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev",
                List.of(row("Network", 900, 800, 500, false), row("Chat", 300, null, 400, true)), Mode.RICH));

        assertEquals("Blackhole » ELO of TheMeinerDev\n  ▸ Network 900\n  ▸ Chat 300 · ✖ permanently punished", rendered.text(), "block");
        assertEquals(List.of("/elo TheMeinerDev"), rendered.clickValues(), "only the player name is clickable");
    }

    @Test
    void theEloBlockInGerman() {
        var rendered = Rendered.german(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 300, null, 400, true)), Mode.RICH));

        assertEquals("Blackhole » ELO von TheMeinerDev\n  ▸ Chat 300 · ✖ permanent bestraft", rendered.text(), "block");
    }

    @Test
    void anEloProfileWithoutSectionsSaysSo() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(), Mode.RICH));

        assertEquals("Blackhole » ELO of TheMeinerDev\n  ▸ No ELO scores yet.", rendered.text(), "empty profile");
    }

    @Test
    void theConsoleGetsTheThresholdsInlineAndNoEvents() {
        Mode mode = Mode.of(Audience.empty());
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Network", 900, 800, 500, false)), mode));

        assertEquals(Mode.PLAIN, mode, "a non-player receiver is PLAIN");
        assertFalse(rendered.hasEvents(), "no hover or click for the console");
        assertEquals("Blackhole » ELO of TheMeinerDev\n  ▸ Network 900 (soft 800, permanent below 500)", rendered.text(), "inline thresholds");
    }

    @Test
    void aSectionWithoutSoftThresholdShowsNoneInline() {
        var rendered = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 600, null, 500, false)), Mode.PLAIN));

        assertTrue(rendered.text().contains("(no soft threshold, permanent below 500)"), "was: " + rendered.text());
    }

    // ---- info ----------------------------------------------------------------------------------

    @Test
    void anEmptyProfileHasNoActivePunishments() {
        assertEquals("Blackhole » Active punishments of TheMeinerDev\n  ▸ ✔ No active punishments.",
                Rendered.english(info(profile(), Mode.RICH)).text(), "English");
        assertEquals("Blackhole » Aktive Strafen von TheMeinerDev\n  ▸ ✔ Keine aktiven Strafen.",
                Rendered.german(info(profile(), Mode.RICH)).text(), "German");
    }

    @Test
    void theInfoHeaderHasNoPossessive() {
        String text = Rendered.english(info(profile(), Mode.RICH)).text();

        assertFalse(text.contains("TheMeinerDevs"), "no genitive s, was: " + text);
    }

    @Test
    void anActiveTemporaryPunishmentIsOneLine() {
        PunishProfileDTO profile = profile().active(Map.of("network", entry(false, false)));

        var rendered = Rendered.english(info(profile, Mode.RICH));

        assertEquals("Blackhole » Active punishments of TheMeinerDev\n  ▸ #A1B2C3D4 · network · temporary · 2026-10-22 14:14 · Spam",
                rendered.text(), "entry line");
    }

    @Test
    void theEntryHoverCarriesReasonTemplateIdAndEnd() {
        PunishProfileDTO profile = profile().active(Map.of("network", entry(false, false)));

        var rendered = Rendered.english(info(profile, Mode.RICH));

        assertTrue(rendered.hoverTexts().contains("Reason: Spam\nTemplate: spam_1\nID: #A1B2C3D4\nEnds: 2026-10-22 20:14 (Europe/Berlin)"),
                "hovers: " + rendered.hoverTexts());
    }

    @Test
    void theShortIdOfAnEntryCopiesItself() {
        PunishProfileDTO profile = profile().active(Map.of("network", entry(false, false)));

        var rendered = Rendered.english(info(profile, Mode.RICH));

        assertEquals(List.of("/elo TheMeinerDev", "#A1B2C3D4"), rendered.clickValues(), "player suggests, id copies");
    }

    @Test
    void theConsoleGetsTemplateAndEndInlineAndNoEvents() {
        PunishProfileDTO profile = profile().active(Map.of("network", entry(false, false)));

        var rendered = Rendered.english(info(profile, Mode.PLAIN));

        assertFalse(rendered.hasEvents(), "no hover or click for the console");
        assertEquals("Blackhole » Active punishments of TheMeinerDev\n"
                + "  ▸ #A1B2C3D4 · network · temporary · 2026-10-22 14:14 · Spam · template spam_1 · ends 2026-10-22 20:14 (Europe/Berlin)",
                rendered.text(), "inline details");
    }

    // ---- history -------------------------------------------------------------------------------

    @Test
    void anEmptyHistorySaysSo() {
        assertEquals("Blackhole » History of TheMeinerDev (entries: 0)\n  ▸ No punishments recorded.",
                Rendered.english(history(profile(), Mode.RICH)).text(), "English");
        assertEquals("Blackhole » Verlauf von TheMeinerDev (Einträge: 0)\n  ▸ Keine Strafen aufgezeichnet.",
                Rendered.german(history(profile(), Mode.RICH)).text(), "German");
    }

    @Test
    void aMissingHistoryIsTreatedLikeAnEmptyOne() {
        var rendered = Rendered.english(history(profile().history(null), Mode.RICH));

        assertTrue(rendered.text().contains("No punishments recorded."), "was: " + rendered.text());
    }

    @Test
    void historyEntriesAreTaggedLiftedPermanentOrTemporaryInOrder() {
        PunishProfileDTO profile = profile().history(List.of(entry(false, false), entry(true, false), entry(false, true)));

        var rendered = Rendered.english(history(profile, Mode.RICH));

        assertEquals("Blackhole » History of TheMeinerDev (entries: 3)\n"
                + "  ▸ #A1B2C3D4 · network · temporary · 2026-10-22 14:14 · Spam\n"
                + "  ▸ #A1B2C3D4 · network · permanent · 2026-10-22 14:14 · Spam\n"
                + "  ▸ #A1B2C3D4 · network · lifted · 2026-10-22 14:14 · Spam", rendered.text(), "three entries");
        assertEquals(NamedTextColor.YELLOW, rendered.colorOf("temporary"), "temporary tag");
        assertEquals(NamedTextColor.RED, rendered.colorOf("permanent"), "permanent tag");
        assertEquals(NamedTextColor.RED, rendered.colorOf("lifted"), "lifted tag");
    }

    @Test
    void aPermanentEntryHoverSaysPermanent() {
        var rendered = Rendered.english(history(profile().history(List.of(entry(true, false))), Mode.RICH));

        assertTrue(rendered.hoverTexts().contains("Reason: Spam\nTemplate: spam_1\nID: #A1B2C3D4\nEnds: permanently"),
                "hovers: " + rendered.hoverTexts());
    }

    @Test
    void anEntryWithoutTemplateReasonOrTimeStillRenders() {
        PunishEntryDTO bare = new PunishEntryDTO().section("chat").permanent(false).lifted(false).metaData(Map.of());

        var rendered = Rendered.english(history(profile().history(List.of(bare)), Mode.RICH));

        assertEquals("Blackhole » History of TheMeinerDev (entries: 1)\n  ▸ - · chat · temporary · - · -", rendered.text(), "placeholders");
    }

    @Test
    void theReasonKeepsItsRemainingTimeTagFromTheInjectedClock() {
        PunishEntryDTO withTag = entry(false, false).template(new PunishTemplateResponse().key("k").reason("Spam, ends <duration>"));

        var rendered = Rendered.english(history(profile().history(List.of(withTag)), Mode.PLAIN));

        assertTrue(rendered.text().contains("Spam, ends in 5 hours 59 minutes"), "was: " + rendered.text());
    }

    @Test
    void aSectionWithoutSoftThresholdReadsNaturallyInGerman() {
        var inline = Rendered.german(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 600, null, 300, false)), Mode.PLAIN));
        var hover = Rendered.german(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 600, null, 300, false)), Mode.RICH));

        assertTrue(inline.text().contains("(keine Warnschwelle, permanent unter 300)"), "inline, was: " + inline.text());
        assertTrue(hover.hoverTexts().contains("Keine Warnschwelle\nPermanent unter: 300"), "hover: " + hover.hoverTexts());
    }

    @Test
    void aSectionWithSoftThresholdReadsNaturallyInGerman() {
        var inline = Rendered.german(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 600, 700, 300, false)), Mode.PLAIN));
        var hover = Rendered.german(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 600, 700, 300, false)), Mode.RICH));

        assertTrue(inline.text().contains("(Warnschwelle 700, permanent unter 300)"), "inline, was: " + inline.text());
        assertTrue(hover.hoverTexts().contains("Warnschwelle: 700\nPermanent unter: 300"), "hover: " + hover.hoverTexts());
    }

    @Test
    void theEnglishHoverWithoutSoftThresholdSaysSo() {
        var hover = Rendered.english(ProfileMessages.elo("TheMeinerDev", List.of(row("Chat", 600, null, 300, false)), Mode.RICH));

        assertTrue(hover.hoverTexts().contains("No soft threshold\nPermanent below: 300"), "hover: " + hover.hoverTexts());
    }
}
