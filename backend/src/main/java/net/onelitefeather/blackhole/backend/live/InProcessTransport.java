package net.onelitefeather.blackhole.backend.live;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The transport of a single replica: a signal goes straight to the listeners of this JVM. Exact on one replica; across replicas the
 * bus adapter (task 4.6) is what makes a signal reach the others. A listener that fails does not stop the others.
 */
@Singleton
public class InProcessTransport implements ChangeSignalTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(InProcessTransport.class);

    private final List<Consumer<ChangeSignal>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ChangeSignal signal) {
        for (Consumer<ChangeSignal> listener : this.listeners) {
            try {
                listener.accept(signal);
            } catch (RuntimeException e) {
                LOGGER.warn("A live listener failed on a {} signal; the others still get it", signal.kind(), e);
            }
        }
    }

    @Override
    public void subscribe(Consumer<ChangeSignal> listener) {
        this.listeners.add(listener);
    }
}
