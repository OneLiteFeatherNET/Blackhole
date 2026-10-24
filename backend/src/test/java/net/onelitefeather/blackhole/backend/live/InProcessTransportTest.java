package net.onelitefeather.blackhole.backend.live;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The single-replica transport hands a signal to every listener, and one failing listener does not stop the rest. */
class InProcessTransportTest {

    @Test
    void everyListenerGetsEverySignalInOrder() {
        InProcessTransport transport = new InProcessTransport();
        List<ChangeSignal> first = new ArrayList<>();
        List<ChangeSignal> second = new ArrayList<>();
        transport.subscribe(first::add);
        transport.subscribe(second::add);
        ChangeSignal one = ChangeSignal.changed("PUNISHMENT-1", 1, LiveAudience.PLAYER);
        ChangeSignal two = ChangeSignal.sessionsEnded("PUNISHMENT-1");

        transport.publish(one);
        transport.publish(two);

        assertEquals(List.of(one, two), first);
        assertEquals(List.of(one, two), second);
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() {
        InProcessTransport transport = new InProcessTransport();
        List<ChangeSignal> survivor = new ArrayList<>();
        transport.subscribe(signal -> {
            throw new IllegalStateException("a broken stream");
        });
        transport.subscribe(survivor::add);
        ChangeSignal signal = ChangeSignal.changed("PUNISHMENT-2", 9, LiveAudience.STAFF);

        transport.publish(signal);

        assertEquals(List.of(signal), survivor);
    }
}
