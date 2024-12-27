package net.onelitefeather.blackhole.backend.listener;

import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;

import javax.validation.constraints.NotNull;

@Singleton
public class BlackholeStartupListener {

    @EventListener
    public void onStartup(@NotNull ApplicationStartupEvent event) {
        // Do something on startup
    }
}
