package net.onelitefeather.blackhole.velocity.command;

import net.onelitefeather.blackhole.velocity.FakeWarningBackend;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.enforcement.WarningHandler;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidence;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;

import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code /warn} command's declaration: its permission, its template argument, and a refusal without one. */
class WarnCommandParsingTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");
    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000a1ec");

    private final Method warnPlayer = warnPlayerMethod();

    private static Method warnPlayerMethod() {
        try {
            return WarnCommand.class.getMethod("warnPlayer", CommandContext.class, ResolvedTarget.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("WarnCommand must declare warnPlayer(context, user, template)", e);
        }
    }

    @Test
    void theWarnCommandNeedsTheWarnPermission() {
        String[] permissions = this.warnPlayer.getAnnotation(Permission.class).value();

        assertTrue(Arrays.asList(permissions).contains("blackhole.warn"), Arrays.toString(permissions));
    }

    @Test
    void everyFormOfTheWarnCommandNamesATemplateArgument() {
        Command[] forms = this.warnPlayer.getAnnotationsByType(Command.class);

        assertEquals(2, forms.length, "the short and the blackhole form");
        for (Command form : forms) {
            assertTrue(form.value().contains("<user> ") && form.value().endsWith("<template>"),
                    "a warning names the player and the template: " + form.value());
        }
    }

    @Test
    void aWarnWithoutATemplateIsRefusedBeforeTheBackendIsAsked() {
        FakeWarningBackend backend = new FakeWarningBackend();
        PunishService service = new PunishService(backend.gateway(), standardRegistry(), ChatEvidence.create(
                new ChatSnapshotBuilder(new ChatBuffer(Clock.systemUTC(), new ChatCaptureConfig()), new ChatCaptureConfig()),
                backend.gateway(), new ChatCaptureConfig()));
        WarnCommand command = new WarnCommand(service, new WarningHandler(backend.gateway(), Runnable::run));
        RecordingPlayer staff = new RecordingPlayer(STAFF, "Staff");

        command.handle(staff.player(), new ResolvedTarget(ALEX, "Alex", null), "   ");

        assertTrue(backend.applied.isEmpty(), "no apply without a template");
        assertTrue(staff.texts().getFirst().contains("Usage"), staff.texts().toString());
    }
}
