package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentExpirer;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.SectionPunisher;
import net.onelitefeather.blackhole.backend.section.SectionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recording is wired into the running application: real beans, real database, nothing mocked. */
@Tag("integration")
class CaseRecordingWiringIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "e2".repeat(64);

    @Test
    void theExpirerRecordsTheExpiryOfAMuteInItsCase() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            long now = context.getBean(Clock.class).millis();
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("creation_date", 1L);
            metaData.put("update_date", 1L);
            metaData.put("expiration_date", now - 5_000);
            PunishmentEntity mute = context.getBean(PunishmentRepository.class)
                    .save(new PunishmentEntity("wiring-expired", UUID.randomUUID(), OWNER, "chat", false, 1L, null, metaData));
            Map<String, PunishmentEntity> active = new HashMap<>();
            active.put("chat", mute);
            PunishmentProfileEntity profile = context.getBean(PunishmentProfileRepository.class)
                    .save(new PunishmentProfileEntity(OWNER, active, new ArrayList<>(), new HashMap<>()));

            context.getBean(ActivePunishmentExpirer.class).expire(profile);

            List<CaseEventRecord> timeline = context.getBean(CaseEventReader.class).timeline("wiring-expired");
            assertEquals(List.of("punishment.expired"), timeline.stream().map(CaseEventRecord::type).toList(),
                    "the expirer recorded the expiry, which created the case");
            assertEquals(new CaseEventPayload.Expired(now - 5_000), timeline.getFirst().payload());
            assertEquals(ActorTrust.SYSTEM, timeline.getFirst().actorTrust());
        }
    }

    @Test
    void anAutomaticPunishmentIsRecordedAsAppliedBySystem() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            var network = context.getBean(SectionService.class).find("network").orElseThrow();

            String identifier = context.getBean(SectionPunisher.class).applyPermanent("f3".repeat(64), network, Map.of()).orElseThrow();

            List<CaseEventRecord> timeline = context.getBean(CaseEventReader.class).timeline(identifier);
            assertEquals(1, timeline.size(), "one applied event");
            CaseEventPayload.Applied payload = (CaseEventPayload.Applied) timeline.getFirst().payload();
            assertTrue(payload.permanent() && payload.escalated(), "a permanent escalation");
            assertEquals(ActorTrust.SYSTEM, timeline.getFirst().actorTrust());
            assertEquals(CaseEventOrigin.LIVE, timeline.getFirst().origin());
        }
    }

    @Test
    void theReconcilerRunsByDefault() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            assertTrue(context.containsBean(CaseReconciler.class), "the reconciler is on with the default interval");
        }
    }

    @Test
    void anIntervalOfZeroSwitchesTheReconcilerOff() {
        Map<String, Object> properties = new HashMap<>(contextProperties());
        properties.put("blackhole.case.reconcile.interval", "0");

        try (ApplicationContext context = ApplicationContext.run(properties)) {
            assertFalse(context.containsBean(CaseReconciler.class), "0 disables the reconciler");
        }
    }
}
