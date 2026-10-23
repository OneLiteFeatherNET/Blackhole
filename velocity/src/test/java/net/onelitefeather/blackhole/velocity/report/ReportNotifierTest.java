package net.onelitefeather.blackhole.velocity.report;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who is told about a new report, once, on which proxy. The Redis channel is replaced by an in-memory bus. */
class ReportNotifierTest {

    private static final ReportNotice NOTICE = new ReportNotice("11111111-1111-4111-8111-111111111111", "Alex", "Steve", "CHEATING", "flying");

    /** Every notifier that listens on the bus gets each published notice once, like proxies on one Redis channel. */
    private static final class Bus implements ReportNoticeChannel {
        private final List<Consumer<ReportNotice>> listeners = new CopyOnWriteArrayList<>();
        int published;
        boolean down;

        @Override
        public void onNotice(Consumer<ReportNotice> listener) {
            this.listeners.add(listener);
        }

        @Override
        public boolean publish(ReportNotice notice) {
            this.published++;
            if (this.down) {
                return false;
            }
            this.listeners.forEach(listener -> listener.accept(notice));
            return true;
        }
    }

    /** A player that records what it is told and holds the given permissions. */
    private static Player player(Set<String> permissions, List<Component> received) {
        return (Player) Proxy.newProxyInstance(ReportNotifierTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permissions.contains((String) args[0]);
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

    private static ReportNotifier notifier(Bus bus, Player... players) {
        Collection<Player> online = List.of(players);
        return new ReportNotifier(() -> online, bus, () -> null);
    }

    @Test
    void staffWithThePermissionAreTold() {
        Bus bus = new Bus();
        List<Component> staff = new ArrayList<>();
        notifier(bus, player(Set.of(Permissions.REPORT_NOTIFY), staff)).announce(NOTICE);

        assertEquals(1, staff.size());
    }

    @Test
    void playersWithoutThePermissionAreNotTold() {
        Bus bus = new Bus();
        List<Component> players = new ArrayList<>();
        notifier(bus, player(Set.of(), players)).announce(NOTICE);

        assertTrue(players.isEmpty(), "ordinary players must never see report notices");
    }

    @Test
    void staffOnTwoProxiesAreEachToldExactlyOnce() {
        Bus bus = new Bus();
        List<Component> onFirst = new ArrayList<>();
        List<Component> onSecond = new ArrayList<>();
        ReportNotifier first = notifier(bus, player(Set.of(Permissions.REPORT_NOTIFY), onFirst));
        notifier(bus, player(Set.of(Permissions.REPORT_NOTIFY), onSecond));

        first.announce(NOTICE);

        assertEquals(1, bus.published, "the proxy that took the report publishes one notice");
        assertEquals(1, onFirst.size(), "the publishing proxy must not tell its staff twice");
        assertEquals(1, onSecond.size(), "staff on another proxy must be told as well");
    }

    @Test
    void withoutAChannelTheAnnouncingProxyTellsItsOwnStaff() {
        Bus bus = new Bus();
        bus.down = true;
        List<Component> staff = new ArrayList<>();
        notifier(bus, player(Set.of(Permissions.REPORT_NOTIFY), staff)).announce(NOTICE);

        assertEquals(1, staff.size(), "a Redis outage must not swallow the notice");
    }

    @Test
    void everyStaffMemberOfAProxyIsTold() {
        Bus bus = new Bus();
        List<Component> a = new ArrayList<>();
        List<Component> b = new ArrayList<>();
        notifier(bus, player(Set.of(Permissions.REPORT_NOTIFY), a), player(Set.of(Permissions.REPORT_NOTIFY), b)).announce(NOTICE);

        assertEquals(1, a.size());
        assertEquals(1, b.size());
    }
}
