package net.onelitefeather.blackhole.discord.audit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Records the delays the poster asks for and waits for none of them. */
final class RecordingSleeper implements Sleeper {

    final List<Duration> sleeps = new ArrayList<>();

    @Override
    public void sleep(Duration duration) {
        this.sleeps.add(duration);
    }
}
