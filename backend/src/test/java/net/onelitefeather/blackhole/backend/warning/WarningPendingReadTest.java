package net.onelitefeather.blackhole.backend.warning;

import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.warning.dto.WarningDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The pending read lists only the owner's unacknowledged warnings, oldest first, at most the limit. */
class WarningPendingReadTest {

    private static final String OTHER_OWNER = "cd".repeat(64);

    private final PunishmentTestBed bed = new PunishmentTestBed();

    /** A stored warning of the owner, created at the given time. */
    private void givenWarningRow(String identifier, String owner, long createdAt, boolean acknowledged) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", "reason of " + identifier);
        PunishmentEntity row = new PunishmentEntity(identifier, PunishmentTestBed.STAFF, owner, null, false, createdAt, null, metadata);
        row.recordWarning();
        if (acknowledged) {
            row.acknowledge(createdAt + 1);
        }
        bed.punishments.repository().save(row);
    }

    private void givenKickRow(String identifier, String owner, long createdAt) {
        PunishmentEntity row = new PunishmentEntity(identifier, PunishmentTestBed.STAFF, owner, null, false, createdAt, null, new HashMap<>());
        row.recordKick(KickScope.NETWORK.name(), null);
        bed.punishments.repository().save(row);
    }

    private long base() {
        return bed.eloBed.clock.millis();
    }

    private WarningDTO.Pending pending(String owner, Integer limit) {
        WarningDTO result = bed.warningService.pending(owner, limit);
        return assertInstanceOf(WarningDTO.Pending.class, result, "the pending read succeeds: " + result);
    }

    private static List<String> ids(WarningDTO.Pending page) {
        List<String> ids = new ArrayList<>();
        page.items().forEach(item -> ids.add(item.id()));
        return ids;
    }

    @Test
    void onlyUnacknowledgedWarningsOfTheOwnerAreReturnedOldestFirst() {
        givenWarningRow("w-newest", OWNER, base() - 1_000, false);
        givenWarningRow("w-oldest", OWNER, base() - 3_000, false);
        givenWarningRow("w-acked", OWNER, base() - 2_000, true);
        givenWarningRow("w-other", OTHER_OWNER, base() - 4_000, false);
        givenKickRow("k-owner", OWNER, base() - 5_000);

        WarningDTO.Pending page = pending(OWNER, null);

        assertEquals(List.of("w-oldest", "w-newest"), ids(page), "pending warnings of this owner, oldest first");
    }

    @Test
    void theLimitIsHonoured() {
        givenWarningRow("w-1", OWNER, base() - 3_000, false);
        givenWarningRow("w-2", OWNER, base() - 2_000, false);
        givenWarningRow("w-3", OWNER, base() - 1_000, false);

        assertEquals(List.of("w-1", "w-2"), ids(pending(OWNER, 2)), "at most the requested limit, oldest first");
    }

    @Test
    void theDefaultLimitIsTen() {
        for (int i = 0; i < 12; i++) {
            givenWarningRow("w-" + i, OWNER, base() - (12 - i) * 1_000L, false);
        }

        WarningDTO.Pending page = pending(OWNER, null);

        assertEquals(10, page.items().size(), "without a limit the read answers ten warnings");
        assertEquals("w-0", page.items().getFirst().id(), "the oldest comes first");
    }

    @Test
    void aLimitAboveFiftyIsRefused() {
        WarningDTO result = bed.warningService.pending(OWNER, 51);

        WarningDTO.Error error = assertInstanceOf(WarningDTO.Error.class, result, "a limit above 50 is refused");
        assertEquals(ErrorKind.VALIDATION, error.kind());
    }

    @Test
    void aMalformedOwnerIsRefused() {
        WarningDTO result = bed.warningService.pending("not-a-hash", null);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(WarningDTO.Error.class, result, "a malformed owner is refused").kind());
    }

    @Test
    void aPendingItemCarriesTheReasonAndNoAcknowledgementTime() {
        givenWarningRow("w-reason", OWNER, base() - 1_000, false);

        WarningDTO.Response item = pending(OWNER, null).items().getFirst();

        assertEquals("reason of w-reason", item.reason(), "the proxy reads the reason from this route");
        assertNull(item.acknowledgedAt(), "a pending warning has no acknowledgement time");
    }
}
