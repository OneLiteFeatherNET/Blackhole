package net.onelitefeather.blackhole.backend.intel;

import com.maxmind.db.DatabaseRecord;
import com.maxmind.db.Network;
import com.maxmind.db.Networks;
import com.maxmind.db.Reader;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Offline ASN and country lookup over two MaxMind DB files (DB-IP Lite or GeoLite2). The files are read from
 * disk; no request leaves the network. A file is reopened when its modification time changes, checked at most
 * once per hour. A missing or unreadable file yields no enrichment and one warning per hour, never an exception.
 */
@Singleton
public class GeoIpReader implements IpEnricher, AsnNetworks {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeoIpReader.class);
    private static final Duration CHECK_EVERY = Duration.ofHours(1);

    private final Clock clock;
    private final Database asn;
    private final Database country;

    public GeoIpReader(
            Clock clock,
            @Value("${blackhole.intel.geoip.asn-path:/data/geoip/asn.mmdb}") String asnPath,
            @Value("${blackhole.intel.geoip.city-path:/data/geoip/city.mmdb}") String cityPath
    ) {
        this.clock = clock;
        this.asn = new Database("ASN", Path.of(asnPath));
        this.country = new Database("country", Path.of(cityPath));
    }

    @Override
    public IpEnrichment enrich(String ip) {
        Optional<InetAddress> address = IpLiteral.parse(ip);
        if (address.isEmpty()) {
            return IpEnrichment.NONE;
        }
        Long asnNumber = null;
        String organisation = null;
        Map<?, ?> asnRecord = lookup(this.asn, address.get());
        if (asnRecord != null && asnRecord.get("autonomous_system_number") instanceof Number number) {
            asnNumber = number.longValue();
            organisation = asnRecord.get("autonomous_system_organization") instanceof String name ? name : null;
        }
        String countryCode = null;
        Map<?, ?> cityRecord = lookup(this.country, address.get());
        if (cityRecord != null && cityRecord.get("country") instanceof Map<?, ?> countryMap
                && countryMap.get("iso_code") instanceof String iso) {
            countryCode = iso;
        }
        return new IpEnrichment(asnNumber, organisation, countryCode);
    }

    /**
     * Enumerates every network of the given autonomous systems in the ASN file. It walks the whole file, so it is for the
     * feed rebuild, never for a request. An absent file yields no networks.
     */
    @Override
    public long version() {
        this.asn.current(this.clock.instant());
        return this.asn.loadedModifiedMillis;
    }

    @Override
    public List<IntelPrefix> networksOfAsns(Set<Long> asns) {
        List<IntelPrefix> found = new ArrayList<>();
        Reader reader = this.asn.current(this.clock.instant());
        if (reader == null || asns.isEmpty()) {
            return found;
        }
        try {
            Networks<Map> networks = reader.networks(Map.class);
            while (networks.hasNext()) {
                DatabaseRecord<Map> record = networks.next();
                if (record.data() != null && record.data().get("autonomous_system_number") instanceof Number number
                        && asns.contains(number.longValue())) {
                    Network network = record.network();
                    found.add(IntelPrefix.ofAddress(network.ipAddress()).withLength(network.prefixLength()));
                }
            }
        } catch (IOException | com.maxmind.db.InvalidNetworkException | RuntimeException e) {
            LOGGER.warn("GeoIP ASN enumeration failed; the hosting ASN networks are left out of the feed");
            return List.of();
        }
        return found;
    }

    private Map<?, ?> lookup(Database database, InetAddress address) {
        Reader reader = database.current(this.clock.instant());
        if (reader == null) {
            return null;
        }
        try {
            return reader.get(address, Map.class);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("GeoIP {} lookup failed for a stored session", database.label);
            return null;
        }
    }

    /** One MaxMind DB file, reopened when it changes. */
    private static final class Database {

        private final String label;
        private final Path path;
        private Reader reader;
        private long loadedModifiedMillis = -1;
        private Instant lastCheck = Instant.MIN;

        Database(String label, Path path) {
            this.label = label;
            this.path = path;
        }

        /** At most one check per hour, so a missing file is reported once per hour. */
        synchronized Reader current(Instant now) {
            if (now.isBefore(this.lastCheck.plus(CHECK_EVERY))) {
                return this.reader;
            }
            this.lastCheck = now;
            try {
                long modified = Files.getLastModifiedTime(this.path).toMillis();
                if (this.reader == null || modified != this.loadedModifiedMillis) {
                    this.reader = new Reader(this.path.toFile());
                    this.loadedModifiedMillis = modified;
                }
            } catch (IOException | RuntimeException e) {
                this.reader = null;
                this.loadedModifiedMillis = -1;
                LOGGER.warn("GeoIP {} database is unavailable at {}; sessions are stored without it", this.label, this.path);
            }
            return this.reader;
        }
    }
}
