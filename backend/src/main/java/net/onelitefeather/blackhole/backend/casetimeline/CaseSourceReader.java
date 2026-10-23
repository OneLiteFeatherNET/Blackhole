package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.AppealRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.EvidenceRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.PunishmentRow;

import java.util.List;

/**
 * Finds the authoritative rows whose lifecycle event is missing from the case log, for the
 * {@link CaseReconciler}. Every method returns at most {@code limit} rows of the look-back window
 * (their source time is at or after {@code since}), oldest first, and only rows without their event.
 */
public interface CaseSourceReader {

    /** Punishments without a {@code punishment.applied} event. */
    List<PunishmentRow> appliedMissing(long since, int limit);

    /** Revoked or lifted punishments without a {@code punishment.revoked} / {@code punishment.lifted} event. */
    List<PunishmentRow> endedMissing(long since, int limit);

    /** Punishments that ran out between {@code since} and {@code now} (not lifted) without a {@code punishment.expired} event. */
    List<PunishmentRow> expiredMissing(long since, long now, int limit);

    /** Appeals without an {@code appeal.filed} event. */
    List<AppealRow> filedMissing(long since, int limit);

    /** Decided appeals without an {@code appeal.decided} event. */
    List<AppealRow> decidedMissing(long since, int limit);

    /** Evidence of a punishment without an {@code evidence.attached} event. */
    List<EvidenceRow> attachedMissing(long since, int limit);

    /** Lifted or revoked evidence without its {@code evidence.lifted} / {@code evidence.revoked} event. */
    List<EvidenceRow> statusChangeMissing(long since, int limit);
}
