package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A template's warnWindow decides which earlier warnings still climb the ladder. Other earlier steps keep
 * the escalation window. The clock is the test bed's fixed clock.
 */
class WarnWindowCountTest {

    private static final String SECTION = "network";
    private static final String GROUP = "warn-ladder";

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @BeforeEach
    void givenAWarnThenADayBanLadderWithAThirtyDayWarnWindow() {
        PunishmentTemplateEntity template = bed.givenTemplate(GROUP, SECTION, GROUP,
                new TemplateStep(StepKind.WARN, null, 0), step("P1D", 50));
        template.setWarnWindow("P30D");
    }

    private void givenEarlierRow(String identifier, PunishmentKind kind, Duration age) {
        long createdAt = bed.eloBed.clock.millis() - age.toMillis();
        PunishmentEntity row = new PunishmentEntity(identifier, PunishmentTestBed.STAFF, OWNER, SECTION, false, createdAt, null, new HashMap<>());
        if (kind == PunishmentKind.WARN) {
            row.recordWarning();
        } else {
            row.recordKick("NETWORK", null);
        }
        row.recordEscalation(GROUP, 0, 2, 0);
        bed.punishments.repository().save(row);
    }

    private int previewedStep() {
        PunishmentDTO result = bed.punishmentService.preview(
                new PunishmentDTO.ApplyRequest(OWNER, GROUP, null, null), PunishmentTestBed.ACTOR);
        return assertInstanceOf(PunishmentDTO.PreviewResponse.class, result, "the template should preview: " + result).step();
    }

    @Test
    void aWarningOlderThanTheWarnWindowDoesNotClimbTheLadder() {
        givenEarlierRow("warn-old", PunishmentKind.WARN, Duration.ofDays(31));

        assertEquals(1, previewedStep(), "a warning 31 days old is outside the 30 day warn window");
    }

    @Test
    void aWarningInsideTheWarnWindowClimbsTheLadder() {
        givenEarlierRow("warn-recent", PunishmentKind.WARN, Duration.ofDays(29));

        assertEquals(2, previewedStep(), "a warning 29 days old is inside the 30 day warn window");
    }

    @Test
    void aKickOlderThanTheWarnWindowStillClimbsTheLadder() {
        givenEarlierRow("kick-old", PunishmentKind.KICK, Duration.ofDays(31));

        assertEquals(2, previewedStep(), "the warn window does not apply to kicks; the escalation window does");
    }
}
