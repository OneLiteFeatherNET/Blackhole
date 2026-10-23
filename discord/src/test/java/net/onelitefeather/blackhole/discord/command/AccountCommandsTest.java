package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.LinkEntry;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.LinkKind;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.LinkResult;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.UnlinkResult;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.WhoamiResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountCommandsTest {

    private static final String USER = "123456789012345678";

    private final FakeBackend backend = new FakeBackend();
    private final AccountCommands commands = new AccountCommands(this.backend);

    @Test
    void theCodeIsSentAsTypedWithoutSurroundingBlanksTogetherWithTheCallingUser() {
        this.commands.link(Lang.EN, USER, "  abcd-efgh ");

        assertEquals(List.of(USER + "/abcd-efgh"), this.backend.confirmed, "the backend normalises, the bot only trims");
    }

    @Test
    void aBlankCodeIsAskedForAndNeverReachesTheBackend() {
        assertEquals("Please enter your link code, for example `/link ABCD-EFGH`.", this.commands.link(Lang.EN, USER, "  "), "english");
        assertEquals("Bitte gib deinen Code an, zum Beispiel `/link ABCD-EFGH`.", this.commands.link(Lang.DE, USER, null), "german");
        assertTrue(this.backend.confirmed.isEmpty(), "nothing was sent");
    }

    @Test
    void aStaffLinkNamesTheStaffMemberInBothLanguages() {
        this.backend.linkResult = new LinkResult.Linked(LinkKind.STAFF, "Alex Admin");

        assertEquals("Your Discord account is now linked to the staff account **Alex Admin**.", this.commands.link(Lang.EN, USER, "ABCD-EFGH"), "english");
        assertEquals("Dein Discord-Account ist jetzt mit dem Team-Account **Alex Admin** verknüpft.", this.commands.link(Lang.DE, USER, "ABCD-EFGH"), "german");
    }

    @Test
    void aStaffLinkWithoutANameIsStillConfirmed() {
        this.backend.linkResult = new LinkResult.Linked(LinkKind.STAFF, null);

        assertEquals("Your Discord account is now linked to your staff account.", this.commands.link(Lang.EN, USER, "ABCD-EFGH"), "unnamed");
    }

    @Test
    void aPlayerLinkNeverMentionsAStaffAccount() {
        this.backend.linkResult = new LinkResult.Linked(LinkKind.PLAYER, null);

        assertEquals("Your Discord account is now linked to your Minecraft account.", this.commands.link(Lang.EN, USER, "ABCD-EFGH"), "english");
        assertEquals("Dein Discord-Account ist jetzt mit deinem Minecraft-Account verknüpft.", this.commands.link(Lang.DE, USER, "ABCD-EFGH"), "german");
    }

    @Test
    void anInvalidCodeTellsHowToGetANewOne() {
        this.backend.linkResult = new LinkResult.InvalidCode();

        assertTrue(this.commands.link(Lang.EN, USER, "ABCD-EFGH").contains("invalid or has expired"), "english");
        assertTrue(this.commands.link(Lang.DE, USER, "ABCD-EFGH").contains("ungültig oder abgelaufen"), "german");
    }

    @Test
    void aBackendFailureIsNotMistakenForAnInvalidCode() {
        this.backend.linkResult = new LinkResult.Unavailable(503);

        assertEquals("The ban system is not reachable right now. Please try again later.", this.commands.link(Lang.EN, USER, "ABCD-EFGH"), "english");
    }

    @Test
    void unlinkingConfirmsAndTheBackendGetsTheCallingUser() {
        this.backend.unlinkResult = new UnlinkResult.Removed(1);

        assertEquals("Your Discord link was removed.", this.commands.unlink(Lang.EN, USER), "english");
        assertEquals("Deine Discord-Verknüpfung wurde entfernt.", this.commands.unlink(Lang.DE, USER), "german");
        assertEquals(List.of(USER, USER), this.backend.unlinked, "only the caller is unlinked");
    }

    @Test
    void unlinkingAnUnlinkedAccountSaysSo() {
        this.backend.unlinkResult = new UnlinkResult.NotLinked();

        assertEquals("This Discord account is not linked.", this.commands.unlink(Lang.EN, USER), "english");
    }

    @Test
    void whoamiListsEveryLinkOfTheUser() {
        this.backend.whoamiResult = new WhoamiResult.Found(List.of(
                new LinkEntry(LinkKind.STAFF, "Alex Admin"), new LinkEntry(LinkKind.PLAYER, null)));

        assertEquals("Your Discord account is linked as:\n- staff member **Alex Admin**\n- player (Minecraft account)",
                this.commands.whoami(Lang.EN, USER), "english");
        assertEquals("Dein Discord-Account ist verknüpft als:\n- Team-Mitglied **Alex Admin**\n- Spieler (Minecraft-Account)",
                this.commands.whoami(Lang.DE, USER), "german");
    }

    @Test
    void whoamiOfAnUnlinkedUserSaysHowToLink() {
        assertTrue(this.commands.whoami(Lang.EN, USER).contains("not linked"), "english");
        assertTrue(this.commands.whoami(Lang.DE, USER).contains("nicht verknüpft"), "german");
    }

    @Test
    void whoamiReportsABackendFailure() {
        this.backend.whoamiResult = new WhoamiResult.Unavailable(0);

        assertEquals("The ban system is not reachable right now. Please try again later.", this.commands.whoami(Lang.EN, USER), "english");
    }

    @Test
    void aGermanDiscordLocaleGetsGermanAndEverythingElseEnglish() {
        assertEquals(Lang.DE, Lang.fromDiscordLocale("de"), "de");
        assertEquals(Lang.EN, Lang.fromDiscordLocale("en-US"), "en-US");
        assertEquals(Lang.EN, Lang.fromDiscordLocale("fr"), "other languages fall back to English");
        assertEquals(Lang.EN, Lang.fromDiscordLocale(null), "unknown");
    }
}
