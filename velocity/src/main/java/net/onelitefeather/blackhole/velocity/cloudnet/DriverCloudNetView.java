package net.onelitefeather.blackhole.velocity.cloudnet;

import eu.cloudnetservice.driver.ComponentInfo;
import eu.cloudnetservice.driver.event.EventListener;
import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.event.events.group.GroupConfigurationAddEvent;
import eu.cloudnetservice.driver.event.events.group.GroupConfigurationRemoveEvent;
import eu.cloudnetservice.driver.event.events.service.CloudServiceLifecycleChangeEvent;
import eu.cloudnetservice.driver.event.events.service.CloudServiceUpdateEvent;
import eu.cloudnetservice.driver.event.events.task.ServiceTaskAddEvent;
import eu.cloudnetservice.driver.event.events.task.ServiceTaskRemoveEvent;
import eu.cloudnetservice.driver.inject.InjectionLayer;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.driver.provider.GroupConfigurationProvider;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.service.GroupConfiguration;
import eu.cloudnetservice.driver.service.ServiceInfoSnapshot;
import eu.cloudnetservice.driver.service.ServiceLifeCycle;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link CloudNetView} backed by the CloudNet driver. The only class that imports CloudNet;
 * {@link CloudNets} loads it only after the driver classes have been found, so the plugin starts
 * without CloudNet. Keeps a map of the running services, fed by CloudNet's service events, and does
 * one lookup by name when a server is not yet known.
 */
public final class DriverCloudNetView implements CloudNetView {

    private final CloudServiceProvider services;
    private final ServiceTaskProvider tasks;
    private final GroupConfigurationProvider groupConfigurations;
    private final String ownName;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ServerFacts> known = new ConcurrentHashMap<>();

    /** Called reflectively by {@link CloudNets}. */
    public DriverCloudNetView() {
        InjectionLayer<?> layer = InjectionLayer.ext();
        this.services = layer.instance(CloudServiceProvider.class);
        if (this.services == null) {
            throw new IllegalStateException("CloudNet provides no service provider");
        }
        this.tasks = layer.instance(ServiceTaskProvider.class);
        this.groupConfigurations = layer.instance(GroupConfigurationProvider.class);
        ComponentInfo component = layer.instance(ComponentInfo.class);
        this.ownName = component == null ? null : component.componentName();
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

    @Override
    public Catalogue catalogue() {
        List<Catalogue.Task> taskList = this.tasks == null ? List.of() : this.tasks.serviceTasks().stream()
                .map(task -> new Catalogue.Task(task.name(), Set.copyOf(task.groups())))
                .sorted(Comparator.comparing(Catalogue.Task::name)).toList();
        List<String> groupList = this.groupConfigurations == null ? List.of() : this.groupConfigurations.groupConfigurations().stream()
                .map(GroupConfiguration::name).sorted().toList();
        List<Catalogue.Service> serviceList = this.services.services().stream()
                .map(service -> new Catalogue.Service(service.name(), service.serviceId().taskName(), service.configuration().groups(),
                        service.lifeCycle() == ServiceLifeCycle.RUNNING))
                .sorted(Comparator.comparing(Catalogue.Service::name)).toList();
        return new Catalogue(taskList, groupList, serviceList);
    }

    @Override
    public Optional<String> ownName() {
        return Optional.ofNullable(this.ownName);
    }

    @Override
    public void onChange(Runnable listener) {
        this.listeners.add(listener);
    }

    @EventListener
    public void onTaskAdded(ServiceTaskAddEvent event) {
        changed();
    }

    @EventListener
    public void onTaskRemoved(ServiceTaskRemoveEvent event) {
        changed();
    }

    @EventListener
    public void onGroupAdded(GroupConfigurationAddEvent event) {
        changed();
    }

    @EventListener
    public void onGroupRemoved(GroupConfigurationRemoveEvent event) {
        changed();
    }

    private void changed() {
        this.listeners.forEach(Runnable::run);
    }

    @EventListener
    public void onLifecycleChange(CloudServiceLifecycleChangeEvent event) {
        ServiceInfoSnapshot service = event.serviceInfo();
        if (event.newLifeCycle() == ServiceLifeCycle.RUNNING) {
            remember(service);
        } else {
            this.known.remove(service.name().toLowerCase(Locale.ROOT));
        }
        changed();
    }

    @EventListener
    public void onUpdate(CloudServiceUpdateEvent event) {
        ServiceInfoSnapshot service = event.serviceInfo();
        if (service.lifeCycle() == ServiceLifeCycle.RUNNING) {
            ServerFacts before = this.known.get(service.name().toLowerCase(Locale.ROOT));
            // Updates also carry player counts and the like; only a change of what the catalogue shows is news.
            if (!remember(service).equals(before)) {
                changed();
            }
        }
    }

    private ServerFacts remember(ServiceInfoSnapshot service) {
        ServerFacts facts = new ServerFacts(service.name(), service.serviceId().taskName(), service.configuration().groups());
        this.known.put(facts.serviceName().toLowerCase(Locale.ROOT), facts);
        return facts;
    }
}
