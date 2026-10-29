package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.areaban.AreaBanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts the refusals the join guard gives (STRICT or LOCKDOWN), for the summary and for the burst rule. Area bans and
 * the VPN policy are not counted: those are decisions already taken. Counting never fails the login: an error is logged
 * and the refusal stands. The log line carries the ASN only, never the address or the owner.
 */
@Singleton
public class GuardRefusalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardRefusalService.class);

    private final GuardRefusalStore store;
    private final AreaBanService areaBans;
    private final BurstSettings burst;

    public GuardRefusalService(GuardRefusalStore store, AreaBanService areaBans, BurstSettings burst) {
        this.store = store;
        this.areaBans = areaBans;
        this.burst = burst;
    }

    /**
     * Records one guard refusal. A login without an ASN is not counted, because the summary and the burst rule are per ASN.
     */
    public void recordRefusal(Long asn, String organisation, String countryCode, long nowMillis) {
        if (asn == null) {
            return;
        }
        try {
            this.store.recordRefusal(asn, organisation, countryCode);
            if (this.burst.enabled()) {
                applyBurstRule(asn, nowMillis);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not count a join guard refusal for AS{}: {}", asn, e.getMessage());
        }
    }

    private void applyBurstRule(long asn, long nowMillis) {
        long inWindow = this.store.addBurstRefusal(asn, nowMillis, this.burst.window().toMillis());
        if (inWindow < this.burst.threshold()) {
            return;
        }
        this.areaBans.createBurstBan(asn, this.burst.banDuration()).ifPresent(ban ->
                LOGGER.warn("Burst rule banned AS{} for {} after {} refusals", asn, this.burst.banDuration(), inWindow));
    }
}
