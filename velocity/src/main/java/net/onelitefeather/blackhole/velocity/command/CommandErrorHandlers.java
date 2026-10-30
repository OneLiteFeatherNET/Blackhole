package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import net.onelitefeather.blackhole.velocity.component.Mode;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.InvalidCommandSenderException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.handling.ExceptionHandler;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Answers wrong syntax and unparsable arguments of the staff commands with a styled usage line instead
 * of Cloud's raw text. The command manager only holds the Blackhole commands, so the handlers cannot
 * touch the failures of other plugins. A missing permission is left to the proxy's own message.
 */
public final class CommandErrorHandlers {

    private CommandErrorHandlers() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Registers the handlers above and reports every other failure of a command to {@code unexpected}.
     * The failure is passed on afterwards, so the command fails exactly as it did without the report.
     *
     * @param manager    the command manager of the proxy
     * @param unexpected receives each failure that no handler above answers
     */
    public static void register(@NotNull CommandManager<CommandSource> manager, @NotNull Consumer<Throwable> unexpected) {
        register(manager);
        manager.exceptionController().registerHandler(Throwable.class,
                ExceptionHandler.passThroughHandler(context -> unexpected.accept(context.exception())));
    }

    public static void register(@NotNull CommandManager<CommandSource> manager) {
        manager.exceptionController().registerHandler(InvalidSyntaxException.class, context -> {
            CommandSource sender = context.context().sender();
            sender.sendMessage(StaffMessages.usage(context.exception().correctSyntax(), Mode.of(sender)));
        });
        manager.exceptionController().registerHandler(InvalidCommandSenderException.class, context ->
                context.context().sender().sendMessage(StaffMessages.playerOnly()));
        manager.exceptionController().registerHandler(ArgumentParseException.class, context -> {
            CommandSource sender = context.context().sender();
            sender.sendMessage(StaffMessages.argumentError(context.exception().getCause()));
        });
    }
}
