package net.onelitefeather.blackhole.backend.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.kick.KickDeadline;
import net.onelitefeather.blackhole.backend.kick.KickEventPublisher;
import net.onelitefeather.blackhole.backend.kick.service.KickService;

import java.time.Duration;

/**
 * The kick service over in-memory collaborators on top of a {@link PunishmentTestBed}: one fixture per
 * test, a fixed clock, a recording event transport and no database or broker.
 */
public final class KickTestBed {

    public static final String OWNER = PunishmentTestBed.OWNER;

    public final PunishmentTestBed bed = new PunishmentTestBed();
    public final InMemoryKickStore store = new InMemoryKickStore();
    public final RecordingEventTransport transport = new RecordingEventTransport();
    public final MeterRegistry meters = new SimpleMeterRegistry();
    public final KickDeadline deadline = new KickDeadline(bed.eloBed.clock, Duration.ofSeconds(5));
    public final KickService service = new KickService(
            bed.punishments.repository(), bed.profiles.repository(), bed.slots, bed.profileCache, store, deadline,
            new KickEventPublisher(new DomainEventPublisher(transport, bed.eloBed.clock), meters),
            bed.eloBed.sections, bed.cases.timeline, bed.staffLinks.service);
}
