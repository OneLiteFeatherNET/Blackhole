package net.onelitefeather.blackhole.velocity.cloudnet;

import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Resolves the CloudNet facts of a server for enforcement. A server CloudNet does not know is not
 * covered by CloudNet sections (fail open); while CloudNet sections exist this is logged once per
 * server name, remembering at most {@value #MAX_WARNED} names so a stream of odd names cannot grow it.
 */
public final class ServerResolver {

    static final int MAX_WARNED = 256;

    private final CloudNetView view;
    private final SectionRegistry sections;
    private final Consumer<String> warn;
    private final Map<String, Boolean> warned = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_WARNED;
        }
    });

    /**
     * @param warn receives the text of each warning
     */
    public ServerResolver(CloudNetView view, SectionRegistry sections, Consumer<String> warn) {
        this.view = view;
        this.sections = sections;
        this.warn = warn;
    }

    public Optional<ServerFacts> facts(String serverName) {
        Optional<ServerFacts> facts = this.view.facts(serverName);
        if (facts.isEmpty() && this.view.available() && hasCloudNetSections()
                && this.warned.putIfAbsent(serverName.toLowerCase(Locale.ROOT), Boolean.TRUE) == null) {
            this.warn.accept("Server " + serverName + " is unknown to CloudNet, CloudNet sections do not cover it. "
                    + "Use a pattern section for servers registered outside CloudNet.");
        }
        return facts;
    }

    private boolean hasCloudNetSections() {
        return this.sections.all().stream().anyMatch(section -> section.targetKind() != ServerTargetKind.PATTERN);
    }
}
