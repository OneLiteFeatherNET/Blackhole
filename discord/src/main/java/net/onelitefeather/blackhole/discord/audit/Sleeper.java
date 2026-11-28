package net.onelitefeather.blackhole.discord.audit;

import java.time.Duration;

/** Waits between two attempts. Production sleeps on the consumer thread; tests record the delay and return at once. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    /** @return the production sleeper, which blocks the calling (consumer) thread */
    static Sleeper threads() {
        return Thread::sleep;
    }
}
