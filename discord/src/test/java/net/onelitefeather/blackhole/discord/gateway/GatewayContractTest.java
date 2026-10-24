package net.onelitefeather.blackhole.discord.gateway;

import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The slash commands as registered and the rule that JDA stays inside the gateway package. No connection is made. */
class GatewayContractTest {

    @Test
    void theThreeAccountCommandsAreRegisteredWithAGermanDescription() {
        List<CommandData> commands = JdaGateway.commandData();

        assertEquals(List.of("link", "unlink", "whoami"), commands.stream().map(CommandData::getName).toList(), "command names");
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
    void theCommandLogicBackendAndConfigNeverReferenceJda() throws IOException {
        for (Class<?> type : List.of(
                net.onelitefeather.blackhole.discord.command.AccountCommands.class,
                net.onelitefeather.blackhole.discord.backend.BackendGateway.class,
                net.onelitefeather.blackhole.discord.backend.HttpBackendGateway.class,
                net.onelitefeather.blackhole.discord.config.BotConfig.class)) {
            try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                String bytecode = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(bytecode.contains("net/dv8tion"), type.getName() + " must not depend on JDA");
            }
        }
    }
}
