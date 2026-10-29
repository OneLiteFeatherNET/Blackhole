package net.onelitefeather.blackhole.backend.publicbans.auth;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the access line and the request counter of a ban-list answer. The client name is the only identity in either:
 * a failed attempt is {@code client=unauthenticated} with its reason class, never the submitted name, and no credential,
 * raw UUID, name or IP address is ever passed in.
 */
@Singleton
public class BanListAttribution {

    static final String UNAUTHENTICATED = "unauthenticated";
    static final String METER = "blackhole.ban.list.requests";
    private static final Logger LOG = LoggerFactory.getLogger(BanListAttribution.class);

    private final MeterRegistry meters;

    @Inject
    public BanListAttribution(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * @param client the authenticated client, or {@code null} for a refused request
     * @param reason the reason class of a refused request, such as {@code bad-password}; {@code null} when authenticated
     * @param route  the route class: {@code list}, {@code summary} or {@code entry}
     * @param status the HTTP status of the answer
     */
    public void answered(String client, String reason, String route, int status) {
        if (client == null) {
            LOG.info("ban-list request client={} reason={} route={} status={}", UNAUTHENTICATED, reason, route, status);
        } else {
            LOG.info("ban-list request client={} route={} status={}", client, route, status);
        }
        this.meters.counter(METER, "client", client == null ? UNAUTHENTICATED : client, "route", route,
                "status", String.valueOf(status)).increment();
    }
}
