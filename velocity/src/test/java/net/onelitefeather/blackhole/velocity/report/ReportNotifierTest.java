package net.onelitefeather.blackhole.velocity.report;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.nats.NatsSyncService.ReportCreated;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who is told about a new report, from which data, and that the lookup never blocks or loses the notice. */
class ReportNotifierTest {

    private static final UUID REPORT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID REPORTED_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final String REPORTER_HASH = UUIDConverter.convertToSHA(REPORTER_ID);
    private static final String REPORTED_HASH = UUIDConverter.convertToSHA(REPORTED_ID);
    private static final ReportNotice NOTICE = new ReportNotice(REPORT.toString(), "Alex", "Steve", "CHEATING", "flying");

    private final List<UUID> lookedUp = new ArrayList<>();
    private ReportDTO report = new ReportDTO().identifier(REPORT).reporterHash(REPORTER_HASH).reportedHash(REPORTED_HASH)
            .category(ReportCategory.CHEATING).description("flying around   the lobby").metaData(Map.of());
    private ApiException lookupFailure;

    /** A backend that only knows the single-report lookup. */
    private final net.onelitefeather.blackhole.velocity.punish.BackendGateway backend =
            (net.onelitefeather.blackhole.velocity.punish.BackendGateway) Proxy.newProxyInstance(
                    ReportNotifierTest.class.getClassLoader(), new Class<?>[]{net.onelitefeather.blackhole.velocity.punish.BackendGateway.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("report")) {
                            lookedUp.add((UUID) args[0]);
                            if (lookupFailure != null) {
                                throw lookupFailure;
                            }
                            return report;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });

    /** A player that records what it is told and holds the given permissions. */
    private static Player player(UUID uuid, String name, Set<String> permissions, List<Component> received) {
        return (Player) Proxy.newProxyInstance(ReportNotifierTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permissions.contains((String) args[0]);
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> name;
                    case "sendMessage" -> {
                        if (args.length > 0 && args[0] instanceof Component component) {
                            received.add(component);
                        }
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Player staff(List<Component> received) {
        return player(UUID.randomUUID(), "Mod", Set.of(Permissions.REPORT_NOTIFY), received);
    }

    private ReportNotifier notifier(boolean busConnected, Player... online) {
        return new ReportNotifier(() -> List.of(online), () -> null, backend, () -> busConnected, Runnable::run);
    }

    private static ReportCreated event() {
        return new ReportCreated(REPORT.toString(), REPORTER_HASH, REPORTED_HASH, "CHEATING");
    }

    private static String text(List<Component> received) {
        return received.stream().map(Component::toString).reduce("", String::concat);
    }

    @Test
    void whenTheBusIsUpTheAnnouncingProxyLeavesTheNoticeToTheEvent() {
        List<Component> told = new ArrayList<>();

        notifier(true, staff(told)).announce(NOTICE);

        assertTrue(told.isEmpty(), "the report.created event reaches this proxy too; telling staff now would double the notice");
    }

    @Test
    void whenTheBusIsDownTheAnnouncingProxyTellsItsOwnStaff() {
        List<Component> told = new ArrayList<>();

        notifier(false, staff(told)).announce(NOTICE);

        assertEquals(1, told.size(), "a bus outage must not swallow the notice");
    }

    @Test
    void aReportEventTellsStaffWithTheNamesOfTheOnlinePlayers() {
        List<Component> told = new ArrayList<>();
        notifier(true, staff(told), player(REPORTER_ID, "Alex", Set.of(), new ArrayList<>()),
                player(REPORTED_ID, "Steve", Set.of(), new ArrayList<>())).reportCreated(event());

        assertEquals(List.of(REPORT), lookedUp, "the report is read over HTTP");
        assertEquals(1, told.size());
        assertTrue(text(told).contains("Alex") && text(told).contains("Steve"), text(told));
        assertTrue(text(told).contains("flying around the lobby"), "the excerpt comes from the report: " + text(told));
    }

    @Test
    void anOfflinePlayersNameComesFromTheReport() {
        report = report.metaData(Map.of("reporterName", "Alex", "reportedName", "Steve"));
        List<Component> told = new ArrayList<>();

        notifier(true, staff(told)).reportCreated(event());

        assertTrue(text(told).contains("Alex") && text(told).contains("Steve"), text(told));
    }

    @Test
    void aPlayerWithoutAnyKnownNameGetsAPlaceholderNotAHash() {
        List<Component> told = new ArrayList<>();

        notifier(true, staff(told)).reportCreated(event());

        assertEquals(1, told.size());
        assertTrue(!text(told).contains(REPORTER_HASH) && !text(told).contains(REPORTED_HASH), "a hash must never be shown to staff");
    }

    @Test
    void aFailedLookupStillTellsStaff() {
        lookupFailure = new ApiException(500, "boom", null, null);
        List<Component> told = new ArrayList<>();

        notifier(true, staff(told), player(REPORTED_ID, "Steve", Set.of(), new ArrayList<>())).reportCreated(event());

        assertEquals(1, told.size(), "the notice survives a backend hiccup, without the excerpt");
        assertTrue(text(told).contains("Steve"));
    }

    @Test
    void playersWithoutThePermissionAreNotTold() {
        List<Component> players = new ArrayList<>();

        notifier(true, player(UUID.randomUUID(), "Pat", Set.of(), players)).reportCreated(event());

        assertTrue(players.isEmpty(), "ordinary players must never see report notices");
    }

    @Test
    void withoutStaffOnlineTheReportIsNotEvenLookedUp() {
        notifier(true, player(UUID.randomUUID(), "Pat", Set.of(), new ArrayList<>())).reportCreated(event());

        assertTrue(lookedUp.isEmpty(), "no staff, no HTTP call");
    }

    @Test
    void everyStaffMemberOfAProxyIsToldOnce() {
        List<Component> a = new ArrayList<>();
        List<Component> b = new ArrayList<>();

        notifier(true, staff(a), staff(b)).reportCreated(event());

        assertEquals(1, a.size());
        assertEquals(1, b.size());
    }
}
