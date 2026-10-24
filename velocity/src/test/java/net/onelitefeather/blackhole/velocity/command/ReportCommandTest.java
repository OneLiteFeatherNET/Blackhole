package net.onelitefeather.blackhole.velocity.command;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import net.onelitefeather.blackhole.velocity.report.ReportEvidence;
import net.onelitefeather.blackhole.velocity.report.ReportNotice;
import net.onelitefeather.blackhole.velocity.report.ReportNotifier;
import net.onelitefeather.blackhole.velocity.report.ReportService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /report command without the command framework: a fake sender, a fake backend, no proxy. */
class ReportCommandTest {

    private static final UUID REPORTER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID REPORTED = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private final List<String> submitted = new ArrayList<>();
    private ApiException failure;
    private final BackendGateway backend = (BackendGateway) Proxy.newProxyInstance(
            ReportCommandTest.class.getClassLoader(), new Class<?>[]{BackendGateway.class},
            (proxy, method, args) -> {
                if (method.getName().equals("submitReport")) {
                    submitted.add(args[2] + "|" + args[3]);
                    if (failure != null) {
                        throw failure;
                    }
                    return new ReportDTO().identifier(UUID.randomUUID());
                }
                throw new UnsupportedOperationException(method.getName());
            });
    private final ChatCaptureConfig config = new ChatCaptureConfig();
    /** What the online staff member of this proxy was told. */
    private final List<String> announced = new ArrayList<>();
    private final Player staff = player(UUID.randomUUID(), Tristate.TRUE, true, announced);
    /** The bus is down, so the proxy that took the report tells its own staff. */
    private final ReportNotifier notifier = new ReportNotifier(() -> List.of(staff), () -> null, backend, () -> false, Runnable::run);
    private final ReportCommand command = new ReportCommand(new ReportService(backend,
            new ReportEvidence(new ChatSnapshotBuilder(new ChatBuffer(new MutableClock(), config), config), backend, config, () -> "c")), notifier);

    /** A player that records what it is told. */
    private static Player player(UUID uuid, Tristate reportPermission, boolean bypass, List<String> received) {
        return (Player) Proxy.newProxyInstance(ReportCommandTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> "Name";
                    case "getPermissionValue" -> reportPermission;
                    case "hasPermission" -> bypass;
                    case "sendMessage" -> {
                        if (args.length > 0 && args[0] instanceof Component component) {
                            received.add(component.toString());
                        }
                        yield null;
                    }
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aPlayerWithoutAnExplicitDenialMayReport() {
        List<String> told = new ArrayList<>();
        Player sender = player(REPORTER, Tristate.UNDEFINED, false, told);

        command.handle(sender, new ResolvedTarget(REPORTED, "Steve", null), "cheating", "flying");

        assertEquals(List.of("CHEATING|flying"), submitted, "an undefined permission counts as granted");
        assertEquals(1, told.size());
        assertTrue(told.getFirst().contains("report.success"), "the reporter must get the confirmation: " + told);
    }

    @Test
    void aFiledReportIsAnnouncedToStaffWithNamesAndExcerpt() {
        Player sender = player(REPORTER, Tristate.TRUE, false, new ArrayList<>());

        command.handle(sender, new ResolvedTarget(REPORTED, "Steve", null), "cheating", "flying around");

        assertEquals(1, announced.size());
        String notice = announced.getFirst();
        assertTrue(notice.contains("Name") && notice.contains("Steve"), "the notice names both players: " + notice);
        assertTrue(notice.contains("flying around"), "the notice carries the excerpt: " + notice);
    }

    @Test
    void aRefusedReportIsNotAnnounced() {
        failure = new ApiException(429, "limit", null, null);

        command.handle(player(REPORTER, Tristate.TRUE, false, new ArrayList<>()), new ResolvedTarget(REPORTED, "Steve", null), "cheating", null);

        assertTrue(announced.isEmpty(), "staff must only hear about reports the backend recorded");
    }

    @Test
    void anExplicitlyDeniedPlayerCannotReport() {
        List<String> told = new ArrayList<>();
        Player sender = player(REPORTER, Tristate.FALSE, false, told);

        command.handle(sender, new ResolvedTarget(REPORTED, "Steve", null), "cheating", null);

        assertTrue(submitted.isEmpty());
        assertTrue(told.getFirst().contains("report.error.denied"));
    }

    @Test
    void anUnknownReasonIsAnsweredWithTheValidOnes() {
        List<String> told = new ArrayList<>();
        Player sender = player(REPORTER, Tristate.TRUE, false, told);

        command.handle(sender, new ResolvedTarget(REPORTED, "Steve", null), "fly", null);

        assertTrue(submitted.isEmpty());
        assertTrue(told.getFirst().contains("report.error.category"));
    }

    @Test
    void aStaffMemberWithTheBypassPermissionCannotBeReported() {
        List<String> told = new ArrayList<>();
        Player sender = player(REPORTER, Tristate.TRUE, false, told);
        Player staff = player(REPORTED, Tristate.TRUE, true, new ArrayList<>());

        command.handle(sender, new ResolvedTarget(REPORTED, "Steve", staff), "cheating", null);

        assertTrue(submitted.isEmpty());
        assertTrue(told.getFirst().contains("report.error.protected"));
    }

    @Test
    void theBackendsRateLimitBecomesAPleaseWaitMessage() {
        failure = new ApiException(429, "limit", null, null);
        List<String> told = new ArrayList<>();
        Player sender = player(REPORTER, Tristate.TRUE, false, told);

        command.handle(sender, new ResolvedTarget(REPORTED, "Steve", null), "cheating", null);

        assertTrue(told.getFirst().contains("report.error.rate_limited"));
    }

    @Test
    void reportingYourselfIsRefused() {
        List<String> told = new ArrayList<>();
        Player sender = player(REPORTER, Tristate.TRUE, false, told);

        command.handle(sender, new ResolvedTarget(REPORTER, "Name", sender), "cheating", null);

        assertTrue(submitted.isEmpty());
        assertTrue(told.getFirst().contains("report.error.self"));
    }
}
