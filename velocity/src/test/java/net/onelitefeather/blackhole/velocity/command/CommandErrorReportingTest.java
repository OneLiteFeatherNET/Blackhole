package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An unexpected failure of a command is reported once and still fails the command as before; a failure
 * the handlers already answer (a usage line) is not reported at all.
 */
class CommandErrorReportingTest {

    @Test
    void anUnexpectedFailureIsReportedAndStillFailsTheCommand() throws Exception {
        CommandManager<CommandSource> manager = manager();
        List<Throwable> reported = new ArrayList<>();
        CommandErrorHandlers.register(manager, reported::add);
        IllegalStateException failure = new IllegalStateException("backend exploded");
        manager.command(manager.commandBuilder("explode").handler(context -> {
            throw failure;
        }).build());

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> manager.commandExecutor().executeCommand(source(), "explode").get());

        assertEquals(1, reported.size(), "the unexpected failure is reported exactly once: " + reported);
        assertSame(failure, unwrap(reported.get(0)), "the reported failure is the one the command threw");
        assertNotNull(thrown.getCause(), "the command still fails, as it did before the reporter existed");
    }

    @Test
    void aSyntaxErrorTheHandlersAnswerIsNotReported() throws Exception {
        CommandManager<CommandSource> manager = manager();
        List<Throwable> reported = new ArrayList<>();
        CommandErrorHandlers.register(manager, reported::add);
        manager.command(manager.commandBuilder("plain").handler(context -> { }).build());

        // Too many words: the usage handler answers the sender, so this is an expected failure.
        try {
            manager.commandExecutor().executeCommand(source(), "plain surplus").get();
        } catch (ExecutionException expected) {
            // Cloud still completes the future with the syntax error; the question here is only what is reported.
        }

        assertEquals(0, reported.size(), "an answered syntax error is not an error of the plugin: " + reported);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException
                || current.getClass().getSimpleName().equals("CommandExecutionException")) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static CommandSource source() {
        return (CommandSource) Proxy.newProxyInstance(CommandSource.class.getClassLoader(), new Class<?>[]{CommandSource.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
    }

    private static CommandManager<CommandSource> manager() {
        return new PlainManager();
    }

    /** A Cloud manager with no registration target and no permission system, as in the registration test. */
    private static final class PlainManager extends CommandManager<CommandSource> {

        PlainManager() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(CommandSource sender, String permission) {
            return true;
        }
    }
}
