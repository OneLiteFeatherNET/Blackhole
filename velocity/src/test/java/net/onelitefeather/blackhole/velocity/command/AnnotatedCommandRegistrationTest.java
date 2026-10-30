package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import net.onelitefeather.blackhole.velocity.guard.AreaBanCommand;
import net.onelitefeather.blackhole.velocity.guard.GuardCommand;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The annotated command classes BlackholeVelocity parses into one Cloud manager, in its order. Two
 * commands with a differently parsed argument of the same name are an ambiguous node, and Cloud throws
 * while the plugin starts, so every class is parsed here into a fresh manager that has no network and no
 * Velocity proxy behind it. Only the annotations are read: the constructor dependencies are not needed.
 */
class AnnotatedCommandRegistrationTest {

    /** The parse calls of BlackholeVelocity.onProxyInitialisation, in the same order. */
    private static final List<Class<?>> REGISTERED = List.of(
            TargetParser.class,
            PunishCommand.class,
            PunishInfoCommand.class,
            NameHistoryCommand.class,
            PardonCommand.class,
            KickCommand.class,
            KickAllCommand.class,
            WarnCommand.class,
            WarningAckCommand.class,
            EloCommand.class,
            LinkCommand.class,
            ReportCommand.class,
            DiscordCommand.class,
            GuardCommand.class,
            AreaBanCommand.class);

    @Test
    void everyRegisteredCommandClassParsesIntoOneManagerWithoutAnAmbiguousNode() throws ReflectiveOperationException {
        CommandManager<CommandSource> manager = new PlainManager();
        AnnotationParser<CommandSource> parser = new AnnotationParser<>(manager, CommandSource.class);

        for (Class<?> type : REGISTERED) {
            Object instance = bareInstance(type);
            assertDoesNotThrow(() -> parser.parse(instance), "parsing " + type.getSimpleName() + " must not throw");
        }
    }

    @Test
    void theGuardCommandsAreRegisteredAsRootCommands() throws ReflectiveOperationException {
        CommandManager<CommandSource> manager = new PlainManager();
        AnnotationParser<CommandSource> parser = new AnnotationParser<>(manager, CommandSource.class);
        for (Class<?> type : REGISTERED) {
            parser.parse(bareInstance(type));
        }

        List<String> roots = List.copyOf(manager.rootCommands());
        assertTrue(roots.contains("guard"), "the /guard command is not registered: " + roots);
        assertTrue(roots.contains("areaban"), "the /areaban command is not registered: " + roots);
    }

    /** A Cloud manager with no registration target and no permission system: parsing is all it does here. */
    private static final class PlainManager extends CommandManager<CommandSource> {

        PlainManager() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(CommandSource sender, String permission) {
            return true;
        }
    }

    /**
     * An instance of the command class without running its constructor: the injected dependencies are
     * not needed to read the annotations, and building them all would test Guice wiring, not parsing.
     */
    private static Object bareInstance(Class<?> type) throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }
}
