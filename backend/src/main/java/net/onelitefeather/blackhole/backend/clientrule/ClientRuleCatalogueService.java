package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleCatalogueDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The client rule catalogue: one snapshot per reporting proxy in the shared cache (a TTL, so a dead
 * proxy's entries expire by themselves and every replica sees them), merged on read. It only feeds
 * the editor's suggestions; nothing in enforcement depends on it and rule patterns are free text, so
 * a cache outage costs the editor its picker and nothing else.
 *
 * <p>Privacy: the proxy already counts distinct players and throws the owner hashes away; this
 * service enforces the same minimum again and drops any brand or channel below it before the
 * snapshot is stored, so a rare value that could identify one person never reaches the cache.</p>
 */
@Singleton
public class ClientRuleCatalogueService {

    static final int MAX_BRANDS = 500;
    static final int MAX_CHANNELS = 1000;
    static final int MAX_COMMANDS = 5000;
    static final int MAX_PROTOCOLS = 200;
    static final int MAX_NAME_LENGTH = 128;

    private static final Pattern PROXY_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9._:/@+-]{1,128}");
    private static final Pattern PROTOCOL_NAME = Pattern.compile("[A-Za-z0-9._ -]{1,32}");

    private final SharedCache cache;
    private final Clock clock;
    private final Duration ttl;
    private final int minPlayers;

    public ClientRuleCatalogueService(
            SharedCache cache,
            Clock clock,
            @Value("${blackhole.clientrules.catalogue.ttl:PT24H}") Duration ttl,
            @Value("${blackhole.clientrules.catalogue.min-players:3}") int minPlayers
    ) {
        this.cache = cache;
        this.clock = clock;
        this.ttl = ttl;
        this.minPlayers = minPlayers;
    }

    /**
     * Stores a proxy's full snapshot, replacing its previous one. Names are lower-cased; brands and
     * channels seen from fewer players than the minimum are dropped.
     *
     * @return {@link ClientRuleCatalogueDTO.Stored}, or an {@link ClientRuleCatalogueDTO.Error} if the
     * report breaks a bound (400) or the cache is down and the proxy should retry (503)
     */
    public ClientRuleCatalogueDTO report(ClientRuleCatalogueDTO.ReportRequest request) {
        List<ClientRuleCatalogueDTO.Entry> brands = orEmpty(request.brands());
        List<ClientRuleCatalogueDTO.Entry> channels = orEmpty(request.channels());
        List<String> commands = orEmpty(request.commands());
        List<ClientRuleCatalogueDTO.ProtocolEntry> protocols = orEmpty(request.protocols());
        String problem = validate(request.proxyId(), brands, channels, commands, protocols);
        if (problem != null) {
            return new ClientRuleCatalogueDTO.Error(problem, ErrorKind.VALIDATION);
        }
        Instant now = this.clock.instant();
        ClientRuleCatalogueSnapshot snapshot = new ClientRuleCatalogueSnapshot(request.proxyId(), now, anonymous(brands), anonymous(channels),
                commands.stream().map(ClientRuleCatalogueService::lower).distinct().sorted().toList(), protocols);
        if (!this.cache.tryPut(CacheKeys.clientRuleCatalogue(request.proxyId()), Argument.of(ClientRuleCatalogueSnapshot.class), snapshot, this.ttl)) {
            return new ClientRuleCatalogueDTO.Error("the catalogue could not be stored, retry later", ErrorKind.UNAVAILABLE);
        }
        return new ClientRuleCatalogueDTO.Stored(request.proxyId(), now);
    }

    /**
     * @return the merge of every live snapshot; {@code available} is false when the cache is down or
     * no proxy has reported within the TTL
     */
    public ClientRuleCatalogueDTO.Response catalogue() {
        Optional<List<String>> keys = this.cache.keys(CacheKeys.CLIENT_RULE_CATALOGUE_PATTERN);
        Instant oldest = this.clock.instant().minus(this.ttl);
        List<ClientRuleCatalogueSnapshot> live = new ArrayList<>();
        for (String key : keys.orElse(List.of())) {
            this.cache.get(key, Argument.of(ClientRuleCatalogueSnapshot.class))
                    .filter(snapshot -> snapshot.reportedAt() != null && snapshot.reportedAt().isAfter(oldest))
                    .ifPresent(live::add);
        }
        return live.isEmpty() ? new ClientRuleCatalogueDTO.Response(false, null, List.of(), List.of(), List.of(), List.of(), List.of()) : merge(live);
    }

    private static ClientRuleCatalogueDTO.Response merge(List<ClientRuleCatalogueSnapshot> live) {
        Map<String, Integer> brands = new TreeMap<>();
        Map<String, Integer> channels = new TreeMap<>();
        Set<String> commands = new TreeSet<>();
        Map<Integer, ClientRuleCatalogueDTO.ProtocolEntry> protocols = new TreeMap<>();
        Set<String> proxies = new TreeSet<>();
        Instant reportedAt = live.get(0).reportedAt();
        for (ClientRuleCatalogueSnapshot snapshot : live) {
            proxies.add(snapshot.proxyId());
            reportedAt = snapshot.reportedAt().isAfter(reportedAt) ? snapshot.reportedAt() : reportedAt;
            orEmpty(snapshot.brands()).forEach(entry -> brands.merge(entry.name(), entry.players(), Integer::sum));
            orEmpty(snapshot.channels()).forEach(entry -> channels.merge(entry.name(), entry.players(), Integer::sum));
            commands.addAll(orEmpty(snapshot.commands()));
            orEmpty(snapshot.protocols()).forEach(protocol -> protocols.putIfAbsent(protocol.version(), protocol));
        }
        return new ClientRuleCatalogueDTO.Response(true, reportedAt, entries(brands), entries(channels), List.copyOf(commands),
                List.copyOf(protocols.values()), List.copyOf(proxies));
    }

    private static List<ClientRuleCatalogueDTO.Entry> entries(Map<String, Integer> counted) {
        return counted.entrySet().stream().map(entry -> new ClientRuleCatalogueDTO.Entry(entry.getKey(), entry.getValue())).toList();
    }

    /** Lower-cases, drops entries below the minimum, and keeps the highest count of a repeated name. */
    private List<ClientRuleCatalogueDTO.Entry> anonymous(List<ClientRuleCatalogueDTO.Entry> entries) {
        Map<String, Integer> counted = new TreeMap<>();
        for (ClientRuleCatalogueDTO.Entry entry : entries) {
            if (entry.players() >= this.minPlayers) {
                counted.merge(lower(entry.name()), entry.players(), Math::max);
            }
        }
        return entries(counted);
    }

    private static String validate(String proxyId, List<ClientRuleCatalogueDTO.Entry> brands, List<ClientRuleCatalogueDTO.Entry> channels,
                                   List<String> commands, List<ClientRuleCatalogueDTO.ProtocolEntry> protocols) {
        if (proxyId == null || !PROXY_ID.matcher(proxyId).matches()) {
            return "proxyId must be 1-64 characters of A-Z a-z 0-9 _ . -";
        }
        if (brands.size() > MAX_BRANDS || channels.size() > MAX_CHANNELS || commands.size() > MAX_COMMANDS || protocols.size() > MAX_PROTOCOLS) {
            return "snapshot too large: at most " + MAX_BRANDS + " brands, " + MAX_CHANNELS + " channels, " + MAX_COMMANDS + " commands, "
                    + MAX_PROTOCOLS + " protocols";
        }
        if (!brands.stream().allMatch(ClientRuleCatalogueService::validEntry) || !channels.stream().allMatch(ClientRuleCatalogueService::validEntry)) {
            return "invalid brand or channel entry: names use a-z 0-9 . _ : / @ + - (at most " + MAX_NAME_LENGTH + " characters) and counts are not negative";
        }
        if (!commands.stream().allMatch(command -> command != null && NAME.matcher(lower(command)).matches())) {
            return "invalid command entry: names use a-z 0-9 . _ : / @ + - (at most " + MAX_NAME_LENGTH + " characters)";
        }
        if (!protocols.stream().allMatch(protocol -> protocol.version() >= 0 && protocol.name() != null && PROTOCOL_NAME.matcher(protocol.name()).matches())) {
            return "invalid protocol entry";
        }
        return null;
    }

    private static boolean validEntry(ClientRuleCatalogueDTO.Entry entry) {
        return entry != null && entry.name() != null && entry.players() >= 0 && NAME.matcher(lower(entry.name())).matches();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
