package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import net.onelitefeather.blackhole.velocity.component.Mode;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.InvalidCommandSenderException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.jetbrains.annotations.NotNull;

/**
 * Answers wrong syntax and unparsable arguments of the staff commands with a styled usage line instead
 * of Cloud's raw text. The command manager only holds the Blackhole commands, so the handlers cannot
 * touch the failures of other plugins. A missing permission is left to the proxy's own message.
 */
public final class CommandErrorHandlers {

    private CommandErrorHandlers() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
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
