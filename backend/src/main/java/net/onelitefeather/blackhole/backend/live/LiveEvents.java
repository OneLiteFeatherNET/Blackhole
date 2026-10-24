package net.onelitefeather.blackhole.backend.live;

import io.micronaut.http.sse.Event;

/**
 * The wire form of a frame. {@code ready}, {@code changed}, {@code expired} and {@code replaced} carry {@code {"seq":N}} or {@code {}};
 * nothing else is ever written into an event. A keep-alive is a comment line, and {@code retry} is sent once.
 */
final class LiveEvents {

    private LiveEvents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    static Event<String> toEvent(LiveFrame frame) {
        return switch (frame) {
            case LiveFrame.Retry retry -> Event.of("").retry(retry.after());
            case LiveFrame.Ready ready -> Event.of("{\"seq\":" + ready.seq() + "}").name("ready");
            case LiveFrame.Changed changed -> Event.of("{\"seq\":" + changed.seq() + "}").name("changed").id(Long.toString(changed.seq()));
            case LiveFrame.Expired expired -> Event.of("{}").name("expired");
            case LiveFrame.Replaced replaced -> Event.of("{}").name("replaced");
            case LiveFrame.KeepAlive keepAlive -> Event.of("").comment("ka");
        };
    }
}
