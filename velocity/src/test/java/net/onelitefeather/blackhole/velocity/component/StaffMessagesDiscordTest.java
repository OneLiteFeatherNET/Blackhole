package net.onelitefeather.blackhole.velocity.component;

import net.onelitefeather.blackhole.velocity.punish.DiscordLinkService.CodeOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The feedback of /blackhole discord: one test per outcome. */
class StaffMessagesDiscordTest {

    @Test
    void theCodeMessageShowsTheCodeTheCommandAndTheLifetimeInBothLanguages() {
        CodeOutcome issued = new CodeOutcome.Issued("ABCD-EFGH", 10);

        assertEquals("Blackhole » ✔ Your Discord link code is ABCD-EFGH (valid for 10 min). Type /link ABCD-EFGH in the Discord server.",
                Rendered.english(StaffMessages.discordCode(issued)).text(), "english");
        assertEquals("Blackhole » ✔ Dein Discord-Code ist ABCD-EFGH (gültig für 10 Min.). Gib /link ABCD-EFGH auf dem Discord-Server ein.",
                Rendered.german(StaffMessages.discordCode(issued)).text(), "german");
    }

    @Test
    void theFailureMessageDoesNotMentionACode() {
        assertEquals("Blackhole » ✖ The Discord link code could not be created. Try again later.",
                Rendered.english(StaffMessages.discordCode(new CodeOutcome.Failed(500))).text(), "english");
    }
}
