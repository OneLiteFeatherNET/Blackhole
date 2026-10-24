package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentEscalation;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.punish.TargetNotFoundException;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffMessagesTest {

    private static final UUID PUNISHMENT = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");
    /** The identifier as the backend stores it: the UUID as 22 character URL-safe Base64. */
    private static final String ID = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteBuffer.allocate(16).putLong(PUNISHMENT.getMostSignificantBits()).putLong(PUNISHMENT.getLeastSignificantBits()).array());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T12:15:00Z"), ZoneOffset.UTC);
    private static final long END = Instant.parse("2026-10-22T18:14:00Z").toEpochMilli();
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final Permissions EVERYTHING = new Permissions(permission -> true);
    private static final Permissions NOTHING = new Permissions(permission -> false);

    private static Permissions with(String... granted) {
        return new Permissions(Set.of(granted)::contains);
    }

    private static PunishmentApplyResponse response(boolean permanent, boolean escalated) {
        PunishmentApplyResponse response = new PunishmentApplyResponse()
                .punishment(new PunishEntryDTO().identifier(ID).section("network").permanent(permanent)
                        .template(new PunishTemplateResponse().key("spam_1").reason("Spam")))
                .step(1).stepCount(2).scoreBefore(1000).scoreAfter(900);
        if (escalated) {
            response.escalation(new PunishmentEscalation().resultingScore(700));
        }
        return response;
    }

    private static Component applied(SectionInfo section, PunishmentApplyResponse response, Long expiresAt, Permissions permissions, Mode mode) {
        PunishmentSyncMessage punished = TestData.active(section.key(), section.enforcement(), ID, expiresAt);
        return StaffMessages.applied("TheMeinerDev", section, response, punished, CLOCK, ZONE, permissions, mode);
    }

    private static Component temporaryBan(Permissions permissions, Mode mode) {
        return applied(TestData.NETWORK, response(false, false), END, permissions, mode);
    }

    // ---- the punish result ---------------------------------------------------------------------

    @Test
    void aTemporaryPunishmentIsOneBlockOfHeaderStepScoreAndId() {
        Rendered rendered = Rendered.english(temporaryBan(EVERYTHING, Mode.RICH));

        assertEquals("Blackhole » ✔ Punished TheMeinerDev in Network. [Undo]\n"
                + "  ▸ Step 1/2 · ends in 5 hours 59 minutes\n"
                + "  ▸ Score: 1000 → 900 (−100)\n"
                + "  ▸ ID #A1B2C3D4", rendered.text());
    }

    @Test
    void theGermanBlockCarriesTheSameParts() {
        String text = Rendered.german(temporaryBan(EVERYTHING, Mode.RICH)).text();

        assertTrue(text.startsWith("Blackhole » ✔ TheMeinerDev bestraft in Network. [Rückgängig]\n"), text);
        assertTrue(text.contains("  ▸ Stufe 1/2 · endet in 5 Stunden 59 Minuten\n"), text);
        assertTrue(text.contains("  ▸ Punktestand: 1000 → 900 (−100)\n"), text);
        assertTrue(text.endsWith("  ▸ ID #A1B2C3D4"), text);
    }

    @Test
    void aPermanentPunishmentSaysItNeverEnds() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(true, false), null, EVERYTHING, Mode.RICH));

        assertTrue(rendered.text().contains("Step 1/2 · ends permanently"), rendered.text());
    }

    @Test
    void theEscalationToAPermanentPunishmentIsAnExtraWarningLine() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(false, true), END, EVERYTHING, Mode.RICH));

        String[] lines = rendered.text().split("\n");
        assertEquals(5, lines.length, "the block gets exactly one more line");
        assertEquals("  ⚠ Now permanently punished in Network (score 700).", lines[4]);
    }

    @Test
    void aSenderWithoutThePardonPermissionIsToldWhatLiftingNeeds() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(false, true), END, with(Permissions.UNBAN), Mode.RICH));

        assertTrue(rendered.text().endsWith("Needs blackhole.pardon.permanent to undo."), rendered.text());
    }

    @Test
    void aSenderWithThePardonPermissionGetsNoHint() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(false, true), END, EVERYTHING, Mode.RICH));

        assertFalse(rendered.text().contains("blackhole.pardon.permanent"), "nothing to explain to who may lift it");
    }

    // ---- hover and click -----------------------------------------------------------------------

    @Test
    void theNameSuggestsTheEloCommandAndNeverRunsIt() {
        Rendered rendered = Rendered.english(temporaryBan(EVERYTHING, Mode.RICH));

        assertTrue(rendered.clicks().stream().anyMatch(click -> click.action() == ClickEvent.Action.SUGGEST_COMMAND),
                "the name must be clickable");
        assertTrue(rendered.clickValues().contains("/elo TheMeinerDev"), "clicks: " + rendered.clickValues());
    }

    @Test
    void theShortIdCopiesItself() {
        Rendered rendered = Rendered.english(temporaryBan(EVERYTHING, Mode.RICH));

        assertTrue(rendered.clicks().stream().anyMatch(click -> click.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
                && "#A1B2C3D4".equals(((ClickEvent.Payload.Text) click.payload()).value())), "clicks: " + rendered.clickValues());
    }

    @Test
    void noClickEverRunsACommand() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(false, true), END, EVERYTHING, Mode.RICH));

        for (ClickEvent click : rendered.clicks()) {
            assertTrue(click.action() == ClickEvent.Action.SUGGEST_COMMAND || click.action() == ClickEvent.Action.COPY_TO_CLIPBOARD,
                    "only suggest or copy, found " + click.action());
        }
    }

    @Test
    void theUndoButtonSuggestsUnbanWithTheSectionKey() {
        Rendered rendered = Rendered.english(temporaryBan(with(Permissions.UNBAN), Mode.RICH));

        assertTrue(rendered.clickValues().contains("/unban TheMeinerDev network"), "clicks: " + rendered.clickValues());
    }

    @Test
    void theUndoButtonOfAMuteSuggestsUnmute() {
        Rendered rendered = Rendered.english(applied(TestData.CHAT, response(false, false), END, with(Permissions.UNMUTE), Mode.RICH));

        assertTrue(rendered.clickValues().contains("/unmute TheMeinerDev chat"), "clicks: " + rendered.clickValues());
    }

    @Test
    void theUndoButtonOfAServerSectionNamesThatSection() {
        Rendered rendered = Rendered.english(applied(TestData.BEDWARS, response(false, false), END, with(Permissions.UNBAN), Mode.RICH));

        assertTrue(rendered.clickValues().contains("/unban TheMeinerDev bedwars"), "clicks: " + rendered.clickValues());
    }

    @Test
    void withoutThePermissionThereIsNoUndoButtonForAMute() {
        Rendered rendered = Rendered.english(applied(TestData.CHAT, response(false, false), END, with(Permissions.UNBAN), Mode.RICH));

        assertFalse(rendered.text().contains("[Undo]"), "a mute needs blackhole.unmute: " + rendered.text());
        assertFalse(rendered.clickValues().contains("/unmute TheMeinerDev chat"));
    }

    @Test
    void withoutThePardonPermissionThereIsNoUndoButtonForAnEscalation() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(false, true), END, with(Permissions.UNBAN), Mode.RICH));

        assertFalse(rendered.text().contains("[Undo]"), "the permanent ban needs blackhole.pardon.permanent: " + rendered.text());
    }

    @Test
    void thePunishmentHoverShowsReasonKeyIdAndTheEndInTheConfiguredZone() {
        Rendered rendered = Rendered.english(temporaryBan(EVERYTHING, Mode.RICH));

        String hover = rendered.hoverTexts().stream().filter(text -> text.contains("Template")).findFirst().orElse("");
        assertEquals("Reason: Spam\nTemplate: spam_1\nID: #A1B2C3D4\nEnds: 2026-10-22 20:14 (Europe/Berlin)", hover);
    }

    @Test
    void theHoverOfAPermanentPunishmentSaysPermanent() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(true, false), null, EVERYTHING, Mode.RICH));

        assertTrue(rendered.hoverTexts().stream().anyMatch(text -> text.contains("Ends: permanently")), rendered.hoverTexts().toString());
    }

    @Test
    void noHoverOrTextShowsTheOwnerHashOrARawUuid() {
        Rendered rendered = Rendered.english(applied(TestData.NETWORK, response(false, true), END, EVERYTHING, Mode.RICH));
        String everything = rendered.text() + rendered.hoverTexts() + rendered.clickValues();

        assertFalse(everything.contains(TestData.OWNER), "the owner hash must not be shown");
        assertFalse(everything.contains(PUNISHMENT.toString()), "a raw UUID must not be shown");
    }

    // ---- console -------------------------------------------------------------------------------

    @Test
    void theConsoleGetsTheContentWithoutEventsOrButtons() {
        Rendered rendered = Rendered.english(temporaryBan(EVERYTHING, Mode.PLAIN));

        assertFalse(rendered.hasEvents(), "the console cannot hover or click");
        assertFalse(rendered.text().contains("[Undo]"), "no buttons for the console: " + rendered.text());
    }

    @Test
    void theConsoleGetsTheHoverDetailsInline() {
        Rendered rendered = Rendered.english(temporaryBan(EVERYTHING, Mode.PLAIN));

        assertEquals("Blackhole » ✔ Punished TheMeinerDev in Network.\n"
                + "  ▸ Step 1/2 · ends 2026-10-22 20:14 (Europe/Berlin), in 5 hours 59 minutes\n"
                + "  ▸ Score: 1000 → 900 (−100)\n"
                + "  ▸ #A1B2C3D4 · template spam_1 · reason: Spam", rendered.text());
    }

    // ---- errors --------------------------------------------------------------------------------

    @Test
    void anUnknownTemplateListsTheSimilarKeysAsSuggestions() {
        Rendered rendered = Rendered.english(StaffMessages.templateNotFound("ban", "Steve", "spamm", List.of("spam_1", "spam_2"), Mode.RICH));

        assertEquals("Blackhole » ✖ Template spamm was not found.\n"
                + "  ▸ Did you mean: spam_1, spam_2?\n"
                + "  ▸ Press TAB after the player name to list all templates.", rendered.text());
        assertEquals(List.of("/ban Steve spam_1", "/ban Steve spam_2"), rendered.clickValues());
    }

    @Test
    void anUnknownTemplateSaysWhenNothingMatches() {
        Rendered rendered = Rendered.english(StaffMessages.templateNotFound("ban", "Steve", "xyz", List.of(), Mode.RICH));

        assertTrue(rendered.text().contains("  ▸ No template matches \"xyz\"."), rendered.text());
        assertTrue(rendered.clicks().isEmpty(), "nothing to suggest");
    }

    @Test
    void theConsoleGetsTheSimilarKeysAsText() {
        Rendered rendered = Rendered.english(StaffMessages.templateNotFound("mute", "Steve", "spamm", List.of("spam_1"), Mode.PLAIN));

        assertTrue(rendered.text().contains("Did you mean: spam_1?"), rendered.text());
        assertFalse(rendered.hasEvents(), "the console cannot click");
    }

    @Test
    void aMuteTemplateUsedWithBanNamesTheMuteCommand() {
        Rendered rendered = Rendered.english(StaffMessages.wrongSection("Steve", "chat_spam", EnforcementKind.MUTE, Mode.RICH));

        assertEquals("Blackhole » ✖ chat_spam is a mute template. Use: /mute Steve chat_spam", rendered.text());
        assertEquals(List.of("/mute Steve chat_spam"), rendered.clickValues());
    }

    @Test
    void aBanTemplateUsedWithMuteNamesTheBanCommand() {
        Rendered rendered = Rendered.english(StaffMessages.wrongSection("Steve", "hacking", EnforcementKind.BAN, Mode.RICH));

        assertEquals("Blackhole » ✖ hacking is a ban template. Use: /ban Steve hacking", rendered.text());
    }

    @Test
    void aServerTemplateIsAlsoUsedWithBan() {
        Rendered rendered = Rendered.english(StaffMessages.wrongSection("Steve", "camping", EnforcementKind.SERVER, Mode.PLAIN));

        assertTrue(rendered.text().contains("Use: /ban Steve camping"), rendered.text());
        assertFalse(rendered.hasEvents(), "the console cannot click");
    }

    @Test
    void aTemplateOfAnUnknownSectionSaysSo() {
        Rendered rendered = Rendered.english(StaffMessages.wrongSection("Steve", "spam", null, Mode.RICH));

        assertTrue(rendered.text().contains("section of template spam is not known yet"), rendered.text());
    }

    @Test
    void theFailureLinesNameNoStatusCode() {
        for (String key : List.of("punishment.error.ban", "punishment.error.mute", "punishment.error.punish", "punishment.error.conflict")) {
            for (Rendered rendered : List.of(Rendered.english(Component.translatable(key)), Rendered.german(Component.translatable(key)))) {
                assertTrue(rendered.text().startsWith("Blackhole » ✖ "), key + ": " + rendered.text());
                assertFalse(rendered.text().matches(".*\\d.*"), key + " must not show a status code: " + rendered.text());
            }
        }
    }

    @Test
    void theEvidenceLinesCarryThePrefix() {
        assertEquals("Blackhole » ✔ Chat evidence attached (12 lines).",
                Rendered.english(Component.translatable("punishment.evidence.attached", Component.text(12))).text());
        assertTrue(Rendered.english(Component.translatable("punishment.evidence.failed")).text().startsWith("Blackhole » ⚠ "));
    }

    // ---- usage and argument errors -------------------------------------------------------------

    @Test
    void wrongSyntaxShowsTheUsageWithThePlayerArgument() {
        Rendered rendered = Rendered.english(StaffMessages.usage("ban <user> <template>", Mode.RICH));

        assertEquals("Blackhole » ✖ That command was not used correctly.\n  ▸ Usage: /ban <player> <template>", rendered.text());
    }

    @Test
    void theUsageLineSuggestsTheCommandItself() {
        Rendered rendered = Rendered.english(StaffMessages.usage("ban <user> <template>", Mode.RICH));

        assertEquals(List.of("/ban "), rendered.clickValues());
    }

    @Test
    void theConsoleGetsTheUsageWithoutEvents() {
        assertFalse(Rendered.english(StaffMessages.usage("mute <user> <template>", Mode.PLAIN)).hasEvents());
    }

    @Test
    void theUsageIsGermanForAGermanClient() {
        Rendered rendered = Rendered.german(StaffMessages.usage("punish <user> <template>", Mode.RICH));

        assertTrue(rendered.text().endsWith("Verwendung: /punish <player> <template>"), rendered.text());
    }

    @Test
    void anUnknownTargetSaysItIsNeitherOnlineNorKnownAndHowToFindPlayers() {
        Rendered rendered = Rendered.english(StaffMessages.argumentError(new TargetNotFoundException("Nobody")));

        assertEquals("Blackhole » ✖ No player named Nobody is online or known.\n"
                + "  ▸ Names are case-insensitive; press TAB to see the online players.", rendered.text());
    }

    @Test
    void anUnknownTargetIsGermanForAGermanClient() {
        Rendered rendered = Rendered.german(StaffMessages.argumentError(new TargetNotFoundException("Nobody")));

        assertTrue(rendered.text().startsWith("Blackhole » ✖ Kein Spieler namens Nobody ist online oder bekannt."), rendered.text());
    }

    @Test
    void anyOtherArgumentErrorIsShownAsItWasDescribed() {
        Rendered rendered = Rendered.english(StaffMessages.argumentError(new IllegalArgumentException("'x' is not a number")));

        assertEquals("Blackhole » ✖ Invalid argument: 'x' is not a number", rendered.text());
    }

    @Test
    void thePlayerOnlyAnswerIsStyledAndHasNoEvents() {
        assertEquals("Blackhole » ✖ That command can only be used by a player.",
                Rendered.english(StaffMessages.playerOnly()).text(), "English");
        assertEquals("Blackhole » ✖ Dieser Befehl kann nur von Spielern verwendet werden.",
                Rendered.german(StaffMessages.playerOnly()).text(), "German");
        assertFalse(Rendered.english(StaffMessages.playerOnly()).hasEvents(), "plain for the console");
    }
}
