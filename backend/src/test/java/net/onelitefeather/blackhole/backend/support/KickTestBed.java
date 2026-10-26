package net.onelitefeather.blackhole.backend.support;

import io.micrometer.core.instrument.MeterRegistry;
import net.onelitefeather.blackhole.backend.kick.KickDeadline;
import net.onelitefeather.blackhole.backend.kick.service.KickService;

/**
 * The kick service over in-memory collaborators on top of a {@link PunishmentTestBed}: one fixture per
 * test, a fixed clock, a recording event transport and no database or broker.
 */
public final class KickTestBed {

    public static final String OWNER = PunishmentTestBed.OWNER;

    public final PunishmentTestBed bed = new PunishmentTestBed();
    public final InMemoryKickStore store = bed.kickStore;
    public final RecordingEventTransport transport = bed.kickTransport;
    public final MeterRegistry meters = bed.kickMeters;
    public final KickDeadline deadline = bed.kickDeadline;
    public final KickService service = bed.kickService;
}
