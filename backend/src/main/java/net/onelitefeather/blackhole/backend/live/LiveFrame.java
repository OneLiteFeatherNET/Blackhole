package net.onelitefeather.blackhole.backend.live;

import java.time.Duration;

/**
 * One thing a stream sends. Only {@link Ready} and {@link Changed} carry data, and that data is a seq: no text, name or identifier
 * ever reaches a frame.
 */
public sealed interface LiveFrame {

    /** The reconnect delay, sent once when the stream opens. */
    record Retry(Duration after) implements LiveFrame {
    }

    /** The stream is open; {@code seq} is the case's current seq as this reader may see it. */
    record Ready(long seq) implements LiveFrame {
    }

    /** Something this reader may see changed up to {@code seq}. The client then reads the normal endpoint with its cursor. */
    record Changed(long seq) implements LiveFrame {
    }

    /** The session ended (expired, logged out, leaked or reissued); the stream closes after this frame. */
    record Expired() implements LiveFrame {
    }

    /** The same session opened another stream; this one closes after this frame. */
    record Replaced() implements LiveFrame {
    }

    /** A comment line that keeps the connection busy through the proxies. */
    record KeepAlive() implements LiveFrame {
    }
}
