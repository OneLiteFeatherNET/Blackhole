package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import net.onelitefeather.blackhole.velocity.component.StaffMessages;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.services.type.ConsumerService;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/**
 * Stops every staff command, which all need the backend, with a clear message while the proxy has no
 * complete credentials for it. Enforcement does not go through commands and is unaffected.
 */
public final class BackendAuthGuard {

    private BackendAuthGuard() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param configured whether the credentials are complete
     */
    public static void register(@NotNull CommandManager<CommandSource> manager, @NotNull BooleanSupplier configured) {
        manager.registerCommandPostProcessor(context -> {
            if (!configured.getAsBoolean()) {
                context.commandContext().sender().sendMessage(StaffMessages.authNotConfigured());
                ConsumerService.interrupt();
            }
        });
    }
}
