package net.onelitefeather.blackhole.backend.warning;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.warning.dto.WarningDTO;
import net.onelitefeather.blackhole.backend.warning.service.WarningService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A stored warning is confirmed against real PostgreSQL. The row already exists, so the confirmation must update it:
 * inserting it again is a duplicate key. Every test seeds its own warning, so the database can be shared.
 */
@Tag("integration")
class WarningAcknowledgementIntegrationTest extends ApiIntegrationTest {

    private static final String OWNER = "ab".repeat(64);

    /** Stores a pending warning row the way the apply path does: a WARN row with an identifier the application assigned. */
    private String givenStoredWarning() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> metaData = new HashMap<>();
        metaData.put("reason", "reason of " + id);
        PunishmentEntity warning = new PunishmentEntity(id, UUID.randomUUID(), OWNER, "network", false, 1L, null, metaData);
        warning.recordWarning();
        this.context.getBean(PunishmentRepository.class).save(warning);
        return id;
    }

    private PunishmentEntity stored(String id) {
        return this.context.getBean(PunishmentRepository.class).findById(id).orElseThrow();
    }

    private WarningDTO acknowledge(String id) {
        return this.context.getBean(WarningService.class).acknowledge(id, new WarningDTO.AcknowledgeRequest(OWNER));
    }

    @Test
    void aFirstAcknowledgementOfAStoredWarningReturnsTheTimeAndStoresIt() {
        String id = givenStoredWarning();

        WarningDTO result = acknowledge(id);

        long at = assertInstanceOf(WarningDTO.Acknowledged.class, result, "the owner's first confirmation is accepted").acknowledgedAt();
        assertEquals(at, stored(id).getAcknowledgedAt(), "the returned time is the stored time");
    }

    @Test
    void aRepeatAcknowledgementOfAStoredWarningIsAcceptedAndKeepsTheFirstTime() {
        String id = givenStoredWarning();
        long first = assertInstanceOf(WarningDTO.Acknowledged.class, acknowledge(id)).acknowledgedAt();

        WarningDTO repeat = acknowledge(id);

        assertEquals(first, assertInstanceOf(WarningDTO.Acknowledged.class, repeat, "a repeat answers like the first").acknowledgedAt(),
                "the repeat returns the first time");
        assertEquals(first, stored(id).getAcknowledgedAt(), "the stored time is still the first one");
    }

    @Test
    void aRepeatAcknowledgementOverHttpIsNoContentNotAServerError() {
        String id = givenStoredWarning();
        Map<String, Object> body = Map.of("owner", OWNER);
        assertEquals(204, post("/warn/" + id + "/acknowledge", body, this.jwt.proxy()).status(), "the first confirmation is stored");

        Reply repeat = post("/warn/" + id + "/acknowledge", body, this.jwt.proxy());

        assertEquals(204, repeat.status(), "the repeat is idempotent: " + repeat.body());
        assertNotNull(stored(id).getAcknowledgedAt(), "the warning stays acknowledged");
    }
}
