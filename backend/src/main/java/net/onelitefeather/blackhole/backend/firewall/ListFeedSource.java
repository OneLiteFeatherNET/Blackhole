package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelClass;
import net.onelitefeather.blackhole.backend.intel.IntelListSourceConfig;
import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import net.onelitefeather.blackhole.backend.intel.IntelPolicyCache;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import net.onelitefeather.blackhole.backend.intel.ListGenerationStore;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The VPN, hosting and proxy list ranges of the classes whose policy is {@code DENY}. A class listed in
 * {@code blackhole.firewall.feed.vpn-classes} but set to {@code ALLOW} or {@code FLAG} contributes nothing.
 */
@Singleton
public class ListFeedSource implements FeedNetworkSource {

    private final ListGenerationStore store;
    private final IntelPolicyCache policies;
    private final List<IntelListSourceConfig> sources;
    private final Set<IntelClass> classes;

    public ListFeedSource(ListGenerationStore store, IntelPolicyCache policies, List<IntelListSourceConfig> sources,
                          @Value("${blackhole.firewall.feed.vpn-classes:VPN,HOSTING,PROXY}") String classes) {
        this.store = store;
        this.policies = policies;
        this.sources = sources;
        this.classes = parseClasses(classes);
    }

    @Override
    public List<IntelPrefix> networks() {
        Map<IntelClass, IntelPolicy> policy = this.policies.policies();
        Set<String> denied = new HashSet<>();
        for (IntelListSourceConfig source : this.sources) {
            IntelClass kind = source.getKind();
            if (source.isEnabled() && this.classes.contains(kind) && policy.get(kind) == IntelPolicy.DENY) {
                denied.add(source.getKey());
            }
        }
        List<IntelPrefix> networks = new ArrayList<>();
        for (ListGenerationStore.ListedCidr row : this.store.loadActiveEntries()) {
            if (denied.contains(row.sourceKey())) {
                IntelPrefix.parseCidr(row.cidr()).ifPresent(networks::add);
            }
        }
        return networks;
    }

    /**
     * The enabled list generations and the class policies: the same fingerprint {@code ListReloader} uses to decide when
     * the classifier must be reloaded. Reads only the generation pointers, never the entries.
     */
    @Override
    public Object version() {
        Map<String, Long> active = this.store.activeGenerations();
        Map<String, Long> enabled = new TreeMap<>();
        for (IntelListSourceConfig source : this.sources) {
            if (source.isEnabled() && active.containsKey(source.getKey())) {
                enabled.put(source.getKey(), active.get(source.getKey()));
            }
        }
        return List.of(enabled, this.policies.policies());
    }

    @Override
    public String kind() {
        return "LIST";
    }

    static Set<IntelClass> parseClasses(String csv) {
        Set<IntelClass> parsed = EnumSet.noneOf(IntelClass.class);
        for (String part : csv.split(",")) {
            String name = part.strip();
            if (!name.isEmpty()) {
                parsed.add(IntelClass.valueOf(name));
            }
        }
        return parsed;
    }
}
