package net.onelitefeather.blackhole.backend.cloudnet;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.cloudnet.dto.CatalogueDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The CloudNet catalogue: one snapshot per reporting proxy in the shared cache (TTL, so a dead
 * proxy's entries expire by themselves and every replica sees them), merged on read. Nothing
 * depends on it for enforcement and sections naming unknown tasks are never rejected, so a cache
 * outage only costs the editor its picker.
 */
@Singleton
public class CatalogueService {

    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_TASKS = 500;
    static final int MAX_GROUPS = 500;
    static final int MAX_SERVICES = 5000;
    static final int MAX_GROUPS_PER_ENTRY = 100;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private final SharedCache cache;
    private final Clock clock;
    private final Duration ttl;

    public CatalogueService(SharedCache cache, Clock clock, @Value("${blackhole.cloudnet.catalogue.ttl:PT15M}") Duration ttl) {
        this.cache = cache;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Stores a proxy's full snapshot, replacing its previous one.
     *
     * @return {@link CatalogueDTO.Stored}, or an {@link CatalogueDTO.Error} if the report breaks a
     * bound or the cache is down (the proxy retries)
     */
    public CatalogueDTO report(CatalogueDTO.ReportRequest request) {
        List<CatalogueDTO.TaskEntry> tasks = orEmpty(request.tasks());
        List<String> groups = orEmpty(request.groups());
        List<CatalogueDTO.ServiceEntry> services = orEmpty(request.services());
        String problem = validate(request.proxyId(), tasks, groups, services);
        if (problem != null) {
            return new CatalogueDTO.Error(problem, ErrorKind.VALIDATION);
        }
        Instant now = this.clock.instant();
        CatalogueSnapshot snapshot = new CatalogueSnapshot(request.proxyId(), now, tasks, groups, services);
        if (!this.cache.tryPut(CacheKeys.cloudnetCatalogue(request.proxyId()), Argument.of(CatalogueSnapshot.class), snapshot, this.ttl)) {
            return new CatalogueDTO.Error("the catalogue could not be stored, retry later", ErrorKind.UNAVAILABLE);
        }
        return new CatalogueDTO.Stored(request.proxyId(), now);
    }

    /**
     * @return the merge of every live snapshot; {@code available} is false when the cache is down or
     * no proxy has reported within the TTL
     */
    public CatalogueDTO.Response catalogue() {
        Optional<List<String>> keys = this.cache.keys(CacheKeys.CLOUDNET_CATALOGUE_PATTERN);
        Instant oldest = this.clock.instant().minus(this.ttl);
        List<CatalogueSnapshot> live = new ArrayList<>();
        for (String key : keys.orElse(List.of())) {
            this.cache.get(key, Argument.of(CatalogueSnapshot.class))
                    .filter(snapshot -> snapshot.reportedAt() != null && snapshot.reportedAt().isAfter(oldest))
                    .ifPresent(live::add);
        }
        return live.isEmpty() ? new CatalogueDTO.Response(false, null, List.of(), List.of(), List.of(), List.of()) : merge(live);
    }

    private static CatalogueDTO.Response merge(List<CatalogueSnapshot> live) {
        Map<String, Set<String>> tasks = new TreeMap<>();
        Set<String> groups = new TreeSet<>();
        Map<String, CatalogueDTO.MergedService> services = new TreeMap<>();
        Set<String> proxies = new TreeSet<>();
        Instant reportedAt = live.get(0).reportedAt();
        for (CatalogueSnapshot snapshot : live) {
            proxies.add(snapshot.proxyId());
            reportedAt = snapshot.reportedAt().isAfter(reportedAt) ? snapshot.reportedAt() : reportedAt;
            groups.addAll(orEmpty(snapshot.groups()));
            // The serializer omits empty collections, so a stored snapshot reads back with nulls.
            for (CatalogueDTO.TaskEntry task : orEmpty(snapshot.tasks())) {
                tasks.computeIfAbsent(task.name(), name -> new TreeSet<>()).addAll(orEmpty(task.groups()));
            }
            for (CatalogueDTO.ServiceEntry service : orEmpty(snapshot.services())) {
                services.merge(service.name(), new CatalogueDTO.MergedService(service.name(), service.task(), service.running()),
                        (a, b) -> new CatalogueDTO.MergedService(a.name(), a.task() != null ? a.task() : b.task(), a.running() || b.running()));
            }
        }
        List<CatalogueDTO.TaskEntry> mergedTasks = tasks.entrySet().stream()
                .map(entry -> new CatalogueDTO.TaskEntry(entry.getKey(), List.copyOf(entry.getValue()))).toList();
        return new CatalogueDTO.Response(true, reportedAt, mergedTasks, List.copyOf(groups),
                services.values().stream().sorted(Comparator.comparing(CatalogueDTO.MergedService::name)).toList(), List.copyOf(proxies));
    }

    private static String validate(String proxyId, List<CatalogueDTO.TaskEntry> tasks, List<String> groups, List<CatalogueDTO.ServiceEntry> services) {
        if (!validName(proxyId)) {
            return "proxyId must be 1-" + MAX_NAME_LENGTH + " characters of A-Z a-z 0-9 _ . -";
        }
        if (tasks.size() > MAX_TASKS || groups.size() > MAX_GROUPS || services.size() > MAX_SERVICES) {
            return "snapshot too large: at most " + MAX_TASKS + " tasks, " + MAX_GROUPS + " groups, " + MAX_SERVICES + " services";
        }
        for (CatalogueDTO.TaskEntry task : tasks) {
            if (!validName(task.name()) || !validNames(task.groups())) {
                return "invalid task entry";
            }
        }
        for (CatalogueDTO.ServiceEntry service : services) {
            if (!validName(service.name()) || (service.task() != null && !validName(service.task())) || !validNames(service.groups())) {
                return "invalid service entry";
            }
        }
        return validNames(groups) ? null : "invalid group name";
    }

    private static boolean validNames(List<String> names) {
        return names == null || (names.size() <= MAX_GROUPS_PER_ENTRY && names.stream().allMatch(CatalogueService::validName));
    }

    private static boolean validName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && NAME.matcher(name).matches();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
