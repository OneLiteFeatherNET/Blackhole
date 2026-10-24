package net.onelitefeather.blackhole.discord.gateway;

import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The slash commands as registered and the rule that JDA stays inside the gateway package. No connection is made. */
class GatewayContractTest {

    @Test
    void theAccountAndAppealCommandsAreRegisteredWithAGermanDescription() {
        List<CommandData> commands = JdaGateway.commandData();

        assertEquals(List.of("link", "unlink", "whoami", "appeal"), commands.stream().map(CommandData::getName).toList(), "command names");
        for (CommandData command : commands) {
            SlashCommandData slash = (SlashCommandData) command;
            assertFalse(slash.getDescriptionLocalizations().toMap().get(DiscordLocale.GERMAN).isBlank(), command.getName() + " has a German description");
        }
    }

    @Test
    void theLinkCommandTakesOneRequiredCodeOption() {
        SlashCommandData link = (SlashCommandData) JdaGateway.commandData().getFirst();

        assertEquals(1, link.getOptions().size(), "one option");
        assertEquals("code", link.getOptions().getFirst().getName(), "named code");
        assertTrue(link.getOptions().getFirst().isRequired(), "required");
    }

    @Test
    void theAppealCommandTakesNoOptionBecauseTheBanIdIsTypedIntoAModal() {
        SlashCommandData appeal = (SlashCommandData) JdaGateway.commandData().getLast();

        assertEquals("appeal", appeal.getName(), "last command");
        assertTrue(appeal.getOptions().isEmpty(), "a slash option would show the ban id in the command line");
    }

    @Test
    void theGatewayConnectsWithGuildMessagesAndMessageContentOnly() {
        assertEquals(java.util.EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT), JdaGateway.intents(),
                "no member list, no presence: only messages and their content");
    }

    @Test
    void permissionsMapToDiscordsOnesWithoutAdministrator() {
        for (net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission permission
                : net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission.values()) {
            assertFalse(JdaGateway.jda(permission) == net.dv8tion.jda.api.Permission.ADMINISTRATOR, permission + " is never administrator");
        }
    }
}
