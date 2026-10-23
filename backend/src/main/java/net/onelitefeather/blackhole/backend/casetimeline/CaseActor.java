package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.security.Attribution;

import java.util.UUID;

/**
 * Who caused a case event. Never carries a name: the display name is joined from the staff actor
 * when the timeline is read, so anonymizing a staff member erases it everywhere.
 *
 * @param trust         how far the actor can be trusted
 * @param actorOid      the person ({@code oid} of the token, or of the linked staff member), if known
 * @param recordedByOid the verified principal that wrote it (the proxy for in-game actions)
 * @param actorSource   the attributed Minecraft UUID when there is no {@code actorOid}
 */
public record CaseActor(ActorTrust trust, @Nullable UUID actorOid, @Nullable UUID recordedByOid, @Nullable UUID actorSource) {

    /** The system itself. */
    public static CaseActor system() {
        return new CaseActor(ActorTrust.SYSTEM, null, null, null);
    }

    /** Nobody is attributed (for example the filing of an appeal, which records no actor). */
    public static CaseActor unattributed() {
        return new CaseActor(ActorTrust.UNVERIFIED, null, null, null);
    }

    /** The actor of a write made with this attribution. */
    public static CaseActor of(Attribution attribution) {
        return ofRow(attribution.source(), attribution.recordedByOid(), attribution.actorOid());
    }

    /**
     * The actor a stored row implies. The same rules as the backfill in {@code V12__punishment_case.sql}.
     *
     * @param source        the attributed UUID of the row
     * @param recordedByOid the verified principal that wrote the row, {@code null} for rows from before SSO
     * @param actorOid      the staff member the source was linked to, if any
     */
    public static CaseActor ofRow(@Nullable UUID source, @Nullable UUID recordedByOid, @Nullable UUID actorOid) {
        if (EloService.SYSTEM_ELO_SOURCE.equals(source)) {
            return system();
        }
        if (actorOid != null) {
            return new CaseActor(ActorTrust.VERIFIED, actorOid, recordedByOid, null);
        }
        if (recordedByOid != null && (source == null || source.equals(recordedByOid))) {
            return new CaseActor(ActorTrust.VERIFIED, recordedByOid, recordedByOid, null);
        }
        return new CaseActor(ActorTrust.UNVERIFIED, null, recordedByOid, source);
    }
}
