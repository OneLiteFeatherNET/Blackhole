package net.onelitefeather.blackhole.backend.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures log lines from any thread. Unlike logback's {@code ListAppender}, whose plain list
 * throws {@code ConcurrentModificationException} when a background thread appends while a test
 * iterates it, the lines are kept in a thread-safe list and read through {@link #snapshot()}.
 */
public class SnapshotLogAppender extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> lines = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
        this.lines.add(event);
    }

    /** An immutable copy of the lines captured so far, in order. */
    public List<ILoggingEvent> snapshot() {
        return List.copyOf(this.lines);
    }
}
