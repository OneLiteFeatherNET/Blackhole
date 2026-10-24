package net.onelitefeather.blackhole.backend.casetimeline.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.live.LiveAudience;
import net.onelitefeather.blackhole.backend.live.LiveHub;
import net.onelitefeather.blackhole.backend.live.LiveStreams;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;

/** Opens the staff live stream of one case. Every staff signal reaches it, internal notes included. */
@Singleton
public class CaseLiveService {

    private final CaseReference references;
    private final LiveStreams streams;

    public CaseLiveService(PunishmentRepository punishments, LiveStreams streams) {
        this.references = new CaseReference(punishments);
        this.streams = streams;
    }

    /**
     * @param reference   the punishment's identifier or short id
     * @param lastEventId the {@code Last-Event-ID} header, or {@code null}
     * @return the opened stream, {@code NotFound} for an unknown reference, or {@code Busy} when the replica is full
     */
    public LiveStreams.Opening open(String reference, @Nullable String lastEventId) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return new LiveStreams.Opening.NotFound();
        }
        return this.streams.open(new LiveHub.Subscription(found.punishment().getIdentifier(), LiveAudience.STAFF, null), lastEventId);
    }
}
