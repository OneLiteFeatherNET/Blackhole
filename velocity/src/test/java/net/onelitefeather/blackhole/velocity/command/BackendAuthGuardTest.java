package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendAuthGuardTest {

    private static final class TestManager extends CommandManager<CommandSource> {
        TestManager() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(CommandSource sender, String permission) {
            return true;
        }
    }

    private static final class Sender {
        final List<Component> received = new ArrayList<>();
        final CommandSource source = (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(), new Class<?>[]{CommandSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args[0] instanceof Component message) {
                        this.received.add(message);
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
    }

    /** Runs {@code ban} on a manager whose only command counts its executions. */
    private static int run(boolean configured, Sender sender) {
        int[] executed = {0};
        CommandManager<CommandSource> manager = new TestManager();
        BackendAuthGuard.register(manager, () -> configured);
        manager.command(manager.commandBuilder("ban").handler(context -> executed[0]++));
        manager.commandExecutor().executeCommand(sender.source, "ban").handle((result, error) -> null).join();
        return executed[0];
    }

    @Test
    void withoutCredentialsTheCommandDoesNotRunAndTheSenderIsToldWhy() {
        Sender sender = new Sender();

        int executed = run(false, sender);

        assertEquals(0, executed, "the command must not run");
        assertEquals(1, sender.received.size(), "exactly one answer: " + sender.received);
        assertTrue(sender.received.getFirst() instanceof TranslatableComponent text
                        && text.key().equals("staff.error.auth_not_configured"),
                "the localized configuration error, got " + sender.received.getFirst());
    }

    @Test
    void withCredentialsTheCommandRunsAsUsual() {
        Sender sender = new Sender();

        int executed = run(true, sender);

        assertEquals(1, executed, "the command runs");
        assertEquals(0, sender.received.size(), "and nothing is said: " + sender.received);
    }
}
