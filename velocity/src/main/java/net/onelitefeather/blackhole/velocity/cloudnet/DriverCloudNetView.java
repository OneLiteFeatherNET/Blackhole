package net.onelitefeather.blackhole.velocity.cloudnet;

import eu.cloudnetservice.driver.event.EventListener;
import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.event.events.service.CloudServiceLifecycleChangeEvent;
import eu.cloudnetservice.driver.event.events.service.CloudServiceUpdateEvent;
import eu.cloudnetservice.driver.inject.InjectionLayer;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.driver.service.ServiceInfoSnapshot;
import eu.cloudnetservice.driver.service.ServiceLifeCycle;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The {@link CloudNetView} backed by the CloudNet driver. The only class that imports CloudNet;
 * {@link CloudNets} loads it only after the driver classes have been found, so the plugin starts
 * without CloudNet. Keeps a map of the running services, fed by CloudNet's service events, and does
 * one lookup by name when a server is not yet known.
 */
public final class DriverCloudNetView implements CloudNetView {

    private final CloudServiceProvider services;
    private final ConcurrentMap<String, ServerFacts> known = new ConcurrentHashMap<>();

    /** Called reflectively by {@link CloudNets}. */
    public DriverCloudNetView() {
        InjectionLayer<?> layer = InjectionLayer.ext();
        this.services = layer.instance(CloudServiceProvider.class);
        if (this.services == null) {
            throw new IllegalStateException("CloudNet provides no service provider");
        }
        this.services.runningServices().forEach(this::remember);
        layer.instance(EventManager.class).registerListener(this);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<ServerFacts> facts(String serverName) {
        String key = serverName.toLowerCase(Locale.ROOT);
        ServerFacts cached = this.known.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        ServiceInfoSnapshot found = this.services.serviceByName(serverName);
        if (found == null || found.lifeCycle() != ServiceLifeCycle.RUNNING) {
            return Optional.empty();
        }
        return Optional.of(remember(found));
    }

    @EventListener
    public void onLifecycleChange(CloudServiceLifecycleChangeEvent event) {
        ServiceInfoSnapshot service = event.serviceInfo();
        if (event.newLifeCycle() == ServiceLifeCycle.RUNNING) {
            remember(service);
        } else {
            this.known.remove(service.name().toLowerCase(Locale.ROOT));
        }
    }

    @EventListener
    public void onUpdate(CloudServiceUpdateEvent event) {
        if (event.serviceInfo().lifeCycle() == ServiceLifeCycle.RUNNING) {
            remember(event.serviceInfo());
        }
    }

    private ServerFacts remember(ServiceInfoSnapshot service) {
        ServerFacts facts = new ServerFacts(service.name(), service.serviceId().taskName(), service.configuration().groups());
        this.known.put(facts.serviceName().toLowerCase(Locale.ROOT), facts);
        return facts;
    }
}
