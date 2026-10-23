package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real annotated commands on a real Cloud manager with a console as sender, so a command that
 * is declared player-only but is not enforced as such fails here instead of on a live proxy.
 */
class CommandErrorHandlersTest {

    /** A Cloud manager without a platform; every sender may do everything. */
    private static final class TestManager extends CommandManager<CommandSource> {
        TestManager() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(CommandSource sender, String permission) {
            return true;
        }
    }

    /** A sender that is not a player and remembers what it was sent. */
    private static final class Console {
        final List<Component> received = new ArrayList<>();
        final CommandSource source = (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(), new Class<?>[]{CommandSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args[0] instanceof Component message) {
                        this.received.add(message);
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
    }

    private static CommandManager<CommandSource> manager() {
        CommandManager<CommandSource> manager = new TestManager();
        manager.parserRegistry().registerNamedParserSupplier("target", options ->
                (context, input) -> ArgumentParseResult.success(new ResolvedTarget(new UUID(0, 1), input.readString(), null)));
        manager.parserRegistry().registerSuggestionProvider("players", SuggestionProvider.blockingStrings((context, input) -> List.of()));
        CommandErrorHandlers.register(manager);
        AnnotationParser<CommandSource> parser = new AnnotationParser<>(manager, CommandSource.class);
        parser.parse(new PunishCommand(null, null, null, null, null));
        parser.parse(new PardonCommand(null, null, null));
        parser.parse(new EloCommand(null));
        parser.parse(new PunishInfoCommand(null, null, null));
        return manager;
    }

    private static List<Component> run(String input) {
        Console console = new Console();
        manager().commandExecutor().executeCommand(console.source, input).handle((result, error) -> null).join();
        return console.received;
    }

    private static void assertPlayerOnly(String input) {
        List<Component> received = run(input);

        assertEquals(1, received.size(), "'" + input + "' must answer exactly once, got " + received);
        assertTrue(received.getFirst() instanceof TranslatableComponent translatable && translatable.key().equals("staff.error.player_only"),
                "'" + input + "' must tell the console it is player-only, got " + received.getFirst());
    }

    @Test
    void banFromTheConsoleIsRefusedAsPlayerOnly() {
        assertPlayerOnly("ban TheMeinerLP spam");
    }

    @Test
    void muteFromTheConsoleIsRefusedAsPlayerOnly() {
        assertPlayerOnly("mute TheMeinerLP spam");
    }

    @Test
    void punishFromTheConsoleIsRefusedAsPlayerOnly() {
        assertPlayerOnly("punish TheMeinerLP spam");
    }

    @Test
    void unbanFromTheConsoleIsRefusedAsPlayerOnly() {
        assertPlayerOnly("unban TheMeinerLP");
    }

    @Test
    void unmuteFromTheConsoleIsRefusedAsPlayerOnly() {
        assertPlayerOnly("unmute TheMeinerLP");
    }

    @Test
    void theBlackholePrefixedFormsAreRefusedToo() {
        assertPlayerOnly("blackhole TheMeinerLP ban spam");
        assertPlayerOnly("blackhole TheMeinerLP unban");
    }

    @Test
    void anIncompleteCommandGetsTheUsageLine() {
        List<Component> received = run("elo");

        assertEquals(1, received.size(), "one usage message, got " + received);
        assertTrue(containsKey(received.getFirst(), "staff.error.syntax"), "must be the syntax error, got " + received.getFirst());
    }

    private static boolean containsKey(Component node, String key) {
        return (node instanceof TranslatableComponent translatable && translatable.key().equals(key))
                || node.children().stream().anyMatch(child -> containsKey(child, key));
    }
}
