package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Commands;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Which commands the console may run: the read-only ones, nothing that punishes. */
class CommandSenderTypesTest {

    private static List<Type> senderTypes(Class<?> commands) {
        List<Method> methods = Arrays.stream(commands.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Command.class) || method.isAnnotationPresent(Commands.class))
                .toList();
        assertFalse(methods.isEmpty(), commands.getSimpleName() + " must declare commands");
        return methods.stream()
                .map(method -> ((ParameterizedType) Arrays.stream(method.getGenericParameterTypes())
                        .filter(type -> type instanceof ParameterizedType parameterized && parameterized.getRawType() == CommandContext.class)
                        .findFirst().orElseThrow()).getActualTypeArguments()[0])
                .toList();
    }

    private static void assertSenders(Class<?> commands, Class<?> expected) {
        for (Type type : senderTypes(commands)) {
            assertEquals(expected, type, commands.getSimpleName() + " sender type");
        }
    }

    @Test
    void eloIsOpenToTheConsole() {
        assertSenders(EloCommand.class, CommandSource.class);
    }

    @Test
    void infoAndHistoryAreOpenToTheConsole() {
        assertSenders(PunishInfoCommand.class, CommandSource.class);
    }

    @Test
    void banMutePunishRemainPlayerOnly() {
        assertSenders(PunishCommand.class, Player.class);
    }

    @Test
    void unbanAndUnmuteRemainPlayerOnly() {
        assertSenders(PardonCommand.class, Player.class);
    }

    @Test
    void linkAndWhoamiArePlayerOnly() {
        assertSenders(LinkCommand.class, Player.class);
    }
}
