package net.onelitefeather.blackhole.velocity.command;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.FakeWarningBackend;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.enforcement.WarningHandler;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidence;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /warn} applies the template with the warning-only flag and reports the outcome to the staff member. */
class WarnCommandTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");
    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000a1ec");

    private final FakeWarningBackend backend = new FakeWarningBackend();
    private final WarnCommand command = new WarnCommand(
            new PunishService(this.backend.gateway(), standardRegistry(), ChatEvidence.create(
                    new ChatSnapshotBuilder(new ChatBuffer(Clock.systemUTC(), new ChatCaptureConfig()), new ChatCaptureConfig()),
                    this.backend.gateway(), new ChatCaptureConfig())),
            new WarningHandler(this.backend.gateway(), Runnable::run));
    private final RecordingPlayer staff = new RecordingPlayer(STAFF, "Staff");
    private final ResolvedTarget alex = new ResolvedTarget(ALEX, "Alex", null);

    private static PunishTemplateResponse template(String key, String section) {
        return new PunishTemplateResponse().identifier(UUID.randomUUID()).key(key).reason("reason of " + key).section(section);
    }

    @Test
    void aWarnAppliesTheTemplateWithTheWarningOnlyFlag() {
        this.backend.templates.add(template("spam", "network"));

        this.command.handle(this.staff.player(), this.alex, "spam");

        assertEquals(List.of("spam|true"), this.backend.applied, "the apply asks for a warning step only");
    }

    @Test
    void aWarnReportsTheWarningToTheStaffMember() {
        this.backend.templates.add(template("spam", "network"));

        this.command.handle(this.staff.player(), this.alex, "spam");

        assertTrue(this.staff.texts().getFirst().contains("Warned Alex with spam"), this.staff.texts().toString());
    }

    @Test
    void aWarnWhoseNextStepIsNotAWarningReportsThatNothingWasApplied() {
        this.backend.templates.add(template("spam", "network"));
        this.backend.applyFailure = new ApiException(422, "WARN_STEP_REQUIRED");

        this.command.handle(this.staff.player(), this.alex, "spam");

        assertTrue(this.staff.texts().getFirst().contains("nothing was applied"), this.staff.texts().toString());
    }
}
