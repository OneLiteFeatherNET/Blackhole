package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.dto.HitDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores the VPN and hosting hits of logins and reads a player's hits back for staff. A hit never creates a
 * punishment; a failure to store one is logged and never fails the login that produced it.
 */
@Singleton
public class IntelHitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntelHitService.class);
    static final int PAGE_LIMIT = 50;
    private static final Pattern OWNER = Pattern.compile("^[a-fA-F0-9]{128}$");

    private final IntelRecordStore store;

    public IntelHitService(IntelRecordStore store) {
        this.store = store;
    }

    /** @return the id of the stored hit, or empty when it could not be stored (logged without the address) */
    public Optional<UUID> record(IntelHit hit) {
        try {
            this.store.insertHit(hit);
            return Optional.of(hit.id());
        } catch (RuntimeException e) {
            LOGGER.warn("A VPN or hosting hit could not be stored: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** @return the newest hits of one owner, or an error for a malformed owner hash */
    public HitDTO listForOwner(String owner) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            return new HitDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        List<HitDTO.Response> hits = this.store.hitsOf(owner, PAGE_LIMIT).stream()
                .map(IntelHitService::response)
                .toList();
        return new HitDTO.Page(hits);
    }

    static HitDTO.Response response(IntelHit hit) {
        return new HitDTO.Response(hit.id().toString(), hit.ip(), hit.classification(), hit.sourceKey(), hit.asn(),
                hit.organisation(), hit.verdict(), hit.allowListed(), hit.createdAt());
    }
}
