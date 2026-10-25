package net.onelitefeather.blackhole.velocity.command;

import net.onelitefeather.blackhole.client.model.KickScope;
import net.onelitefeather.blackhole.velocity.command.KickArguments.Invalid;
import net.onelitefeather.blackhole.velocity.command.KickArguments.Parsed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How {@code /kick <player> [server|section] <reason>} reads what follows the player. No framework runs. */
class KickCommandParsingTest {

    private static Parsed parsed(String input) {
        return assertInstanceOf(Parsed.class, KickArguments.parse(input), input);
    }

    @Test
    void aBareReasonIsANetworkKick() {
        Parsed result = parsed("spamming the chat");

        assertEquals(KickScope.NETWORK, result.scope());
        assertNull(result.target());
        assertEquals("spamming the chat", result.reason());
    }

    @Test
    void theServerKeywordNamesAServerAndTheRestIsTheReason() {
        Parsed result = parsed("server bedwars-1 away from keyboard");

        assertEquals(KickScope.SERVER, result.scope());
        assertEquals("bedwars-1", result.target());
        assertEquals("away from keyboard", result.reason());
    }

    @Test
    void theSectionKeywordNamesASectionAndTheRestIsTheReason() {
        Parsed result = parsed("section bedwars cheating suspicion");

        assertEquals(KickScope.SECTION, result.scope());
        assertEquals("bedwars", result.target());
        assertEquals("cheating suspicion", result.reason());
    }

    @Test
    void theKeywordIsReadIgnoringCase() {
        assertEquals(KickScope.SERVER, parsed("SERVER lobby-1 too loud").scope());
    }

    @Test
    void surroundingBlanksAreIgnored() {
        assertEquals("too loud", parsed("   server lobby-1    too loud  ").reason());
    }

    @Test
    void aMissingReasonIsRefused() {
        assertInstanceOf(Invalid.class, KickArguments.parse(""));
        assertInstanceOf(Invalid.class, KickArguments.parse("   "));
    }

    @Test
    void aScopeWithoutAReasonIsRefused() {
        assertInstanceOf(Invalid.class, KickArguments.parse("server bedwars-1"));
        assertInstanceOf(Invalid.class, KickArguments.parse("section bedwars"));
    }

    @Test
    void aScopeWithoutATargetIsRefused() {
        assertInstanceOf(Invalid.class, KickArguments.parse("server"));
    }

    @Test
    void aReasonLongerThanTheBackendAllowsIsRefused() {
        assertInstanceOf(Invalid.class, KickArguments.parse("x".repeat(KickArguments.MAX_REASON + 1)));
        assertEquals(KickArguments.MAX_REASON, parsed("x".repeat(KickArguments.MAX_REASON)).reason().length());
    }

    @Test
    void aMassKickTargetNamesASectionOrAServerWithAPrefix() {
        assertEquals(KickScope.SECTION, ((Parsed) KickArguments.parseMass("section:bedwars", "maintenance", key -> false)).scope());
        assertEquals(KickScope.SERVER, ((Parsed) KickArguments.parseMass("server:bedwars", "maintenance", key -> true)).scope());
    }

    @Test
    void aBareMassKickTargetIsASectionWhenTheSectionExistsOtherwiseAServer() {
        Parsed section = (Parsed) KickArguments.parseMass("bedwars", "maintenance", key -> key.equals("bedwars"));
        Parsed server = (Parsed) KickArguments.parseMass("lobby-1", "maintenance", key -> key.equals("bedwars"));

        assertEquals(KickScope.SECTION, section.scope());
        assertEquals(KickScope.SERVER, server.scope());
        assertEquals("lobby-1", server.target());
    }

    @Test
    void aMassKickWithoutAReasonIsRefused() {
        assertInstanceOf(Invalid.class, KickArguments.parseMass("bedwars", "  ", key -> true));
    }
}
