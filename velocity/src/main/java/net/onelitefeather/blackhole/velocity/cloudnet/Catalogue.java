package net.onelitefeather.blackhole.velocity.cloudnet;

import java.util.List;
import java.util.Set;

/**
 * What CloudNet knows, free of any CloudNet type: the tasks, groups and services the backend offers
 * as picker entries for CloudNet sections.
 */
public record Catalogue(List<Task> tasks, List<String> groups, List<Service> services) {

    public Catalogue {
        tasks = List.copyOf(tasks);
        groups = List.copyOf(groups);
        services = List.copyOf(services);
    }

    public static Catalogue empty() {
        return new Catalogue(List.of(), List.of(), List.of());
    }

    public record Task(String name, Set<String> groups) {

        public Task {
            groups = Set.copyOf(groups);
        }
    }

    public record Service(String name, String task, Set<String> groups, boolean running) {

        public Service {
            groups = Set.copyOf(groups);
        }
    }
}
