package net.onelitefeather.blackhole.backend.listener;

import io.micronaut.runtime.event.ApplicationShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;

@Singleton
public class BlackholeStopListener {

    @EventListener
    public void onShutdown(@NotNull ApplicationShutdownEvent event) {
        // Do something on shutdown
    }
}
